package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strings"
)

// Phone backup (ADR 0024): the mirror's index. A root is a folder a phone backs up; an entry is one file of a phone,
// current or kept. The tree on disk follows the database (package mirror).

// Where an entry's content is.
const (
	StoreBlob = "blob" // uploaded bytes in the blob store
	StoreNAS  = "nas"  // an indexed NAS file (a reference)
	StoreNone = "none" // waiting for an upload
)

// Why an entry left the phone (removed_reason).
const (
	RemovedDeleted  = "deleted"
	RemovedMoved    = "moved"
	RemovedFreed    = "freed"
	RemovedReplaced = "replaced"
)

// Report states (POST /v1/backup/report).
const (
	StateStored     = "stored"
	StateNAS        = "nas"
	StateMissing    = "missing"
	StateInProgress = "in_progress"
	// StateRefused is an entry the server did not take (a reason says why); the rest of its batch was applied.
	StateRefused = "refused"
	StateDiffers = "differs"
)

// Root errors.
var (
	// ErrCovered: the path is inside another root of the phone (CoveredBy names it).
	ErrCovered = errors.New("covered by another root")
	// ErrTooManyRoots: the phone has maxRoots roots that are not stopped.
	ErrTooManyRoots = errors.New("too many roots")
	// ErrMirror: a mirror entry names the original (DELETE /v1/originals answers 409, reason mirror).
	ErrMirror = errors.New("named by a phone backup")
)

// CoveredError carries the root that covers a path.
type CoveredError struct{ RootID string }

func (e *CoveredError) Error() string { return "covered by root " + e.RootID }
func (e *CoveredError) Unwrap() error { return ErrCovered }

// MirrorRoot is a folder of a phone that the server mirrors.
type MirrorRoot struct {
	ID           string
	PhysicalID   string
	Volume       string
	Path         string
	CreatedBy    string
	CreatedAt    int64
	PausedAt     *int64
	StoppedAt    *int64
	LastReportAt *int64
	Types        []string
	DirName      string // the phone's folder under the backup root
	App          string // the app of the device that made it ("" when unknown)
}

// Paused, Stopped report the root's state.
func (r MirrorRoot) Paused() bool  { return r.PausedAt != nil }
func (r MirrorRoot) Stopped() bool { return r.StoppedAt != nil }

const mirrorRootCols = `r.id, r.physical_id, r.volume, r.path, r.created_by, r.created_at, r.paused_at, r.stopped_at, r.last_report_at,
	r.types, d.dir_name, coalesce((SELECT v.app FROM devices v WHERE v.id = r.created_by), '')`

const mirrorRootFrom = ` FROM mirror_roots r JOIN mirror_devices d ON d.physical_id = r.physical_id`

func scanMirrorRoot(row interface{ Scan(...any) error }) (MirrorRoot, error) {
	var r MirrorRoot
	var types string
	err := row.Scan(&r.ID, &r.PhysicalID, &r.Volume, &r.Path, &r.CreatedBy, &r.CreatedAt, &r.PausedAt, &r.StoppedAt, &r.LastReportAt,
		&types, &r.DirName, &r.App)
	if errors.Is(err, sql.ErrNoRows) {
		return r, ErrNotFound
	}
	if err == nil {
		_ = json.Unmarshal([]byte(types), &r.Types)
		if r.Types == nil {
			r.Types = []string{}
		}
	}
	return r, err
}

