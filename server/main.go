// Command reveal-sync is the self-hosted sync server for Reveal: edits and library metadata, smart previews and
// original backups for every paired device, plus read-only NAS folders it indexes and serves (ADR 0017).
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
	"strings"
	"syscall"
	"time"

	"github.com/henriquefps/reveal/sync-server/internal/server"
	"github.com/henriquefps/reveal/sync-server/internal/store"
)

// Set at build time with -ldflags "-X main.version=... -X main.commit=...".
var (
	version = "dev"
	commit  = "none"
)

const usage = `reveal-sync: self-hosted sync server for Reveal

Usage:
  reveal-sync [serve] [flags]   run the server (default)
  reveal-sync version           print the version

Flags (each falls back to the environment variable in brackets):
  --port string           port to listen on [PORT] (default 3071)
  --listen string         address to bind [LISTEN] (default all interfaces)
  --data-dir string       database and settings [DATA_DIR] (default ./data)
  --mounts string         comma-separated mount roots the setup page may offer [MOUNTS] (default /mnt)
  --backup-root string    backup folder to use until one is chosen on the setup page [BACKUP_ROOT]
  --nas-interval duration how often NAS folders are walked again [NAS_INTERVAL] (default 30m)
  --log-format string     json or text [LOG_FORMAT] (default json)

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
		fmt.Fprintf(stdout, "reveal-sync %s (commit %s)\n", version, commit)
		return 0
	case "help":
		fmt.Fprint(stdout, usage)
		return 0
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n%s", cmd, usage)
		return 2
	}
}

func envOr(name, fallback string) string {
	if v, ok := os.LookupEnv(name); ok && v != "" {
		return v
	}
	return fallback
}

type options struct {
	port, listen, dataDir, mounts, backupRoot, logFormat string
	nasInterval                                          time.Duration
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
	if err := fs.Parse(args); err != nil {
		return o, err
	}
	if fs.NArg() > 0 {
		return o, fmt.Errorf("unexpected arguments: %v", fs.Args())
	}
	if o.nasInterval < time.Minute {
		return o, fmt.Errorf("--nas-interval must be at least 1m")
	}
	return o, nil
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
	st, err := store.Open(filepath.Join(dir, "reveal-sync.db"))
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
	srv, err := server.New(ctx, server.Config{Version: version, Mounts: mountList, BackupRoot: o.backupRoot,
		NasInterval: o.nasInterval, Logger: logger}, st)
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
	logger.Info("reveal-sync listening", "version", version, "addr", ln.Addr().String(), "data", dir, "mounts", mountList)
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
