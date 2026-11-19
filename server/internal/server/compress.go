package server

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/henriquefps/hoard/server/internal/compress"
	"github.com/henriquefps/hoard/server/internal/items"
	"github.com/henriquefps/hoard/server/internal/store"
)

// newCompress makes the video compression manager (ADR 0025) from the configuration: real tools unless a test gave
// fakes.
func (s *Server) newCompress() *compress.Manager {
	cfg := s.cfg
	look := func(name string) string {
		if name == "" {
			return ""
		}
		p, err := exec.LookPath(name)
		if err != nil {
			return ""
		}
		return p
	}
	ffmpeg, ffprobe, exiftool := look(cfg.FFmpeg), look(cfg.FFprobe), look(cfg.Exiftool)
	enc := cfg.CompressEncoder
	if enc == nil {
		enc = &compress.NvencEncoder{FFmpeg: ffmpeg, Exiftool: exiftool, Nice: compress.LowerPriority}
	}
	ver := cfg.CompressVerifier
	if ver == nil {
		ver = &compress.FFVerifier{FFmpeg: ffmpeg, FFprobe: ffprobe, Exiftool: exiftool, Threads: max(cfg.VMAFThreads, 1), Nice: compress.LowerPriority}
	}
	det := cfg.CompressDetector
	if det == nil {
		det = &compress.ToolDetector{FFmpeg: ffmpeg, Exiftool: exiftool, NvidiaSMI: look(cfg.NvidiaSMI)}
	}
	nvml := cfg.CompressNVML
	if nvml == nil {
		if p := look(cfg.NvidiaSMI); p != "" {
			nvml = compress.SMI{Path: p}
		}
	}
	dataDir := filepath.Dir(cfg.EditsDir)
	if cfg.EditsDir == "" {
		dataDir = filepath.Dir(cfg.PreviewsDir)
	}
	return compress.New(compress.Config{
		Store: s.store, Log: s.log, DataDir: dataDir,
		WorkRoot: func() string {
			if bs := s.blobStore(); bs != nil {
				return filepath.Join(filepath.Dir(bs.Path()), ".hoard-compress", "work")
			}
			return ""
		},
		Mount:     &compress.WriteMount{ReadRoot: cfg.NasReadRoot, WriteRoot: cfg.NasWriteRoot, Link: cfg.CompressLinker},
		FolderDir: s.nasFolderDir,
		Prober:    &compress.Prober{FFprobe: ffprobe, Timeout: max(cfg.ProbeTimeout, 30*time.Second)},
		Encoder:   enc, Verifier: ver, Detector: det, NVML: nvml,
		Sessions: max(cfg.CompressSessions, 1), CPUWorkers: 2, Poll: cfg.CompressPoll,
		Refresh:     s.refreshItems,
		RequestWalk: func(ids []string) { s.indexer.RequestWalk(ids) },
	})
}

// nasFolderDir resolves a NAS folder's read path.
func (s *Server) nasFolderDir(ctx context.Context, folderID string) (string, error) {
	f, err := s.store.NasFolder(ctx, folderID)
	if err != nil {
		return "", err
	}
	return s.roots.Resolve(f.Path)
}

// refreshItems rebuilds the items that hold (or held) the fingerprints: the derived ids and every item with one of them
// as a member.
func (s *Server) refreshItems(ctx context.Context, fps []string) {
	ids := map[string]bool{}
	var list []string
	addID := func(id string) {
		if id != "" && !ids[id] {
			ids[id] = true
			list = append(list, id)
		}
	}
	for _, fp := range fps {
		if fp != "" {
			addID(items.ID(fp))
		}
	}
	if files, err := s.store.ItemFilesByFingerprint(ctx, fps); err == nil {
		for _, f := range files {
			addID(f.ItemID)
		}
	}
	if _, err := s.indexer.Rebuild(ctx, list); err != nil && ctx.Err() == nil {
		s.log.Error("compress: rebuild items", "err", err)
	}
}

var _ = store.CompressQueued

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