func queryMirrorRoots(ctx context.Context, q rowsQueryer, where string, args ...any) ([]MirrorRoot, error) {
	rows, err := q.QueryContext(ctx, "SELECT "+mirrorRootCols+mirrorRootFrom+" "+where+" ORDER BY r.physical_id, r.volume, r.path", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []MirrorRoot{}
	for rows.Next() {
		r, err := scanMirrorRoot(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// MirrorRoots lists the roots of one physical device ("" = every root).
func (s *Store) MirrorRoots(ctx context.Context, physicalID string) ([]MirrorRoot, error) {
	if physicalID == "" {
		return queryMirrorRoots(ctx, s.db, "")
	}
	return queryMirrorRoots(ctx, s.db, "WHERE r.physical_id = ?", physicalID)
}

// MirrorRoot returns one root.
func (s *Store) MirrorRoot(ctx context.Context, id string) (MirrorRoot, error) {
	return scanMirrorRoot(s.db.QueryRowContext(ctx, "SELECT "+mirrorRootCols+mirrorRootFrom+" WHERE r.id = ?", id))
}

// MirrorDevice is a phone with a backup.
type MirrorDevice struct {
	PhysicalID string `json:"physicalDeviceId"`
	DirName    string `json:"folder"`
	CreatedAt  int64  `json:"createdAt"`
}

// MirrorDevices lists the phones with a backup.
func (s *Store) MirrorDevices(ctx context.Context) ([]MirrorDevice, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT physical_id, dir_name, created_at FROM mirror_devices ORDER BY dir_name")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []MirrorDevice{}
	for rows.Next() {
		var d MirrorDevice
		if err := rows.Scan(&d.PhysicalID, &d.DirName, &d.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// MirrorDeviceByID returns a phone's backup folder.
func (s *Store) MirrorDeviceByID(ctx context.Context, physicalID string) (MirrorDevice, error) {
	var d MirrorDevice
	err := s.db.QueryRowContext(ctx, "SELECT physical_id, dir_name, created_at FROM mirror_devices WHERE physical_id = ?", physicalID).
		Scan(&d.PhysicalID, &d.DirName, &d.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return d, ErrNotFound
	}
	return d, err
}

// RenameMirrorDevice stores a phone's new folder name (the admin page renames it on disk first).
func (s *Store) RenameMirrorDevice(ctx context.Context, physicalID, dirName string) error {
	res, err := s.db.ExecContext(ctx, "UPDATE mirror_devices SET dir_name = ? WHERE physical_id = ?", dirName, physicalID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// PhysicalNames names every physical device: the name of its oldest live device, else of its oldest device.
func (s *Store) PhysicalNames(ctx context.Context) (map[string]string, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT physical_id, name FROM devices WHERE physical_id IS NOT NULL
		ORDER BY (revoked_at IS NULL) DESC, created_at ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var p, n string
		if err := rows.Scan(&p, &n); err != nil {
			return nil, err
		}
		if _, ok := out[p]; !ok {
			out[p] = n
		}
	}
	return out, rows.Err()
}

// PhysicalLive reports, per physical device, whether it has a device that is not revoked.
func (s *Store) PhysicalLive(ctx context.Context) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT DISTINCT physical_id FROM devices WHERE revoked_at IS NULL AND physical_id IS NOT NULL")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var p string
		if err := rows.Scan(&p); err != nil {
			return nil, err
		}
		out[p] = true
	}
	return out, rows.Err()
}

func within(root, p string) bool { return root == "" || p == root || strings.HasPrefix(p, root+"/") }

// NewMirrorRoot is a root a device asks for.
type NewMirrorRoot struct {
	PhysicalID string
	DeviceID   string
	Volume     string
	Path       string
	Types      []string // nil = keep (an existing root) or the default
	DirName    string   // the phone's folder when it has none yet
	MaxRoots   int
}

// RootResult is what CreateMirrorRoot did.
type RootResult struct {
	Root     MirrorRoot
	Created  bool
	Absorbed []string // roots inside the new one: their entries moved to it and they are gone
}

// CreateMirrorRoot starts mirroring a folder (POST /v1/backup/roots): an existing root of the same path is resumed; a
// path inside a root that is not stopped is refused (CoveredError); roots inside the new path are absorbed. The phone's
// mirror_devices row is made with its first root.
func (s *Store) CreateMirrorRoot(ctx context.Context, n NewMirrorRoot) (RootResult, error) {
	var res RootResult
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		res = RootResult{}
		var typesJSON any
		if n.Types != nil {
			b, _ := json.Marshal(n.Types)
			typesJSON = string(b)
		}
		var have int
		if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM mirror_devices WHERE physical_id = ?", n.PhysicalID).Scan(&have); err != nil {
			return err
		}
		if have == 0 {
			name := n.DirName
			for i := 2; ; i++ {
				var taken int
				if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM mirror_devices WHERE dir_name = ?", name).Scan(&taken); err != nil {
					return err
				}
				if taken == 0 {
					break
				}
				name = fmt.Sprintf("%s %d", n.DirName, i)
			}
			if _, err := tx.ExecContext(ctx, "INSERT INTO mirror_devices (physical_id, dir_name, created_at) VALUES (?, ?, ?)",
				n.PhysicalID, name, now); err != nil {
				return err
			}
		}
		roots, err := queryMirrorRoots(ctx, tx, "WHERE r.physical_id = ?", n.PhysicalID)
		if err != nil {
			return err
		}
		live := 0
		var inside []MirrorRoot
		for _, r := range roots {
			if r.Volume != n.Volume {
				if !r.Stopped() {
					live++
				}
				continue
			}
			if r.Path == n.Path {
				if _, err := tx.ExecContext(ctx, "UPDATE mirror_roots SET paused_at = NULL, stopped_at = NULL, types = coalesce(?, types) WHERE id = ?",
					typesJSON, r.ID); err != nil {
					return err
				}
				res.Root, err = scanMirrorRoot(tx.QueryRowContext(ctx, "SELECT "+mirrorRootCols+mirrorRootFrom+" WHERE r.id = ?", r.ID))
				return err
			}
			if !r.Stopped() && within(r.Path, n.Path) {
				return &CoveredError{RootID: r.ID}
			}
			if within(n.Path, r.Path) {
				inside = append(inside, r)
				continue
			}
			if !r.Stopped() {
				live++
			}
		}
		if n.MaxRoots > 0 && live >= n.MaxRoots {
			return ErrTooManyRoots
		}
		id := NewUUID()
		if typesJSON == nil {
			typesJSON = `["image","video"]`
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO mirror_roots (id, physical_id, volume, path, created_by, created_at, types)
			VALUES (?, ?, ?, ?, ?, ?, ?)`, id, n.PhysicalID, n.Volume, n.Path, n.DeviceID, now, typesJSON); err != nil {
			return err
		}
		for _, r := range inside {
			if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET root_id = ? WHERE root_id = ?", id, r.ID); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "DELETE FROM mirror_roots WHERE id = ?", r.ID); err != nil {
				return err
			}
			res.Absorbed = append(res.Absorbed, r.ID)
		}
		res.Created = true
		res.Root, err = scanMirrorRoot(tx.QueryRowContext(ctx, "SELECT "+mirrorRootCols+mirrorRootFrom+" WHERE r.id = ?", id))
		return err
	})
	return res, err
}

// UpdateMirrorRoot pauses or resumes a root and changes its types (nil: unchanged).
func (s *Store) UpdateMirrorRoot(ctx context.Context, id string, paused *bool, types []string) (MirrorRoot, error) {
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if paused != nil {
			q := "UPDATE mirror_roots SET paused_at = NULL WHERE id = ?"
			args := []any{id}
			if *paused {
				q, args = "UPDATE mirror_roots SET paused_at = coalesce(paused_at, ?) WHERE id = ?", []any{now, id}
			}
			if _, err := tx.ExecContext(ctx, q, args...); err != nil {
				return err
			}
		}
		if types != nil {
			b, _ := json.Marshal(types)
			if _, err := tx.ExecContext(ctx, "UPDATE mirror_roots SET types = ? WHERE id = ?", string(b), id); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return MirrorRoot{}, err
	}
	return s.MirrorRoot(ctx, id)
}

// StopMirrorRoot stops a root: no new entries; its mirror stays.
func (s *Store) StopMirrorRoot(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE mirror_roots SET stopped_at = coalesce(stopped_at, ?) WHERE id = ?", s.nowMs(), id)
	return err
}

// --- entries ---------------------------------------------------------------------------------------------------------

// MirrorEntry is one file of a phone.
type MirrorEntry struct {
	ID            int64
	PhysicalID    string
	Volume        string
	Path          string
	RootID        string
	Fingerprint   string
	SHA256        string
	Size          int64
	ModifiedAt    int64
	Store         string
	Linked        bool
	OnDevice      bool
	RemovedAt     *int64
	RemovedReason string
	VerifiedSHA   string
	VerifiedAt    *int64
	ReportedBy    string
	TreePath      string // where its link is, relative to the device folder ("" none)
	LostAt        *int64
	DirName       string // the device folder (filled by the queries that join it)
}

// OwnsPath reports whether the entry holds its path in the tree (current, deleted or freed).
func (e MirrorEntry) OwnsPath() bool {
	return e.RemovedReason == "" || e.RemovedReason == RemovedDeleted || e.RemovedReason == RemovedFreed
}

const mirrorEntryCols = `e.id, e.physical_id, e.volume, e.path, e.root_id, e.fingerprint, coalesce(e.sha256, ''), e.size, e.modified_at, e.store,
	e.linked, e.on_device, e.removed_at, coalesce(e.removed_reason, ''), coalesce(e.verified_sha256, ''), e.verified_at, e.reported_by,
	coalesce(e.tree_path, ''), e.lost_at, coalesce((SELECT d.dir_name FROM mirror_devices d WHERE d.physical_id = e.physical_id), '')`

func scanMirrorEntry(row interface{ Scan(...any) error }) (MirrorEntry, error) {
	var e MirrorEntry
	err := row.Scan(&e.ID, &e.PhysicalID, &e.Volume, &e.Path, &e.RootID, &e.Fingerprint, &e.SHA256, &e.Size, &e.ModifiedAt, &e.Store,
		&e.Linked, &e.OnDevice, &e.RemovedAt, &e.RemovedReason, &e.VerifiedSHA, &e.VerifiedAt, &e.ReportedBy, &e.TreePath, &e.LostAt, &e.DirName)
	if errors.Is(err, sql.ErrNoRows) {
		return e, ErrNotFound
	}
	return e, err
}

func queryMirrorEntries(ctx context.Context, q rowsQueryer, where string, args ...any) ([]MirrorEntry, error) {
	//nolint:gosec // fixed clauses; values are arguments
	rows, err := q.QueryContext(ctx, "SELECT "+mirrorEntryCols+" FROM mirror_entries e "+where, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MirrorEntry
	for rows.Next() {
		e, err := scanMirrorEntry(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

const ownsPathSQL = "(e.removed_reason IS NULL OR e.removed_reason IN ('deleted', 'freed'))"

// MirrorEntryAt returns the entry that owns a phone path (current, deleted or freed).
func (s *Store) MirrorEntryAt(ctx context.Context, physicalID, volume, path string) (MirrorEntry, error) {
	return scanMirrorEntry(s.db.QueryRowContext(ctx, "SELECT "+mirrorEntryCols+" FROM mirror_entries e WHERE e.physical_id = ? AND e.volume = ? AND e.path = ? AND "+
		ownsPathSQL, physicalID, volume, path))
}

// MirrorEntries returns entries by id.
func (s *Store) MirrorEntries(ctx context.Context, ids []int64) ([]MirrorEntry, error) {
	var out []MirrorEntry
	for start := 0; start < len(ids); start += 500 {
		chunk := ids[start:min(start+500, len(ids))]
		args := make([]any, len(chunk))
		for i, id := range chunk {
			args[i] = id
		}
		es, err := queryMirrorEntries(ctx, s.db, "WHERE e.id IN ("+placeholders(len(chunk))+") ORDER BY e.id", args...)
		if err != nil {
			return nil, err
		}
		out = append(out, es...)
	}
	return out, nil
}

// MirrorEntriesOfRoot lists every entry of a root (the manifest, the admin page), by path.
func (s *Store) MirrorEntriesOfRoot(ctx context.Context, rootID string) ([]MirrorEntry, error) {
	return queryMirrorEntries(ctx, s.db, "WHERE e.root_id = ? ORDER BY e.volume, e.path, e.id", rootID)
}

// MirrorEntryIDsOfPhysical lists the ids of a phone's entries.
func (s *Store) MirrorEntryIDsOfPhysical(ctx context.Context, physicalID string) ([]int64, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT id FROM mirror_entries WHERE physical_id = ? ORDER BY id", physicalID)
	if err != nil {
		return nil, err
	}
	return scanIDs(rows)
}

// MirrorUnlinked lists a root's entries whose bytes are stored but that have no link yet (retried on each report).
func (s *Store) MirrorUnlinked(ctx context.Context, rootID string) ([]int64, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id FROM mirror_entries WHERE root_id = ? AND store = 'blob' AND linked = 0
		AND (removed_reason IS NULL OR removed_reason != 'moved') ORDER BY id`, rootID)
	if err != nil {
		return nil, err
	}
	return scanIDs(rows)
}

func scanIDs(rows *sql.Rows) ([]int64, error) {
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// SetMirrorLink records where an entry's link is ("" = none) and whether it is linked.
func (s *Store) SetMirrorLink(ctx context.Context, id int64, treePath string, linked bool) error {
	_, err := s.db.ExecContext(ctx, "UPDATE mirror_entries SET tree_path = ?, linked = ? WHERE id = ?", nullString(treePath), linked, id)
	return err
}

// ReportEntry is one file a phone reports.
type ReportEntry struct {
	Path        string // relative to the volume
	Fingerprint string
	SHA256      string // "" = not sent
	Size        int64
	ModifiedAt  int64
}

// ReportRemoval is one file that left the phone.
type ReportRemoval struct {
	Path   string
	Reason string // deleted | moved | freed
}

// ReportResult is the answer for one reported file.
type ReportResult struct {
	Index       int
	Path        string
	State       string
	EntryID     int64
	Fingerprint string
	Verified    string // yes | no (pending is the caller's: the verify queue)
	SHA256      string
}

// RemovalResult is the answer for one removal: kept, moved or unknown.
type RemovalResult struct {
	Index int
	Path  string
	State string
}

// ReportOutcome is what a report changed.
type ReportOutcome struct {
	Results []ReportResult
	Removed []RemovalResult
	Touched []int64 // entries whose tree state may have changed
	// NasOnly lists entries whose only copy became a NAS file (on_device = 0 and store nas): copy them into the store
	// ("Keep a copy of NAS files a phone no longer has").
	NasOnly []int64
	// Compressed lists the blob compressions whose swap the report completed (ADR 0025 "Order does not matter").
	Compressed []CompressItem
}

// ReportOptions tunes a report.
type ReportOptions struct {
	DeviceID string
	// UploadLiveSince (ms): another device's unfinished upload touched since then is "in_progress".
	UploadLiveSince int64
	// CompressTrashMs: how long a compression's trashed side is kept (ADR 0025), for a swap the report completes.
	CompressTrashMs int64
}

// resolveContent finds where the server has a file: uploaded bytes (by SHA-256 when sent, else by fingerprint), an
// indexed NAS file, or nothing (missing, in_progress, or differs when the server has the fingerprint with another
// SHA-256 than the phone's).
func resolveContent(ctx context.Context, tx *sql.Tx, fp, sha string, opt ReportOptions) (store, storeSHA, state string, err error) {
	if sha != "" {
		var n int
		if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM blobs WHERE sha256 = ? AND kind = 'original'", sha).Scan(&n); err != nil {
			return "", "", "", err
		}
		if n > 0 {
			return StoreBlob, sha, StateStored, nil
		}
	}
	differs := false
	var osha string
	err = tx.QueryRowContext(ctx, "SELECT sha256 FROM originals WHERE fingerprint = ?", fp).Scan(&osha)
	switch {
	case err == nil && (sha == "" || osha == sha):
		return StoreBlob, osha, StateStored, nil
	case err == nil:
		differs = true
	case !errors.Is(err, sql.ErrNoRows):
		return "", "", "", err
	}
	var nasSHA sql.NullString
	var shaMtime sql.NullInt64
	var mtime int64
	err = tx.QueryRowContext(ctx, `SELECT f.sha256, f.sha_mtime, f.mtime FROM nas_files f JOIN nas_folders d ON d.id = f.folder_id
		WHERE f.fingerprint = ? AND f.removed = 0 AND d.enabled = 1 ORDER BY f.origin_path IS NOT NULL, f.id LIMIT 1`, fp).Scan(&nasSHA, &shaMtime, &mtime)
	switch {
	case err == nil:
		if sha != "" && nasSHA.Valid && shaMtime.Valid && shaMtime.Int64 == mtime && nasSHA.String != sha {
			differs = true
		} else if !differs {
			return StoreNAS, "", StateNAS, nil
		}
	case !errors.Is(err, sql.ErrNoRows):
		return "", "", "", err
	}
	if differs {
		return StoreNone, "", StateDiffers, nil
	}
	var one int
	err = tx.QueryRowContext(ctx, `SELECT 1 FROM uploads WHERE device_id != ? AND kind = 'original' AND updated_at >= ?
		AND (sha256 = ? OR fingerprint = ?) LIMIT 1`, opt.DeviceID, opt.UploadLiveSince, sha, fp).Scan(&one)
	switch {
	case err == nil:
		return StoreNone, "", StateInProgress, nil
	case !errors.Is(err, sql.ErrNoRows):
		return "", "", "", err
	}
	return StoreNone, "", StateMissing, nil
}

func ownerAt(ctx context.Context, tx *sql.Tx, physical, volume, path string) (MirrorEntry, error) {
	return scanMirrorEntry(tx.QueryRowContext(ctx, "SELECT "+mirrorEntryCols+" FROM mirror_entries e WHERE e.physical_id = ? AND e.volume = ? AND e.path = ? AND "+
		ownsPathSQL, physical, volume, path))
}

// ApplyReport applies a phone's report to one root in one transaction (ADR 0024 "Entry rules"): new files, restores,
// rewrites (the old content becomes "replaced"), and then removals (deleted, moved, freed; a removal whose content is
// at another current path of the phone is a move). Nothing is ever dropped. The caller validated the paths.
func (s *Store) ApplyReport(ctx context.Context, root MirrorRoot, entries []ReportEntry, removed []ReportRemoval, opt ReportOptions) (ReportOutcome, error) {
	var out ReportOutcome
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		out = ReportOutcome{}
		touched := map[int64]bool{}
		touch := func(id int64) {
			if !touched[id] {
				touched[id] = true
				out.Touched = append(out.Touched, id)
			}
		}
		for i, e := range entries {
			owner, err := ownerAt(ctx, tx, root.PhysicalID, root.Volume, e.Path)
			if err != nil && !errors.Is(err, ErrNotFound) {
				return err
			}
			had := err == nil
			var cur MirrorEntry
			if had && owner.Fingerprint == e.Fingerprint && owner.Size == e.Size && (e.SHA256 == "" || owner.SHA256 == "" || owner.SHA256 == e.SHA256) {
				// The same file (again, or restored to the phone): a current entry, no upload.
				if _, err := tx.ExecContext(ctx, `UPDATE mirror_entries SET root_id = ?, modified_at = ?, sha256 = coalesce(sha256, ?), on_device = 1,
					removed_at = NULL, removed_reason = NULL, pinned = 0, reported_by = ?, updated_at = ? WHERE id = ?`,
					root.ID, e.ModifiedAt, nullString(e.SHA256), opt.DeviceID, now, owner.ID); err != nil {
					return err
				}
				cur = owner
				if cur.SHA256 == "" {
					cur.SHA256 = e.SHA256
				}
			} else if done, err := compressRewrite(ctx, tx, had, owner, e, opt, now, &out, touch); err != nil {
				return err
			} else if done != nil {
				cur = *done
			} else {
				if had { // rewritten in place: the old content is kept as a previous version
					if _, err := tx.ExecContext(ctx, `UPDATE mirror_entries SET removed_reason = 'replaced', removed_at = ?, on_device = 0, updated_at = ?
						WHERE id = ?`, now, now, owner.ID); err != nil {
						return err
					}
					touch(owner.ID)
				}
				var id int64
				if err := tx.QueryRowContext(ctx, `INSERT INTO mirror_entries (physical_id, volume, path, root_id, fingerprint, sha256, size, modified_at,
					store, reported_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'none', ?, ?, ?) RETURNING id`,
					root.PhysicalID, root.Volume, e.Path, root.ID, e.Fingerprint, nullString(e.SHA256), e.Size, e.ModifiedAt, opt.DeviceID, now, now).Scan(&id); err != nil {
					return err
				}
				cur = MirrorEntry{ID: id, Fingerprint: e.Fingerprint, SHA256: e.SHA256, Store: StoreNone}
				// Inferred move: the same content deleted from another path of this phone moved here.
				rows, err := tx.QueryContext(ctx, `UPDATE mirror_entries SET removed_reason = 'moved', updated_at = ? WHERE physical_id = ?
					AND fingerprint = ? AND removed_reason = 'deleted' AND NOT (volume = ? AND path = ?) RETURNING id`,
					now, root.PhysicalID, e.Fingerprint, root.Volume, e.Path)
				if err != nil {
					return err
				}
				ids, err := scanIDs(rows)
				if err != nil {
					return err
				}
				for _, id := range ids {
					touch(id)
				}
			}
			state := StateStored
			if cur.Store != StoreBlob {
				st, sha, stt, err := resolveContent(ctx, tx, e.Fingerprint, cur.SHA256, opt)
				if err != nil {
					return err
				}
				state = stt
				if st != cur.Store || sha != "" && cur.SHA256 == "" {
					if _, err := tx.ExecContext(ctx, `UPDATE mirror_entries SET store = ?, sha256 = coalesce(sha256, ?),
						lost_at = CASE WHEN ? != 'none' THEN NULL ELSE lost_at END, updated_at = ? WHERE id = ?`,
						st, nullString(sha), st, now, cur.ID); err != nil {
						return err
					}
					if cur.SHA256 == "" {
						cur.SHA256 = sha
					}
				}
			}
			touch(cur.ID)
			r := ReportResult{Index: i, Path: e.Path, State: state, EntryID: cur.ID, Fingerprint: e.Fingerprint, Verified: "no", SHA256: cur.SHA256}
			var vsha sql.NullString
			if err := tx.QueryRowContext(ctx, "SELECT verified_sha256 FROM mirror_entries WHERE id = ?", cur.ID).Scan(&vsha); err != nil {
				return err
			}
			if e.SHA256 != "" && vsha.Valid && vsha.String == e.SHA256 {
				r.Verified = "yes"
			}
			out.Results = append(out.Results, r)
		}
		for i, rm := range removed {
			owner, err := ownerAt(ctx, tx, root.PhysicalID, root.Volume, rm.Path)
			if errors.Is(err, ErrNotFound) {
				out.Removed = append(out.Removed, RemovalResult{Index: i, Path: rm.Path, State: "unknown"})
				continue
			}
			if err != nil {
				return err
			}
			state := "kept"
			reason := rm.Reason
			if reason != RemovedFreed {
				if owner.RemovedReason == RemovedFreed {
					out.Removed = append(out.Removed, RemovalResult{Index: i, Path: rm.Path, State: state}) // stays pinned
					continue
				}
				var one int
				err := tx.QueryRowContext(ctx, `SELECT 1 FROM mirror_entries WHERE physical_id = ? AND fingerprint = ? AND removed_reason IS NULL
					AND id != ? LIMIT 1`, root.PhysicalID, owner.Fingerprint, owner.ID).Scan(&one)
				switch {
				case err == nil:
					reason, state = RemovedMoved, "moved"
				case !errors.Is(err, sql.ErrNoRows):
					return err
				}
			}
			if _, err := tx.ExecContext(ctx, `UPDATE mirror_entries SET on_device = 0, removed_reason = ?, removed_at = coalesce(removed_at, ?),
				pinned = CASE WHEN ? = 'freed' THEN 1 ELSE pinned END, reported_by = ?, updated_at = ? WHERE id = ?`, reason, now, reason,
				opt.DeviceID, now, owner.ID); err != nil {
				return err
			}
			touch(owner.ID)
			if owner.Store == StoreNAS && (reason == RemovedDeleted || reason == RemovedFreed) {
				out.NasOnly = append(out.NasOnly, owner.ID)
			}
			out.Removed = append(out.Removed, RemovalResult{Index: i, Path: rm.Path, State: state})
		}
		_, err := tx.ExecContext(ctx, "UPDATE mirror_roots SET last_report_at = ? WHERE id = ?", now, root.ID)
		return err
	})
	return out, err
}

// SettleMoves turns "moved" removals older than before (ms) whose content never showed up at another current path of
// the phone back into "deleted" (kept; ADR 0024: a move is a removal plus a new path within 24 hours), or "replaced"
// when another entry took the path meanwhile. It returns the entries changed.
func (s *Store) SettleMoves(ctx context.Context, before int64) ([]int64, error) {
	var changed []int64
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		changed = nil
		pending, err := queryMirrorEntries(ctx, tx, `WHERE e.removed_reason = 'moved' AND e.removed_at < ? AND NOT EXISTS (SELECT 1 FROM mirror_entries o
			WHERE o.physical_id = e.physical_id AND o.fingerprint = e.fingerprint AND o.removed_reason IS NULL)`, before)
		if err != nil {
			return err
		}
		for _, e := range pending {
			reason := RemovedDeleted
			if _, err := ownerAt(ctx, tx, e.PhysicalID, e.Volume, e.Path); err == nil {
				reason = RemovedReplaced
			} else if !errors.Is(err, ErrNotFound) {
				return err
			}
			if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET removed_reason = ?, updated_at = ? WHERE id = ?", reason, now, e.ID); err != nil {
				return err
			}
			changed = append(changed, e.ID)
		}
		return nil
	})
	return changed, err
}

