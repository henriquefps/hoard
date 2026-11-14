// Package webui holds the web gallery's built files (server/web, ADR 0018), embedded in the binary. `bun run build`
// in server/web writes them to dist/; without a build only dist/.gitkeep is embedded and the gallery answers with a
// short page saying so (Go tests and builds do not need the web build).
package webui

import (
	"embed"
	"io/fs"
)

//go:embed all:dist
var dist embed.FS

// Files returns the built gallery (index.html at the root) and whether it was built.
func Files() (fs.FS, bool) {
	sub, err := fs.Sub(dist, "dist")
	if err != nil {
		return nil, false
	}
	if _, err := fs.Stat(sub, "index.html"); err != nil {
		return sub, false
	}
	return sub, true
}
