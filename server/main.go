// Command hoard-server is the self-hosted photo server of Hoard and Reveal (formerly Reveal's reveal-sync, ADR 0012):
// edits and library metadata, smart previews and original backups for every paired device, plus read-only NAS
// folders it indexes and serves (protocol: ADR 0013).
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/henriquefps/hoard/server/internal/server"
	"github.com/henriquefps/hoard/server/internal/store"
)

// Set at build time with -ldflags "-X main.version=... -X main.commit=...".
var (
	version = "dev"
	commit  = "none"
)

const usage = `hoard-server: self-hosted photo server for Hoard and Reveal

Usage:
  hoard-server [serve] [flags]   run the server (default)
  hoard-server version           print the version

Flags (each falls back to the environment variable in brackets):
  --port string           port to listen on [PORT] (default 3071)
  --listen string         address to bind [LISTEN] (default all interfaces)
  --data-dir string       database and settings [DATA_DIR] (default ./data)
  --mounts string         comma-separated mount roots the setup page may offer [MOUNTS] (default /mnt)
  --backup-root string    backup folder to use until one is chosen on the setup page [BACKUP_ROOT]
  --nas-interval duration how often NAS folders are walked again [NAS_INTERVAL] (default 30m)
  --log-format string     json or text [LOG_FORMAT] (default json)
  --ffprobe string        ffprobe binary for video metadata [FFPROBE] (default ffprobe in PATH)
  --probe-workers int     ffprobe runs at a time [PROBE_WORKERS] (default 2)
  --probe-timeout duration  time limit of one ffprobe run [PROBE_TIMEOUT] (default 30s)
  --previews-dir string   where gallery previews are kept [PREVIEWS_DIR] (default <data-dir>/previews)
  --vips string           libvips' vips program, for photo previews [VIPS] (default vips in PATH)
  --ffmpeg string         ffmpeg, for video posters and proxies [FFMPEG] (default ffmpeg in PATH)
  --preview-workers int   photo/poster previews made at a time [PREVIEW_WORKERS] (default 2)
  --proxy-workers int     video proxies made at a time [PROXY_WORKERS] (default 1)
  --preview-threads int   threads of one vips/ffmpeg run [PREVIEW_THREADS] (default 2)
  --preview-backlog list  previews made for every item ahead of requests: image, proxy, none
                          [PREVIEW_BACKLOG] (default image,proxy)

On the first start the log shows a one-time setup code: open http://<server>:3071/admin/ from your
network and enter it to set the admin password. The setup page only answers the local network and
Tailscale unless you allow it through a tunnel. Devices always need their own key (pair by QR code).
`

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	cmd := "serve"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	switch cmd {
	case "serve":
		return serve(args, stderr)
	case "version":
		fmt.Fprintf(stdout, "hoard-server %s (commit %s)\n", version, commit)
		return 0
	case "help":
		fmt.Fprint(stdout, usage)
		return 0
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n%s", cmd, usage)
		return 2
	}
}

// Database file names in the data directory. A data directory made by reveal-sync keeps its file name (no rename, so
// rolling back to the reveal-sync image stays possible); a new one gets the hoard-server name (ADR 0012).
const (
	databaseFile       = "hoard-server.db"
	legacyDatabaseFile = "reveal-sync.db"
)

// databasePath picks the database in dir: hoard-server.db if it exists, else reveal-sync.db if that exists, else a new
// hoard-server.db. legacy reports the reveal-sync name.
func databasePath(dir string) (path string, legacy bool) {
	current := filepath.Join(dir, databaseFile)
	if _, err := os.Stat(current); err == nil {
		return current, false
	}
	old := filepath.Join(dir, legacyDatabaseFile)
	if _, err := os.Stat(old); err == nil {
		return old, true
	}
	return current, false
}

func envOr(name, fallback string) string {
	if v, ok := os.LookupEnv(name); ok && v != "" {
		return v
	}
	return fallback
}