// LinkWaiting turns the entries waiting for (or referencing on the NAS) the content of an original that is now in the
// store into stored entries ("Content in both places prefers blob"): by fingerprint, and by SHA-256 when the entry has
// one. It returns them (the caller links them).
func (s *Store) LinkWaiting(ctx context.Context, fp, sha string) ([]int64, error) {
	rows, err := s.db.QueryContext(ctx, `UPDATE mirror_entries SET store = 'blob', sha256 = coalesce(sha256, ?), lost_at = NULL, updated_at = ?
		WHERE fingerprint = ? AND store IN ('none', 'nas') AND (sha256 IS NULL OR sha256 = ?) RETURNING id`, sha, s.nowMs(), fp, sha)
	if err != nil {
		return nil, err
	}
	return scanIDs(rows)
}

// RootStats counts a root's files: every path it holds (current, deleted or freed).
type RootStats struct {
	Files             int   `json:"files"`
	Bytes             int64 `json:"bytes"`
	Stored            int   `json:"stored"`
	NAS               int   `json:"nas"`
	Waiting           int   `json:"waiting"`
	RemovedFromDevice int   `json:"removedFromDevice"`
	Freed             int   `json:"freed"`
	Verified          int   `json:"verified"`
}

// MirrorRootStats returns the statistics of every root.
func (s *Store) MirrorRootStats(ctx context.Context) (map[string]RootStats, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT root_id, count(*), coalesce(sum(size), 0), coalesce(sum(store = 'blob'), 0),
		coalesce(sum(store = 'nas'), 0), coalesce(sum(store = 'none'), 0), coalesce(sum(on_device = 0), 0),
		coalesce(sum(removed_reason = 'freed'), 0), coalesce(sum(verified_sha256 IS NOT NULL AND verified_sha256 = sha256), 0)
		FROM mirror_entries e WHERE `+ownsPathSQL+` GROUP BY root_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]RootStats{}
	for rows.Next() {
		var id string
		var st RootStats
		if err := rows.Scan(&id, &st.Files, &st.Bytes, &st.Stored, &st.NAS, &st.Waiting, &st.RemovedFromDevice, &st.Freed, &st.Verified); err != nil {
			return nil, err
		}
		out[id] = st
	}
	return out, rows.Err()
}

