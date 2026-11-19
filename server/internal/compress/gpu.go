package compress

import (
	"bytes"
	"context"
	"errors"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// GPUStatus is what the start-up check found (ADR 0025 "Detection").
type GPUStatus struct {
	Available  bool     `json:"available"`
	Reason     string   `json:"reason,omitempty"` // no_gpu | no_tools
	GPU        string   `json:"gpu,omitempty"`
	Driver     string   `json:"driver,omitempty"`
	Codecs     []string `json:"codecs"`
	VMAF       bool     `json:"vmaf"`
	CUDADecode bool     `json:"cudaDecode"`
	Exiftool   bool     `json:"exiftool"`
	CheckedAt  int64    `json:"checkedAt"`
	Detail     string   `json:"detail,omitempty"` // the failing step (admin page only)
}

// Detector finds out whether this host can compress.
type Detector interface {
	Detect(ctx context.Context) GPUStatus
}

// ToolDetector checks ffmpeg's encoders, a 0.2 s testsrc2 encode with hevc_nvenc (and av1_nvenc), a CUDA decode of it,
// libvmaf and exiftool; nvidia-smi names the GPU and the driver.
type ToolDetector struct {
	FFmpeg, Exiftool, NvidiaSMI string
}

func run(ctx context.Context, timeout time.Duration, stdin []byte, name string, args ...string) ([]byte, error) {
	if name == "" {
		return nil, exec.ErrNotFound
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	//nolint:gosec // configured tools with fixed arguments
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = toolEnv()
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	var out bytes.Buffer
	cmd.Stdout = &limited{w: &out, n: 16 << 20}
	errTail := &tail{n: 4096}
	cmd.Stderr = errTail
	err := cmd.Run()
	if err != nil {
		return out.Bytes(), errors.New(lastLine(errTail.String()) + " (" + err.Error() + ")")
	}
	return out.Bytes(), nil
}

func (d *ToolDetector) testEncode(ctx context.Context, codec string) ([]byte, error) {
	return run(ctx, 30*time.Second, nil, d.FFmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-f", "lavfi",
		"-i", "testsrc2=s=256x144:r=25:d=0.2", "-c:v", codec, "-pix_fmt", "yuv420p", "-f", "mp4",
		"-movflags", "frag_keyframe+empty_moov", "pipe:1")
}

// Detect implements Detector.
func (d *ToolDetector) Detect(ctx context.Context) GPUStatus {
	st := GPUStatus{Codecs: []string{}, CheckedAt: time.Now().UnixMilli()}
	if out, err := run(ctx, 10*time.Second, nil, d.NvidiaSMI, "--query-gpu=name,driver_version", "--format=csv,noheader"); err == nil {
		if name, drv, ok := strings.Cut(strings.TrimSpace(strings.Split(string(out), "\n")[0]), ","); ok {
			st.GPU, st.Driver = strings.TrimSpace(name), strings.TrimSpace(drv)
		}
	}
	enc, err := run(ctx, 10*time.Second, nil, d.FFmpeg, "-hide_banner", "-encoders")
	if err != nil || !bytes.Contains(enc, []byte("hevc_nvenc")) {
		st.Reason, st.Detail = "no_tools", "ffmpeg has no hevc_nvenc"
		return st
	}
	clip, err := d.testEncode(ctx, "hevc_nvenc")
	if err != nil || len(clip) == 0 {
		st.Reason, st.Detail = "no_gpu", "hevc_nvenc test encode failed"
		if err != nil {
			st.Detail += ": " + err.Error()
		}
		return st
	}
	st.Codecs = append(st.Codecs, "hevc")
	if bytes.Contains(enc, []byte("av1_nvenc")) {
		if out, err := d.testEncode(ctx, "av1_nvenc"); err == nil && len(out) > 0 {
			st.Codecs = append(st.Codecs, "av1")
		}
	}
	if _, err := run(ctx, 30*time.Second, clip, d.FFmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-hwaccel", "cuda",
		"-hwaccel_flags", "+unsafe_output", "-i", "pipe:0", "-f", "null", "-"); err == nil {
		st.CUDADecode = true
	}
	if f, err := run(ctx, 10*time.Second, nil, d.FFmpeg, "-hide_banner", "-filters"); err == nil && bytes.Contains(f, []byte("libvmaf")) {
		st.VMAF = true
	}
	if out, err := run(ctx, 20*time.Second, nil, d.Exiftool, "-ver"); err == nil && len(bytes.TrimSpace(out)) > 0 {
		st.Exiftool = true
	}
	if !st.VMAF || !st.Exiftool {
		st.Reason, st.Detail = "no_tools", "libvmaf or exiftool missing"
		return st
	}
	st.Available = true
	return st
}

// GPULoad is one reading of the GPU (NVML through nvidia-smi).
type GPULoad struct {
	Utilization int // percent
	Sessions    int // NVENC sessions of every process
}

// NVML reads the GPU's load.
type NVML interface {
	Load(ctx context.Context) (GPULoad, error)
}

// SMI is NVML through nvidia-smi (the "utility" driver capability).
type SMI struct{ Path string }

// Load implements NVML.
func (s SMI) Load(ctx context.Context) (GPULoad, error) {
	out, err := run(ctx, 10*time.Second, nil, s.Path, "--query-gpu=utilization.gpu,encoder.stats.sessionCount", "--format=csv,noheader,nounits")
	if err != nil {
		return GPULoad{}, err
	}
	var l GPULoad
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") { // every GPU: the busiest counts
		a, b, ok := strings.Cut(line, ",")
		if !ok {
			continue
		}
		u, err1 := strconv.Atoi(strings.TrimSpace(a))
		n, err2 := strconv.Atoi(strings.TrimSpace(b))
		if err1 != nil || err2 != nil {
			continue
		}
		l.Utilization = max(l.Utilization, u)
		l.Sessions += n
	}
	return l, nil
}
