package compress

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"github.com/henriquefps/hoard/server/internal/fingerprint"
	"github.com/henriquefps/hoard/server/internal/media"
	"github.com/henriquefps/hoard/server/internal/store"
)

func i64(v int64) *int64       { return &v }
func f64(v float64) *float64   { return &v }
func iptr(v int) *int          { return &v }
func ms(d time.Duration) int64 { return d.Milliseconds() }

// openNas opens the original of a NAS item through the folder's read path and checks it is still the file queued
// (size and fingerprint).
func (m *Manager) openNas(ctx context.Context, it store.CompressItem) (*os.File, os.FileInfo, error) {
	dir, err := m.cfg.FolderDir(ctx, it.FolderID)
	if err != nil {
		return nil, nil, err
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return nil, nil, err
	}
	defer root.Close()
	f, err := root.Open(it.RelPath)
	if err != nil {
		return nil, nil, err
	}
	fi, err := f.Stat()
	if err != nil || !fi.Mode().IsRegular() {
		f.Close()
		return nil, nil, ErrChanged
	}
	fp, err := fingerprint.Of(f, fi.Size())
	if err != nil || fi.Size() != it.Size || fp != it.Fingerprint {
		f.Close()
		return nil, nil, ErrChanged
	}
	return f, fi, nil
}

// ProbeOf returns a fingerprint's probe, reading it (and storing it) when missing.
func (m *Manager) ProbeOf(ctx context.Context, fp string, f *os.File) (Probe, error) {
	got, err := m.cfg.Store.VideoProbes(ctx, ProbeVersion, []string{fp})
	if err != nil {
		return Probe{}, err
	}
	if b, ok := got[fp]; ok {
		var p Probe
		if json.Unmarshal(b, &p) == nil {
			return p, nil
		}
	}
	if m.cfg.Prober == nil {
		return Probe{}, errors.New("no prober")
	}
	p, err := m.cfg.Prober.Probe(ctx, f)
	if err != nil {
		return Probe{}, err
	}
	b, _ := json.Marshal(p)
	return p, m.cfg.Store.PutVideoProbe(ctx, fp, ProbeVersion, b)
}

// fileHDR reads file_meta's HDR of a fingerprint ("dolby-vision" is skipped).
func (m *Manager) fileHDR(ctx context.Context, fp string) string {
	metas, err := m.cfg.Store.FileMetas(ctx, []string{fp})
	if err != nil {
		return ""
	}
	var meta media.Meta
	if json.Unmarshal(metas[fp], &meta) == nil && meta.Video != nil {
		return meta.Video.HDR
	}
	return ""
}

// save writes an item when its stored state is one of from; false means it was cancelled meanwhile.
func (m *Manager) save(ctx context.Context, it *store.CompressItem, from ...string) bool {
	ok, err := m.cfg.Store.SaveCompressItem(context.WithoutCancel(ctx), *it, from...)
	if err != nil {
		m.log.Error("compress: save item", "item", it.ID, "err", err)
		return false
	}
	return ok
}

// process runs one leased item (state estimating) as far as it goes: skipped, failed, queued again (no space, a
// transient failure), or ready and then replaced.
func (m *Manager) process(parent context.Context, it store.CompressItem) {
	ctx, cancel := context.WithCancel(parent)
	m.register(it.ID, cancel)
	defer func() {
		cancel()
		m.unregister(it.ID)
	}()
	log := m.openLog(it)
	defer func() { _ = log.Close() }()
	start := time.Now()
	res := m.encodeItem(ctx, &it, log)
	if parent.Err() != nil { // shutting down: the restart puts the item back in the queue
		return
	}
	if ctx.Err() != nil { // cancelled
		m.removeWork(it.ID)
		fmt.Fprintln(log, "cancelled")
		return
	}
	m.log.Info("compress: item", "job", it.JobID, "item", it.ID, "state", it.State, "reason", it.Reason, "ms", time.Since(start).Milliseconds())
	if res && it.State == store.CompressReady {
		m.replaceReady(parent, it)
	}
}