// ManifestEntry is a manifest row's data: an entry and, for a NAS reference, where the NAS file is.
type ManifestEntry struct {
	MirrorEntry
	NasFolder string // the folder's name
	NasPath   string
}

// MirrorManifest lists a root's entries for its manifest (NAS references with their folder name and path).
func (s *Store) MirrorManifest(ctx context.Context, rootID string) ([]ManifestEntry, error) {
	es, err := s.MirrorEntriesOfRoot(ctx, rootID)
	if err != nil {
		return nil, err
	}
	out := make([]ManifestEntry, 0, len(es))
	for _, e := range es {
		m := ManifestEntry{MirrorEntry: e}
		if e.Store == StoreNAS {
			err := s.db.QueryRowContext(ctx, `SELECT d.name, f.rel_path FROM nas_files f JOIN nas_folders d ON d.id = f.folder_id
				WHERE f.fingerprint = ? AND f.removed = 0 AND d.enabled = 1 ORDER BY f.origin_path IS NOT NULL, f.id LIMIT 1`, e.Fingerprint).Scan(&m.NasFolder, &m.NasPath)
			if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return nil, err
			}
		}
		out = append(out, m)
	}
	return out, nil
}

// --- mirror items (ADR 0014 amendment) -------------------------------------------------------------------------------

