//go:build !hoarde2e

package main

import (
	"log/slog"

	"github.com/henriquefps/hoard/server/internal/server"
)

// e2eCompress does nothing in a real build: the fake encoder exists only in binaries built with -tags hoarde2e.
func e2eCompress(*server.Config, *slog.Logger) {}