// fail settles an item that cannot go on: transient reasons go back to the queue with a back-off until maxAttempts.
func (m *Manager) fail(ctx context.Context, it *store.CompressItem, reason string, log io.Writer, err error) {
	from := it.State
	it.Attempts++
	if err != nil {
		fmt.Fprintf(log, "failed (%s): %v\n", reason, err)
	}
	if (reason == ReasonEncoder || reason == ReasonNoSpace) && it.Attempts < maxAttempts {
		it.State, it.Reason = store.CompressQueued, reason
		it.NotBefore = m.now().Add(time.Duration(it.Attempts) * 5 * time.Minute).UnixMilli()
	} else {
		it.State, it.Reason = store.CompressFailed, reason
	}
	m.save(ctx, it, from)
	m.removeWork(it.ID)
}

func (m *Manager) encodeItem(ctx context.Context, it *store.CompressItem, log io.Writer) bool {
	j, err := m.cfg.Store.CompressJob(ctx, it.JobID)
	if err != nil {
		m.fail(ctx, it, ReasonEncoder, log, err)
		return false
	}
	prof, _ := ProfileByID(j.Profile)
	opts, _ := ParseOptions(j.Options)
	if it.Source != "nas" {
		m.fail(ctx, it, ReasonNotInTarget, log, errors.New("phone and backup items arrive with phase S2"))
		return false
	}
	src, fi, err := m.openNas(ctx, *it)
	if err != nil {
		if ctx.Err() != nil {
			return false
		}
		reason := ReasonSourceChanged
		if !errors.Is(err, ErrChanged) {
			reason = ReasonUnreadable
		}
		m.fail(ctx, it, reason, log, err)
		return false
	}
	defer src.Close()
	probe, err := m.ProbeOf(ctx, it.Fingerprint, src)
	if err != nil {
		if ctx.Err() != nil {
			return false
		}
		m.fail(ctx, it, ReasonUnreadable, log, err)
		return false
	}
	named, _ := m.cfg.Store.NamedFingerprints(ctx)
	_, outs, _ := m.cfg.Store.CompressHeldFingerprints(ctx, []string{it.Fingerprint})
	est := Decide(Input{Kind: "video", Paths: []string{it.RelPath}, Probe: &probe, Size: it.Size, HDR: m.fileHDR(ctx, it.Fingerprint),
		IsOutput: outs[it.Fingerprint], RevealNamed: named[it.Fingerprint]}, prof)
	it.DurationMs = i64(probe.DurationMs)
	if est.Decision == DecisionSkip {
		fmt.Fprintf(log, "skipped: %s\n", est.Reason)
		it.State, it.Reason = store.CompressSkipped, est.Reason
		if est.EstSize > 0 {
			it.EstSize = i64(est.EstSize)
		}
		m.save(ctx, it, store.CompressEstimating)
		return false
	}
	it.EstSize, it.EstMs = i64(est.EstSize), i64(est.EstMs)
	fmt.Fprintf(log, "estimate: %d -> about %d bytes (%.0f %%), %d ms; probe %s %dx%d %d-bit %.3f fps, %d b/s, bpp %.3f, warnings %v\n",
		it.Size, est.EstSize, est.EstSaving*100, est.EstMs, probe.Codec, probe.Width, probe.Height, probe.BitDepth, probe.AvgFrameRate,
		probe.BitRate, probe.BPP(), est.Warnings)
	work := m.workDir(it.ID)
	if work == "" {
		m.fail(ctx, it, ReasonNoSpace, log, errors.New("no backup root"))
		return false
	}
	_ = os.RemoveAll(work)
	if err := os.MkdirAll(work, 0o700); err != nil {
		m.fail(ctx, it, ReasonNoSpace, log, err)
		return false
	}
	if free, err := FreeBytes(work); err == nil && free < est.EstSize*3/2+1<<30 {
		fmt.Fprintf(log, "waiting for space: %d bytes free in the work directory\n", free)
		it.State, it.Reason = store.CompressQueued, ReasonNoSpace
		it.NotBefore = m.now().Add(noSpaceWait).UnixMilli()
		m.save(ctx, it, store.CompressEstimating)
		m.removeWork(it.ID)
		return false
	}
	// The original's SHA-256 (kept with the record).
	h := sha256.New()
	if _, err := src.Seek(0, io.SeekStart); err == nil {
		if _, err := io.Copy(h, src); err == nil {
			it.SHA256 = hex.EncodeToString(h.Sum(nil))
		}
	}
	if ctx.Err() != nil {
		return false
	}
	// The per-frame sidecar, before the encode.
	metaTmp := filepath.Join(work, "meta.json.gz")
	it.MetaSidecar = false
	if probe.PerFrameData() {
		f, err := os.Create(metaTmp)
		if err == nil {
			err = m.cfg.Encoder.Sidecar(ctx, src, f, log)
			if cerr := f.Close(); err == nil {
				err = cerr
			}
		}
		if err != nil { // a gate: the per-frame data leaves the file, so it must be saved first
			_ = os.Remove(metaTmp)
			if ctx.Err() != nil {
				return false
			}
			m.fail(ctx, it, ReasonVerifyMeta, log, fmt.Errorf("the per-frame metadata could not be saved: %w", err))
			return false
		}
		it.MetaSidecar = true
	}
	ext := strings.ToLower(path.Ext(it.RelPath))
	if ext != ".mov" {
		ext = ".mp4"
	}
	out := filepath.Join(work, "out"+ext)
	q := prof.Q
	floor := opts.Quality.VMAF
	var vm VMAFResult
	var encodeMs, verifyMs int64
	var decoder string
	from := store.CompressEstimating
	for step := 0; ; step++ {
		// encoding: one GPU session.
		select {
		case m.gpu <- struct{}{}:
		case <-ctx.Done():
			return false
		}
		it.State = store.CompressEncoding
		if !m.save(ctx, it, from) {
			<-m.gpu
			return false
		}
		from = store.CompressEncoding
		m.mu.Lock()
		m.encoding++
		m.mu.Unlock()
		t := time.Now()
		r, err := m.cfg.Encoder.Encode(ctx, EncodeRequest{Src: src, SrcMTime: fi.ModTime(), Probe: probe, Profile: prof, Q: q, Set: opts.Set,
			Out: out, Date: m.now().Format("2006-01-02"), Log: log, Progress: func(p float64) { m.setProgress(it.ID, p) }})
		m.mu.Lock()
		m.encoding--
		m.mu.Unlock()
		<-m.gpu
		encodeMs += ms(time.Since(t))
		if err != nil {
			if ctx.Err() != nil {
				return false
			}
			var ve *VerifyError
			if errors.As(err, &ve) { // the metadata restore: not retried
				m.fail(ctx, it, ve.Reason(), log, err)
			} else {
				m.fail(ctx, it, ReasonEncoder, log, err)
			}
			return false
		}
		decoder = r.Decoder
		// verifying: CPU.
		it.State = store.CompressVerifying
		if !m.save(ctx, it, from) {
			return false
		}
		from = store.CompressVerifying
		select {
		case m.cpu <- struct{}{}:
		case <-ctx.Done():
			return false
		}
		t = time.Now()
		err = m.cfg.Verifier.Check(ctx, src, out, log)
		if err == nil {
			vm, err = m.cfg.Verifier.VMAF(ctx, src, out, probe, log)
		}
		<-m.cpu
		verifyMs += ms(time.Since(t))
		if err != nil {
			if ctx.Err() != nil {
				return false
			}
			var ve *VerifyError
			if errors.As(err, &ve) {
				m.fail(ctx, it, ve.Reason(), log, err)
			} else {
				m.fail(ctx, it, ReasonEncoder, log, err)
			}
			return false
		}
		fmt.Fprintf(log, "q %d: VMAF %.2f (1%% low %.2f, %s)\n", q, vm.Mean, vm.Low, vm.Mode)
		if opts.Quality.Mode == ModeFixed || vm.Mean >= floor || step == 2 {
			break
		}
		q -= 2 // mode C: q-2, then q-4 at most
	}
	it.Q, it.VmafMean, it.VmafLow, it.VmafMode = iptr(q), f64(vm.Mean), f64(vm.Low), vm.Mode
	it.EncodeMs, it.VerifyMs, it.Decoder = i64(encodeMs), i64(verifyMs), decoder
	if vm.Mean < floor-1 {
		m.fail(ctx, it, ReasonVerifyQuality, log, fmt.Errorf("VMAF %.2f under %.1f after mode C", vm.Mean, floor-1))
		return false
	}
	ofi, err := os.Stat(out)
	if err != nil {
		m.fail(ctx, it, ReasonEncoder, log, err)
		return false
	}
	saving := 1 - float64(ofi.Size())/float64(it.Size)
	fmt.Fprintf(log, "saving: %d -> %d bytes (%.1f %%)\n", it.Size, ofi.Size(), saving*100)
	if saving < minSaving {
		it.State, it.Reason, it.OutSize = store.CompressSkipped, ReasonSmallSaving, i64(ofi.Size())
		m.save(ctx, it, store.CompressVerifying)
		m.removeWork(it.ID)
		return false
	}
	of, err := os.Open(out)
	if err != nil {
		m.fail(ctx, it, ReasonEncoder, log, err)
		return false
	}
	ofp, err := fingerprint.Of(of, ofi.Size())
	oh := sha256.New()
	if err == nil {
		_, _ = of.Seek(0, io.SeekStart)
		_, err = io.Copy(oh, of)
	}
	of.Close()
	if err != nil {
		m.fail(ctx, it, ReasonEncoder, log, err)
		return false
	}
	it.OutFingerprint, it.OutSHA256, it.OutSize = ofp, hex.EncodeToString(oh.Sum(nil)), i64(ofi.Size())
	if it.MetaSidecar {
		dst := m.MetaPath(ofp)
		err := os.MkdirAll(filepath.Dir(dst), 0o700)
		if err == nil {
			err = moveFile(metaTmp, dst)
		}
		if err != nil { // the gate holds until the sidecar is in the data directory
			m.fail(ctx, it, ReasonVerifyMeta, log, fmt.Errorf("the per-frame metadata could not be kept: %w", err))
			return false
		}
	}
	it.State, it.Reason = store.CompressReady, ""
	return m.save(ctx, it, store.CompressVerifying)
}

// workOutput finds a ready item's output in its work directory and checks it by size and SHA-256.
func (m *Manager) workOutput(it store.CompressItem) (string, bool) {
	dir := m.workDir(it.ID)
	if dir == "" || it.OutSize == nil {
		return "", false
	}
	for _, ext := range []string{".mp4", ".mov"} {
		p := filepath.Join(dir, "out"+ext)
		f, err := os.Open(p)
		if err != nil {
			continue
		}
		fi, err := f.Stat()
		if err != nil || fi.Size() != *it.OutSize {
			f.Close()
			return "", false
		}
		h := sha256.New()
		_, err = io.Copy(h, f)
		f.Close()
		if err != nil || hex.EncodeToString(h.Sum(nil)) != it.OutSHA256 {
			return "", false
		}
		return p, true
	}
	return "", false
}

// moveFile renames src to dst, or copies it (fsynced) when they are on different filesystems: the work directory is on
// the backup volume, the per-frame sidecars in the data directory.
func moveFile(src, dst string) error {
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".part"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	if err == nil {
		err = out.Sync()
	}
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp, dst)
	}
	if err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Remove(src)
}