// MirrorMember is an entry that is a member of a mirror item: its bytes are stored or on the NAS, and it holds a path
// (current, deleted, freed) or is a previous version (replaced). Moved entries are not.
type MirrorMember struct {
	MirrorEntry
	RootPath string
	// A NAS reference's live NAS file, for the location next to the mirror one ("" when none).
	NasFolderID string
	NasPath     string
	NasMTime    int64 // ns
}

// MirrorMembers lists every member entry, by root. Entries a compression keeps (ADR 0025, "compressed") are not
// members: they are the "kept" sources (CompressedMembers).
func (s *Store) MirrorMembers(ctx context.Context) (map[string][]MirrorMember, error) {
	return mirrorMembers(ctx, s.db, "e.store IN ('blob', 'nas') AND (e.removed_reason IS NULL OR e.removed_reason NOT IN ('moved', 'compressed'))")
}

func mirrorMembers(ctx context.Context, q rowsQueryer, where string) (map[string][]MirrorMember, error) {
	//nolint:gosec // fixed clauses only
	rows, err := q.QueryContext(ctx, `SELECT `+mirrorEntryCols+`, r.path, coalesce(f.folder_id, ''), coalesce(f.rel_path, ''), coalesce(f.mtime, 0)
		FROM mirror_entries e JOIN mirror_roots r ON r.id = e.root_id
		LEFT JOIN nas_files f ON e.store = 'nas' AND f.id = (SELECT f2.id FROM nas_files f2 JOIN nas_folders d2 ON d2.id = f2.folder_id
			WHERE f2.fingerprint = e.fingerprint AND f2.removed = 0 AND f2.origin_path IS NULL AND d2.enabled = 1 ORDER BY f2.id LIMIT 1)
		WHERE `+where+`
		ORDER BY e.root_id, e.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][]MirrorMember{}
	for rows.Next() {
		var m MirrorMember
		e := &m.MirrorEntry
		if err := rows.Scan(&e.ID, &e.PhysicalID, &e.Volume, &e.Path, &e.RootID, &e.Fingerprint, &e.SHA256, &e.Size, &e.ModifiedAt, &e.Store,
			&e.Linked, &e.OnDevice, &e.RemovedAt, &e.RemovedReason, &e.VerifiedSHA, &e.VerifiedAt, &e.ReportedBy, &e.TreePath, &e.LostAt, &e.DirName,
			&m.RootPath, &m.NasFolderID, &m.NasPath, &m.NasMTime); err != nil {
			return nil, err
		}
		out[e.RootID] = append(out[e.RootID], m)
	}
	return out, rows.Err()
}

// IsMirrorRoot reports whether id is a mirror root (GET /v1/items?folder=<rootId>).
func (s *Store) IsMirrorRoot(ctx context.Context, id string) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM mirror_roots WHERE id = ?", id).Scan(&n)
	return n > 0, err
}

// --- NAS references and the trash (ADR 0024 "With ADR 0023's trash") -------------------------------------------------

// NasCheck is what a pass over the NAS references found.
type NasCheck struct {
	Touched []int64 // entries whose store changed
	CopyOut []int64 // NAS references whose phone file is gone: copy them into the store
	Lost    int     // entries that just lost their only copy
}

// CheckNasReferences re-resolves every NAS reference (and every entry that lost its only copy) after a walk: content
// now uploaded becomes "blob"; a NAS file still indexed (in a trash too, until purged) stays "nas", and when the phone
// no longer has the file it is listed for a copy into the store; a NAS file that is gone turns the entry "none" (the
// phone uploads it while it has it; otherwise the backup lost its only copy: lost_at, a warning on the admin page).
func (s *Store) CheckNasReferences(ctx context.Context) (NasCheck, error) {
	var out NasCheck
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		out = NasCheck{}
		es, err := queryMirrorEntries(ctx, tx, "WHERE e.store = 'nas' OR (e.store = 'none' AND e.lost_at IS NOT NULL) ORDER BY e.id")
		if err != nil {
			return err
		}
		for _, e := range es {
			var osha string
			err := tx.QueryRowContext(ctx, "SELECT sha256 FROM originals WHERE fingerprint = ?", e.Fingerprint).Scan(&osha)
			if err == nil && (e.SHA256 == "" || e.SHA256 == osha) {
				if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET store = 'blob', sha256 = coalesce(sha256, ?), lost_at = NULL, updated_at = ? WHERE id = ?",
					osha, now, e.ID); err != nil {
					return err
				}
				out.Touched = append(out.Touched, e.ID)
				continue
			}
			if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			var one int
			err = tx.QueryRowContext(ctx, `SELECT 1 FROM nas_files f JOIN nas_folders d ON d.id = f.folder_id WHERE f.fingerprint = ? AND f.removed = 0
				AND d.enabled = 1 LIMIT 1`, e.Fingerprint).Scan(&one)
			switch {
			case err == nil:
				if e.Store != StoreNAS {
					if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET store = 'nas', lost_at = NULL, updated_at = ? WHERE id = ?", now, e.ID); err != nil {
						return err
					}
					out.Touched = append(out.Touched, e.ID)
				}
				if !e.OnDevice && e.RemovedReason != RemovedMoved {
					out.CopyOut = append(out.CopyOut, e.ID)
				}
			case errors.Is(err, sql.ErrNoRows):
				if e.Store == StoreNone {
					continue // still lost
				}
				var lost any
				if !e.OnDevice && e.RemovedReason != RemovedMoved {
					lost = now
					out.Lost++
				}
				if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET store = 'none', lost_at = ?, updated_at = ? WHERE id = ?", lost, now, e.ID); err != nil {
					return err
				}
				out.Touched = append(out.Touched, e.ID)
			default:
				return err
			}
		}
		return nil
	})
	return out, err
}

// LostBackups counts the entries whose only copy, a NAS file, is gone (the admin page's warning).
func (s *Store) LostBackups(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM mirror_entries WHERE lost_at IS NOT NULL AND store = 'none'").Scan(&n)
	return n, err
}

// --- verification (POST /v1/backup/verify) ---------------------------------------------------------------------------

// BlobVerification is the server's last re-hash of a stored blob.
func (s *Store) BlobVerification(ctx context.Context, sha string) (result string, at int64, ok bool, err error) {
	err = s.db.QueryRowContext(ctx, "SELECT result, verified_at FROM blob_verifications WHERE sha256 = ?", sha).Scan(&result, &at)
	if errors.Is(err, sql.ErrNoRows) {
		return "", 0, false, nil
	}
	return result, at, err == nil, err
}

// SetBlobVerification stores a blob's re-hash (result "" = unreadable) and marks the entries stored there.
func (s *Store) SetBlobVerification(ctx context.Context, sha, result string) error {
	now := s.nowMs()
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO blob_verifications (sha256, result, verified_at) VALUES (?, ?, ?)
			ON CONFLICT (sha256) DO UPDATE SET result = excluded.result, verified_at = excluded.verified_at`, sha, result, now); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET verified_sha256 = ?, verified_at = ? WHERE sha256 = ? AND store = 'blob'",
			nullString(result), now, sha)
		return err
	})
}

