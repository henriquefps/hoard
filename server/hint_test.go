package main

import (
	"fmt"
	"io/fs"
	"strings"
	"testing"
)

type codeErr int

func (c codeErr) Error() string { return fmt.Sprintf("sqlite code %d", int(c)) }
func (c codeErr) Code() int     { return int(c) }

func TestDatabaseHint(t *testing.T) {
	const chown = "own the data directory"
	for _, c := range []struct {
		err  error
		want string // substring; "!" prefix = must not appear
	}{
		{codeErr(14), chown},                   // SQLITE_CANTOPEN
		{codeErr(8), chown},                    // SQLITE_READONLY
		{codeErr(1032), chown},                 // SQLITE_READONLY_DBMOVED
		{codeErr(3), chown},                    // SQLITE_PERM
		{fs.ErrPermission, chown},              // EACCES from opening the file
		{codeErr(6410), "temporary directory"}, // SQLITE_IOERR_GETTEMPPATH
		{codeErr(10), "I/O error"},
		{codeErr(778), "I/O error"}, // SQLITE_IOERR_WRITE-ish extended code
		{fmt.Errorf("x"), "see err"},
	} {
		h := databaseHint(fmt.Errorf("migrate: %w", c.err))
		if !strings.Contains(h, c.want) {
			t.Errorf("%v: hint %q lacks %q", c.err, h, c.want)
		}
		if c.want != chown && strings.Contains(h, "chown") {
			t.Errorf("%v: must not blame ownership: %q", c.err, h)
		}
	}
}