type options struct {
	port, listen, dataDir, mounts, backupRoot, logFormat, ffprobe string
	nasInterval, probeTimeout                                     time.Duration
	probeWorkers                                                  int
	previewsDir, vips, ffmpeg, previewBacklog                     string
	previewWorkers, proxyWorkers, previewThreads                  int
}

// intOption reads an integer flag whose default comes from an environment variable.
func intOption(fs *flag.FlagSet, dst *int, name, env string, def int) error {
	v, err := strconv.Atoi(envOr(env, strconv.Itoa(def)))
	if err != nil {
		return fmt.Errorf("%s: %w", env, err)
	}
	fs.IntVar(dst, name, v, "")
	return nil
}

func parseOptions(args []string, stderr io.Writer) (options, error) {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	fs.SetOutput(stderr)
	fs.Usage = func() { fmt.Fprint(stderr, usage) }
	var o options
	fs.StringVar(&o.port, "port", envOr("PORT", "3071"), "")
	fs.StringVar(&o.listen, "listen", envOr("LISTEN", ""), "")
	fs.StringVar(&o.dataDir, "data-dir", envOr("DATA_DIR", "data"), "")
	fs.StringVar(&o.mounts, "mounts", envOr("MOUNTS", "/mnt"), "")
	fs.StringVar(&o.backupRoot, "backup-root", envOr("BACKUP_ROOT", ""), "")
	fs.StringVar(&o.logFormat, "log-format", envOr("LOG_FORMAT", "json"), "")
	interval, err := time.ParseDuration(envOr("NAS_INTERVAL", "30m"))
	if err != nil {
		return o, fmt.Errorf("NAS_INTERVAL: %w", err)
	}
	fs.DurationVar(&o.nasInterval, "nas-interval", interval, "")
	fs.StringVar(&o.ffprobe, "ffprobe", envOr("FFPROBE", "ffprobe"), "")
	probeTimeout, err := time.ParseDuration(envOr("PROBE_TIMEOUT", "30s"))
	if err != nil {
		return o, fmt.Errorf("PROBE_TIMEOUT: %w", err)
	}
	fs.DurationVar(&o.probeTimeout, "probe-timeout", probeTimeout, "")
	probeWorkers, err := strconv.Atoi(envOr("PROBE_WORKERS", "2"))
	if err != nil {
		return o, fmt.Errorf("PROBE_WORKERS: %w", err)
	}
	fs.IntVar(&o.probeWorkers, "probe-workers", probeWorkers, "")
	fs.StringVar(&o.previewsDir, "previews-dir", envOr("PREVIEWS_DIR", ""), "")
	fs.StringVar(&o.vips, "vips", envOr("VIPS", "vips"), "")
	fs.StringVar(&o.ffmpeg, "ffmpeg", envOr("FFMPEG", "ffmpeg"), "")
	fs.StringVar(&o.previewBacklog, "preview-backlog", envOr("PREVIEW_BACKLOG", "image,proxy"), "")
	for _, opt := range []struct {
		dst       *int
		name, env string
		def       int
	}{{&o.previewWorkers, "preview-workers", "PREVIEW_WORKERS", 2}, {&o.proxyWorkers, "proxy-workers", "PROXY_WORKERS", 1},
		{&o.previewThreads, "preview-threads", "PREVIEW_THREADS", 2}} {
		if err := intOption(fs, opt.dst, opt.name, opt.env, opt.def); err != nil {
			return o, err
		}
	}
	if err := fs.Parse(args); err != nil {
		return o, err
	}
	if fs.NArg() > 0 {
		return o, fmt.Errorf("unexpected arguments: %v", fs.Args())
	}
	if o.nasInterval < time.Minute {
		return o, fmt.Errorf("--nas-interval must be at least 1m")
	}
	if o.probeWorkers < 1 || o.probeWorkers > 16 {
		return o, fmt.Errorf("--probe-workers must be 1 to 16")
	}
	if o.probeTimeout < time.Second {
		return o, fmt.Errorf("--probe-timeout must be at least 1s")
	}
	if o.previewWorkers < 1 || o.previewWorkers > 16 || o.proxyWorkers < 1 || o.proxyWorkers > 8 || o.previewThreads < 1 || o.previewThreads > 32 {
		return o, fmt.Errorf("--preview-workers must be 1 to 16, --proxy-workers 1 to 8, --preview-threads 1 to 32")
	}
	if _, err := backlogJobs(o.previewBacklog); err != nil {
		return o, err
	}
	return o, nil
}