// NasForVerify is an indexed NAS file of a fingerprint with its last full hash.
type NasForVerify struct {
	Original
	FileID   int64
	SHA256   string
	SHAMTime int64
}

// NasFileForVerify finds the NAS file a reference reads (live copies first).
func (s *Store) NasFileForVerify(ctx context.Context, fp string) (NasForVerify, error) {
	n := NasForVerify{Original: Original{Fingerprint: fp, Source: "nas"}}
	var sha sql.NullString
	var shaMtime sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT f.id, f.size, f.folder_id, d.path, f.rel_path, f.mtime, f.sha256, f.sha_mtime FROM nas_files f
		JOIN nas_folders d ON d.id = f.folder_id WHERE f.fingerprint = ? AND f.removed = 0 AND d.enabled = 1
		ORDER BY f.origin_path IS NOT NULL, f.id LIMIT 1`, fp).Scan(&n.FileID, &n.Size, &n.FolderID, &n.FolderPath, &n.RelPath, &n.ModTime, &sha, &shaMtime)
	if errors.Is(err, sql.ErrNoRows) {
		return n, ErrNotFound
	}
	n.SHA256, n.SHAMTime = sha.String, shaMtime.Int64
	return n, err
}

// SetNasSHA stores a NAS file's full hash for the mtime it was made for and marks the references to it verified.
func (s *Store) SetNasSHA(ctx context.Context, fileID, mtime int64, fp, sha string) error {
	now := s.nowMs()
	return s.tx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "UPDATE nas_files SET sha256 = ?, sha_mtime = ? WHERE id = ?", sha, mtime, fileID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET verified_sha256 = ?, verified_at = ? WHERE fingerprint = ? AND store = 'nas'", sha, now, fp)
		return err
	})
}

// SetEntryVerified records a verification result on one entry (a NAS copy whose bytes differ).
func (s *Store) SetEntryVerified(ctx context.Context, id int64, sha string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE mirror_entries SET verified_sha256 = ?, verified_at = ? WHERE id = ?", sha, s.nowMs(), id)
	return err
}

var fpInText = regexp.MustCompile(`"(v1:[0-9a-f]{64})"`)

// NamedFingerprints returns the fingerprints a live Reveal record names (ADR 0024 "namedByRecord"): any fingerprint
// in a live photo record (its file or any file of the shot, as originalNamed) and the photoFingerprint of a live
// version record. One pass over the records.
func (s *Store) NamedFingerprints(ctx context.Context) (map[string]bool, error) {
	out := map[string]bool{}
	rows, err := s.db.QueryContext(ctx, `SELECT kind, data FROM records WHERE kind IN ('photo', 'version') AND deleted = 0 AND data IS NOT NULL`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var kind, data string
		if err := rows.Scan(&kind, &data); err != nil {
			return nil, err
		}
		if kind == "photo" {
			for _, m := range fpInText.FindAllStringSubmatch(data, -1) {
				out[m[1]] = true
			}
			continue
		}
		var v struct {
			PhotoFingerprint string `json:"photoFingerprint"`
		}
		if json.Unmarshal([]byte(data), &v) == nil && v.PhotoFingerprint != "" {
			out[v.PhotoFingerprint] = true
		}
	}
	return out, rows.Err()
}

// --- ownership: claims, uploads, deletes, purges ---------------------------------------------------------------------

// AddClaim records that a device asked the server to keep an original.
func (s *Store) AddClaim(ctx context.Context, fp, deviceID string) error {
	_, err := s.db.ExecContext(ctx, "INSERT INTO original_claims (fingerprint, device_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
		fp, deviceID, s.nowMs())
	return err
}

// Claims lists the devices that claim an original.
func (s *Store) Claims(ctx context.Context, fp string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT device_id FROM original_claims WHERE fingerprint = ? ORDER BY device_id", fp)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var d string
		if err := rows.Scan(&d); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// SetUploadUnclaimed marks an upload as the mirror's own ("claim": false): no claim when it completes.
func (s *Store) SetUploadUnclaimed(ctx context.Context, uploadID string, unclaimed bool) error {
	q := "DELETE FROM upload_unclaimed WHERE upload_id = ?"
	if unclaimed {
		q = "INSERT INTO upload_unclaimed (upload_id) VALUES (?) ON CONFLICT DO NOTHING"
	}
	_, err := s.db.ExecContext(ctx, q, uploadID)
	return err
}

// UploadUnclaimed reports whether an upload was made with "claim": false.
func (s *Store) UploadUnclaimed(ctx context.Context, uploadID string) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM upload_unclaimed WHERE upload_id = ?", uploadID).Scan(&n)
	return n > 0, err
}

// ActiveUploadByOther reports whether another device has an unfinished upload of sha touched since (ms).
func (s *Store) ActiveUploadByOther(ctx context.Context, deviceID, sha string, since int64) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM uploads WHERE sha256 = ? AND device_id != ? AND updated_at >= ?", sha, deviceID, since).Scan(&n)
	return n > 0, err
}

// mirrorNames reports whether a mirror entry (any state) names the content (fingerprint or SHA-256).
func mirrorNames(ctx context.Context, q queryer, fp, sha string) (bool, error) {
	var one int
	err := q.QueryRowContext(ctx, "SELECT 1 FROM mirror_entries WHERE fingerprint = ? OR (? != '' AND sha256 = ?) LIMIT 1", fp, sha, sha).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// MirrorNames reports whether a mirror entry names a fingerprint.
func (s *Store) MirrorNames(ctx context.Context, fp string) (bool, error) {
	return mirrorNames(ctx, s.db, fp, "")
}

// contentKept reports whether anything still names an original's content (ADR 0024 "Ownership"): a mirror entry, a
// device claim, a live photo record, or a blob compression that is not purged (ADR 0025, rule 5).
func contentKept(ctx context.Context, tx *sql.Tx, fp, sha string) (bool, error) {
	if named, err := mirrorNames(ctx, tx, fp, sha); err != nil || named {
		return named, err
	}
	if held, err := compressHolds(ctx, tx, fp, sha); err != nil || held {
		return held, err
	}
	var one int
	err := tx.QueryRowContext(ctx, "SELECT 1 FROM original_claims WHERE fingerprint = ? LIMIT 1", fp).Scan(&one)
	if err == nil {
		return true, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return false, err
	}
	return originalNamed(ctx, tx, fp)
}

// PurgeResult is what a purge removed.
type PurgeResult struct {
	DirName string   // the phone's folder (the caller removes it)
	Removed []string // blobs no longer named by anything (the caller removes their files)
	Entries int
	Pinned  int // pinned entries among them: freed on the phone, the only copies
	// Links are the hard links the server made in the phone's folder (the caller removes exactly these), Roots the
	// roots whose manifests go.
	Links []MirrorEntry
	Roots []string
}

// MirrorPurgeInfo counts a phone's entries and its pinned (freed) ones, for the purge warning.
func (s *Store) MirrorPurgeInfo(ctx context.Context, physicalID string) (entries, pinned int, err error) {
	err = s.db.QueryRowContext(ctx, "SELECT count(*), coalesce(sum(pinned), 0) FROM mirror_entries WHERE physical_id = ?", physicalID).
		Scan(&entries, &pinned)
	return
}

// PurgeMirror deletes a phone's backup (the admin page only): its entries, roots and folder row, then every original
// whose content nothing names any more (another phone's entry, a claim, a live photo record keep it), in one
// transaction. The caller removes the folder and the released blobs' files.
func (s *Store) PurgeMirror(ctx context.Context, physicalID string) (PurgeResult, error) {
	var out PurgeResult
	err := s.tx(ctx, func(tx *sql.Tx) error {
		out = PurgeResult{}
		err := tx.QueryRowContext(ctx, "SELECT dir_name FROM mirror_devices WHERE physical_id = ?", physicalID).Scan(&out.DirName)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		type content struct{ fp, sha string }
		rows, err := tx.QueryContext(ctx, "SELECT DISTINCT fingerprint, coalesce(sha256, '') FROM mirror_entries WHERE physical_id = ?", physicalID)
		if err != nil {
			return err
		}
		var cs []content
		for rows.Next() {
			var c content
			if err := rows.Scan(&c.fp, &c.sha); err != nil {
				rows.Close()
				return err
			}
			cs = append(cs, c)
		}
		rows.Close()
		if err := tx.QueryRowContext(ctx, "SELECT count(*), coalesce(sum(pinned), 0) FROM mirror_entries WHERE physical_id = ?",
			physicalID).Scan(&out.Entries, &out.Pinned); err != nil {
			return err
		}
		if out.Links, err = queryMirrorEntries(ctx, tx, "WHERE e.physical_id = ? AND e.tree_path IS NOT NULL ORDER BY e.id", physicalID); err != nil {
			return err
		}
		rs, err := queryMirrorRoots(ctx, tx, "WHERE r.physical_id = ?", physicalID)
		if err != nil {
			return err
		}
		for _, r := range rs {
			out.Roots = append(out.Roots, r.ID)
		}
		for _, q := range []string{
			"DELETE FROM mirror_entries WHERE physical_id = ?",
			"DELETE FROM mirror_roots WHERE physical_id = ?",
			"DELETE FROM mirror_devices WHERE physical_id = ?",
			"DELETE FROM physical_devices WHERE id = ? AND NOT EXISTS (SELECT 1 FROM devices WHERE physical_id = physical_devices.id)",
		} {
			if _, err := tx.ExecContext(ctx, q, physicalID); err != nil {
				return err
			}
		}
		for _, c := range cs {
			shas := []string{}
			if c.sha != "" {
				shas = append(shas, c.sha)
			}
			var osha string
			err := tx.QueryRowContext(ctx, "SELECT sha256 FROM originals WHERE fingerprint = ?", c.fp).Scan(&osha)
			if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			if osha != "" && !slices.Contains(shas, osha) {
				shas = append(shas, osha)
			}
			for _, sha := range shas {
				var fps []string
				r2, err := tx.QueryContext(ctx, "SELECT fingerprint FROM originals WHERE sha256 = ?", sha)
				if err != nil {
					return err
				}
				for r2.Next() {
					var f string
					if err := r2.Scan(&f); err != nil {
						r2.Close()
						return err
					}
					fps = append(fps, f)
				}
				r2.Close()
				if len(fps) == 0 {
					fps = []string{c.fp}
				}
				kept := false
				for _, f := range fps {
					k, err := contentKept(ctx, tx, f, sha)
					if err != nil {
						return err
					}
					kept = kept || k
				}
				if kept {
					continue
				}
				var kind string
				err = tx.QueryRowContext(ctx, "SELECT kind FROM blobs WHERE sha256 = ?", sha).Scan(&kind)
				if errors.Is(err, sql.ErrNoRows) || kind != BlobOriginal {
					continue
				}
				if err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM originals WHERE sha256 = ?", sha); err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM blobs WHERE sha256 = ?", sha); err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM blob_verifications WHERE sha256 = ?", sha); err != nil {
					return err
				}
				out.Removed = append(out.Removed, sha)
			}
		}
		return nil
	})
	return out, err
}

// MirrorUsage is a phone's storage (ADR 0024 "Ownership and storage accounting"): each content counted once.
type MirrorUsage struct {
	PhysicalID   string `json:"physicalDeviceId"`
	OwnBlobs     int    `json:"ownFiles"`    // blob contents only this phone's entries name
	OwnBytes     int64  `json:"ownBytes"`    // "uploaded from this phone"
	SharedBlobs  int    `json:"sharedFiles"` // blob contents also named by another phone, a claim or a record
	SharedBytes  int64  `json:"sharedBytes"`
	NasFiles     int    `json:"nasFiles"` // NAS references
	NasBytes     int64  `json:"nasBytes"`
	Waiting      int    `json:"waiting"`
	Pinned       int    `json:"pinned"` // entries freed on the phone (pinned): the only copies
	Entries      int    `json:"entries"`
	Lost         int    `json:"lost"`
	Previous     int    `json:"previous"` // rewritten files' previous versions
	DeletedFiles int    `json:"deletedFromPhone"`
}

// MirrorUsageAll returns every phone's storage accounting.
func (s *Store) MirrorUsageAll(ctx context.Context) (map[string]MirrorUsage, error) {
	out := map[string]MirrorUsage{}
	rows, err := s.db.QueryContext(ctx, `SELECT physical_id, count(*), coalesce(sum(pinned), 0), coalesce(sum(store = 'none'), 0),
		coalesce(sum(lost_at IS NOT NULL AND store = 'none'), 0), coalesce(sum(removed_reason = 'replaced'), 0),
		coalesce(sum(removed_reason = 'deleted'), 0) FROM mirror_entries GROUP BY physical_id`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var u MirrorUsage
		if err := rows.Scan(&u.PhysicalID, &u.Entries, &u.Pinned, &u.Waiting, &u.Lost, &u.Previous, &u.DeletedFiles); err != nil {
			rows.Close()
			return nil, err
		}
		out[u.PhysicalID] = u
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// Blob contents per phone: shared when another phone's entry, a claim or a live photo record names them.
	rows, err = s.db.QueryContext(ctx, `SELECT c.physical_id, c.sha256, b.size,
		EXISTS (SELECT 1 FROM mirror_entries o WHERE o.sha256 = c.sha256 AND o.physical_id != c.physical_id)
		OR EXISTS (SELECT 1 FROM originals x JOIN original_claims k ON k.fingerprint = x.fingerprint WHERE x.sha256 = c.sha256),
		(SELECT x.fingerprint FROM originals x WHERE x.sha256 = c.sha256 LIMIT 1)
		FROM (SELECT DISTINCT physical_id, sha256 FROM mirror_entries WHERE store = 'blob' AND sha256 IS NOT NULL) c
		JOIN blobs b ON b.sha256 = c.sha256`)
	if err != nil {
		return nil, err
	}
	type blobRow struct {
		physical, sha, fp string
		size              int64
		shared            bool
	}
	var brs []blobRow
	for rows.Next() {
		var br blobRow
		var fp sql.NullString
		if err := rows.Scan(&br.physical, &br.sha, &br.size, &br.shared, &fp); err != nil {
			rows.Close()
			return nil, err
		}
		br.fp = fp.String
		brs = append(brs, br)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	named, err := s.NamedFingerprints(ctx)
	if err != nil {
		return nil, err
	}
	for _, br := range brs {
		u := out[br.physical]
		u.PhysicalID = br.physical
		if br.shared || named[br.fp] {
			u.SharedBlobs++
			u.SharedBytes += br.size
		} else {
			u.OwnBlobs++
			u.OwnBytes += br.size
		}
		out[br.physical] = u
	}
	rows, err = s.db.QueryContext(ctx, `SELECT physical_id, count(*), coalesce(sum(size), 0) FROM (SELECT DISTINCT physical_id, fingerprint, size
		FROM mirror_entries WHERE store = 'nas') GROUP BY physical_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var p string
		var n int
		var b int64
		if err := rows.Scan(&p, &n, &b); err != nil {
			return nil, err
		}
		u := out[p]
		u.PhysicalID, u.NasFiles, u.NasBytes = p, n, b
		out[p] = u
	}
	return out, rows.Err()
}

// --- claims and moves of physical devices (ADR 0024 "Security", ADR 0017) --------------------------------------------

// MirrorMove is what moving a phone's backup to another physical device did on the database; the caller moves the
// links on disk.
type MirrorMove struct {
	FromDir string
	ToDir   string
	ToID    string
	// Relinked are entries whose link was in FromDir and must move to ToDir (empty when the folder itself moved).
	Relinked []MirrorEntry
	Dropped  []string // roots merged into another root (their manifests go)
}

// moveMirror moves a physical device's backup to another one (its last live device moved there): roots move, a root
// with the same volume and path is merged (the newer file wins a path, the other becomes "replaced"). Called inside
// MoveDevice's transaction.
func moveMirror(ctx context.Context, tx *sql.Tx, from, to string, now int64) (*MirrorMove, error) {
	var fromDir string
	err := tx.QueryRowContext(ctx, "SELECT dir_name FROM mirror_devices WHERE physical_id = ?", from).Scan(&fromDir)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var toDir string
	err = tx.QueryRowContext(ctx, "SELECT dir_name FROM mirror_devices WHERE physical_id = ?", to).Scan(&toDir)
	if errors.Is(err, sql.ErrNoRows) {
		// The folder moves with its roots (it keeps its name).
		if _, err := tx.ExecContext(ctx, "UPDATE mirror_devices SET physical_id = ? WHERE physical_id = ?", to, from); err != nil {
			return nil, err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET physical_id = ? WHERE physical_id = ?", to, from); err != nil {
			return nil, err
		}
		return &MirrorMove{FromDir: fromDir, ToDir: fromDir, ToID: to}, nil
	}
	if err != nil {
		return nil, err
	}
	mv := &MirrorMove{FromDir: fromDir, ToDir: toDir, ToID: to}
	// Paths both phones hold: the newer modified_at wins, the other becomes a previous version (or, the same content,
	// simply leaves the path).
	type clash struct {
		a, b       int64
		am, bm     int64
		afp, bfp   string
		aSHA, bSHA string
	}
	rows, err := tx.QueryContext(ctx, `SELECT a.id, a.modified_at, a.fingerprint, coalesce(a.sha256, ''), b.id, b.modified_at, b.fingerprint, coalesce(b.sha256, '')
		FROM mirror_entries a JOIN mirror_entries b ON b.physical_id = ? AND b.volume = a.volume AND b.path = a.path
		AND (b.removed_reason IS NULL OR b.removed_reason IN ('deleted', 'freed'))
		WHERE a.physical_id = ? AND (a.removed_reason IS NULL OR a.removed_reason IN ('deleted', 'freed'))`, to, from)
	if err != nil {
		return nil, err
	}
	var clashes []clash
	for rows.Next() {
		var c clash
		if err := rows.Scan(&c.a, &c.am, &c.afp, &c.aSHA, &c.b, &c.bm, &c.bfp, &c.bSHA); err != nil {
			rows.Close()
			return nil, err
		}
		clashes = append(clashes, c)
	}
	rows.Close()
	for _, c := range clashes {
		loser := c.a
		if c.am > c.bm {
			loser = c.b
		}
		reason := RemovedReplaced
		if c.afp == c.bfp && (c.aSHA == "" || c.bSHA == "" || c.aSHA == c.bSHA) {
			reason = RemovedMoved
		}
		if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET removed_reason = ?, removed_at = coalesce(removed_at, ?), on_device = 0, updated_at = ? WHERE id = ?",
			reason, now, now, loser); err != nil {
			return nil, err
		}
	}
	mv.Relinked, err = queryMirrorEntries(ctx, tx, "WHERE e.physical_id = ? AND e.tree_path IS NOT NULL", from)
	if err != nil {
		return nil, err
	}
	fromRoots, err := queryMirrorRoots(ctx, tx, "WHERE r.physical_id = ?", from)
	if err != nil {
		return nil, err
	}
	toRoots, err := queryMirrorRoots(ctx, tx, "WHERE r.physical_id = ?", to)
	if err != nil {
		return nil, err
	}
	for _, r := range fromRoots {
		target := ""
		for _, t := range toRoots {
			if t.Volume == r.Volume && t.Path == r.Path {
				target = t.ID
			}
		}
		if target != "" {
			if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET root_id = ? WHERE root_id = ?", target, r.ID); err != nil {
				return nil, err
			}
			if _, err := tx.ExecContext(ctx, "DELETE FROM mirror_roots WHERE id = ?", r.ID); err != nil {
				return nil, err
			}
			mv.Dropped = append(mv.Dropped, r.ID)
			continue
		}
		// A root's path that is now also covered by another root of the phone is kept as it is (both mirror it).
		if _, err := tx.ExecContext(ctx, "UPDATE mirror_roots SET physical_id = ? WHERE id = ?", to, r.ID); err != nil {
			return nil, err
		}
	}
	if _, err := tx.ExecContext(ctx, "UPDATE mirror_entries SET physical_id = ?, tree_path = NULL, linked = 0 WHERE physical_id = ?", to, from); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM mirror_devices WHERE physical_id = ?", from); err != nil {
		return nil, err
	}
	return mv, nil
}