// backlogJobs parses --preview-backlog: a comma-separated list of image and proxy, or none.
func backlogJobs(v string) ([]string, error) {
	var out []string
	for _, j := range strings.Split(v, ",") {
		switch j = strings.TrimSpace(j); j {
		case "image", "proxy":
			out = append(out, j)
		case "none", "":
		default:
			return nil, fmt.Errorf("--preview-backlog is a list of image, proxy, or none")
		}
	}
	return out, nil
}

func serve(args []string, stderr io.Writer) int {
	o, err := parseOptions(args, stderr)
	if errors.Is(err, flag.ErrHelp) {
		return 0
	}
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 2
	}
	restrictUmask()
	var handler slog.Handler = slog.NewJSONHandler(stderr, nil)
	if o.logFormat == "text" {
		handler = slog.NewTextHandler(stderr, nil)
	}
	logger := slog.New(handler)

	dir, err := filepath.Abs(o.dataDir)
	if err == nil {
		err = os.MkdirAll(dir, 0o700)
	}
	if err != nil {
		logger.Error("data directory", "dir", o.dataDir, "err", err)
		return 1
	}
	dbPath, legacy := databasePath(dir)
	if legacy {
		logger.Info("using the reveal-sync database file name: kept so the old image can still open it", "file", filepath.Base(dbPath))
	}
	st, err := store.Open(dbPath)
	if err != nil {
		logger.Error("cannot open the database", "dir", dir, "err", err,
			"hint", "the server's user must own the data directory, e.g. for the Docker image: sudo chown -R 65532:65532 ./data")
		return 1
	}
	defer st.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var mountList []string
	for _, m := range strings.Split(o.mounts, ",") {
		if m = strings.TrimSpace(m); m != "" {
			mountList = append(mountList, m)
		}
	}
	previewsDir := o.previewsDir
	if previewsDir == "" {
		previewsDir = filepath.Join(dir, "previews")
	}
	backlog, _ := backlogJobs(o.previewBacklog)
	srv, err := server.New(ctx, server.Config{Version: version, Mounts: mountList, BackupRoot: o.backupRoot,
		NasInterval: o.nasInterval, Logger: logger, FFprobe: o.ffprobe, ProbeWorkers: o.probeWorkers, ProbeTimeout: o.probeTimeout,
		PreviewsDir: previewsDir, Vips: o.vips, FFmpeg: o.ffmpeg, PreviewWorkers: o.previewWorkers, ProxyWorkers: o.proxyWorkers,
		PreviewThreads: o.previewThreads, PreviewBacklog: backlog}, st)
	if err != nil {
		logger.Error("cannot start", "err", err)
		return 1
	}
	httpSrv := &http.Server{
		Addr:              net.JoinHostPort(o.listen, o.port),
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       10 * time.Minute, // an 8 MiB chunk on a slow uplink
		WriteTimeout:      30 * time.Minute, // a 60 MB original to a slow phone
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    64 << 10,
	}
	ln, err := net.Listen("tcp", httpSrv.Addr)
	if err != nil {
		logger.Error("cannot listen", "addr", httpSrv.Addr, "err", err)
		return 1
	}
	logger.Info("hoard-server listening", "version", version, "addr", ln.Addr().String(), "data", dir, "mounts", mountList)
	srv.Run(ctx)
	errc := make(chan error, 1)
	go func() { errc <- httpSrv.Serve(ln) }()
	select {
	case err := <-errc:
		logger.Error("server stopped", "err", err)
		return 1
	case <-ctx.Done():
	}
	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpSrv.Shutdown(shutdownCtx); err != nil {
		logger.Error("shutdown", "err", err)
		return 1
	}
	return 0
}
