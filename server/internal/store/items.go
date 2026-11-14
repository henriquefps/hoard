package store

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"slices"
	"strings"
)

// --- file metadata -------------------------------------------------------------------------------------------------

// MetaFingerprints returns the fingerprints whose metadata was read with the given rules version.
func (s *Store) MetaFingerprints(ctx context.Context, version int) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT fingerprint FROM file_meta WHERE version = ?", version)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var fp string
		if err := rows.Scan(&fp); err != nil {
			return nil, err
		}
		out[fp] = true
	}
	return out, rows.Err()
}

// PutFileMetas stores metadata (JSON) per fingerprint.
func (s *Store) PutFileMetas(ctx context.Context, version int, metas map[string][]byte) error {
	if len(metas) == 0 {
		return nil
	}
	now := s.nowMs()
	return s.tx(ctx, func(tx *sql.Tx) error {
		for fp, m := range metas {
			if _, err := tx.ExecContext(ctx, `INSERT INTO file_meta (fingerprint, version, meta, updated_at) VALUES (?, ?, ?, ?)
				ON CONFLICT (fingerprint) DO UPDATE SET version = excluded.version, meta = excluded.meta, updated_at = excluded.updated_at`,
				fp, version, string(m), now); err != nil {
				return err
			}
		}
		return nil
	})
}

type rowsQueryer interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

func placeholders(n int) string {
	return strings.TrimSuffix(strings.Repeat("?,", n), ",")
}

func fileMetas(ctx context.Context, q rowsQueryer, fps []string) (map[string][]byte, error) {
	out := map[string][]byte{}
	for start := 0; start < len(fps); start += 500 {
		chunk := fps[start:min(start+500, len(fps))]
		args := make([]any, len(chunk))
		for i, fp := range chunk {
			args[i] = fp
		}
		rows, err := q.QueryContext(ctx, "SELECT fingerprint, meta FROM file_meta WHERE fingerprint IN ("+placeholders(len(chunk))+")", args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var fp, m string
			if err := rows.Scan(&fp, &m); err != nil {
				rows.Close()
				return nil, err
			}
			out[fp] = []byte(m)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// --- item sources --------------------------------------------------------------------------------------------------

// ItemSource is one row of item_sources: a NAS group or an uploaded original, and the item it belongs to.
type ItemSource struct {
	ID        int64
	Source    string // nas | backup
	FolderID  string
	Dir       string
	Key       string
	ItemID    string
	MainFP    string
	Signature string
	Members   []byte // JSON
}

const sourceCols = "id, source, folder_id, dir, key, item_id, main_fp, signature, members"

func scanSources(rows *sql.Rows) ([]ItemSource, error) {
	defer rows.Close()
	var out []ItemSource
	for rows.Next() {
		var r ItemSource
		var members string
		if err := rows.Scan(&r.ID, &r.Source, &r.FolderID, &r.Dir, &r.Key, &r.ItemID, &r.MainFP, &r.Signature, &members); err != nil {
			return nil, err
		}
		r.Members = []byte(members)
		out = append(out, r)
	}
	return out, rows.Err()
}

// ItemSources lists the sources of one kind (and folder, for NAS sources).
func (s *Store) ItemSources(ctx context.Context, source, folderID string) ([]ItemSource, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+sourceCols+" FROM item_sources WHERE source = ? AND folder_id = ?", source, folderID)
	if err != nil {
		return nil, err
	}
	return scanSources(rows)
}

// SourceFolders lists the folder ids that still have NAS sources.
func (s *Store) SourceFolders(ctx context.Context) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT DISTINCT folder_id FROM item_sources WHERE source = 'nas'")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// ItemTx is one transaction of the items indexer: sources and the items they make change together, so a crash never
// leaves an item behind its sources.
type ItemTx struct {
	ctx context.Context
	tx  *sql.Tx
	now int64
}

// ItemsTx runs fn in one write transaction.
func (s *Store) ItemsTx(ctx context.Context, fn func(*ItemTx) error) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		return fn(&ItemTx{ctx: ctx, tx: tx, now: s.nowMs()})
	})
}

// PutSource inserts or replaces a source (by source, folder, dir and key).
func (t *ItemTx) PutSource(r ItemSource) error {
	_, err := t.tx.ExecContext(t.ctx, `INSERT INTO item_sources (source, folder_id, dir, key, item_id, main_fp, signature, members)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (source, folder_id, dir, key) DO UPDATE SET item_id = excluded.item_id,
		main_fp = excluded.main_fp, signature = excluded.signature, members = excluded.members`,
		r.Source, r.FolderID, r.Dir, r.Key, r.ItemID, r.MainFP, r.Signature, string(r.Members))
	return err
}

// DeleteSource removes a source row.
func (t *ItemTx) DeleteSource(id int64) error {
	_, err := t.tx.ExecContext(t.ctx, "DELETE FROM item_sources WHERE id = ?", id)
	return err
}

// SourcesOf lists an item's sources.
func (t *ItemTx) SourcesOf(itemID string) ([]ItemSource, error) {
	rows, err := t.tx.QueryContext(t.ctx, "SELECT "+sourceCols+" FROM item_sources WHERE item_id = ? ORDER BY id", itemID)
	if err != nil {
		return nil, err
	}
	return scanSources(rows)
}

// FileMetas returns the stored metadata of the given fingerprints.
func (t *ItemTx) FileMetas(fps []string) (map[string][]byte, error) {
	return fileMetas(t.ctx, t.tx, fps)
}

// ItemFile is one member of an item, for the lookup index.
type ItemFile struct {
	Fingerprint string `json:"fingerprint"`
	ItemID      string `json:"itemId"`
	Role        string `json:"role"`
}

// WriteItem stores an item's new state (data != nil) or turns it into a tombstone (data == nil; tombstone is its
// JSON, e.g. {"replacedBy": …}). A state equal to the stored one writes nothing. It reports whether a revision was
// used.
func (t *ItemTx) WriteItem(id, mainFP, kind string, sortTime int64, data []byte, files []ItemFile, tombstone []byte) (bool, error) {
	var cur struct {
		deleted bool
		data    string
	}
	err := t.tx.QueryRowContext(t.ctx, "SELECT deleted, data FROM items WHERE id = ?", id).Scan(&cur.deleted, &cur.data)
	exists := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return false, err
	}
	if data == nil {
		if !exists || cur.deleted {
			return false, nil
		}
		if tombstone == nil {
			tombstone = []byte("{}")
		}
		rev, err := next(t.ctx, t.tx, "items")
		if err != nil {
			return false, err
		}
		if _, err := t.tx.ExecContext(t.ctx, "UPDATE items SET deleted = 1, rev = ?, data = ?, updated_at = ? WHERE id = ?", rev, string(tombstone), t.now, id); err != nil {
			return false, err
		}
		if _, err = t.tx.ExecContext(t.ctx, "DELETE FROM item_files WHERE item_id = ?", id); err != nil {
			return false, err
		}
		// Its edits follow the item that replaced it, else go with it (ADR 0020 "Lifecycle").
		if err := editsOfTombstone(t.ctx, t.tx, id, tombstone, t.now); err != nil {
			return false, err
		}
		// Its culling record is sent again, now naming the item that replaced it (ADR 0016 "Mapping a record").
		return true, touchCulling(t.ctx, t.tx, id)
	}
	if exists && !cur.deleted && bytes.Equal([]byte(cur.data), data) {
		return false, nil
	}
	rev, err := next(t.ctx, t.tx, "items")
	if err != nil {
		return false, err
	}
	if _, err := t.tx.ExecContext(t.ctx, `INSERT INTO items (id, main_fp, rev, deleted, kind, sort_time, data, created_at, updated_at)
		VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET rev = excluded.rev, deleted = 0, kind = excluded.kind,
		sort_time = excluded.sort_time, data = excluded.data, updated_at = excluded.updated_at`,
		id, mainFP, rev, kind, sortTime, string(data), t.now, t.now); err != nil {
		return false, err
	}
	before, err := t.ItemFileFingerprints(id)
	if err != nil {
		return false, err
	}
	if _, err := t.tx.ExecContext(t.ctx, "DELETE FROM item_files WHERE item_id = ?", id); err != nil {
		return false, err
	}
	after := make([]string, 0, len(files))
	for _, f := range files {
		after = append(after, f.Fingerprint)
	}
	slices.Sort(after)
	if !slices.Equal(before, slices.Compact(after)) {
		// Clients map culling records by the item's member fingerprints: a changed member list is sent again.
		if err := touchCulling(t.ctx, t.tx, id); err != nil {
			return false, err
		}
	}
	for _, f := range files {
		if _, err := t.tx.ExecContext(t.ctx, "INSERT INTO item_files (fingerprint, item_id, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
			f.Fingerprint, id, f.Role); err != nil {
			return false, err
		}
	}
	return true, nil
}

// LiveItemWithFile returns a live item, other than except, that has fp as a member and at least one source of the
// given kind ("" = any): the item fp's file belongs to.
func (t *ItemTx) LiveItemWithFile(fp, except, sourceKind string) (string, error) {
	var id string
	err := t.tx.QueryRowContext(t.ctx, `SELECT f.item_id FROM item_files f JOIN items i ON i.id = f.item_id
		WHERE f.fingerprint = ? AND f.item_id != ? AND i.deleted = 0
		AND (? = '' OR EXISTS (SELECT 1 FROM item_sources s WHERE s.item_id = f.item_id AND s.source = ?))
		ORDER BY (i.main_fp = f.fingerprint) DESC, f.item_id LIMIT 1`, fp, except, sourceKind, sourceKind).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return id, err
}

// --- reads for the API ---------------------------------------------------------------------------------------------

// ItemRow is a stored item.
type ItemRow struct {
	ID        string
	Rev       int64
	Deleted   bool
	Kind      string
	SortTime  int64
	Data      []byte
	CreatedAt int64
	UpdatedAt int64
	Edits     []byte // JSON array of the item's edits (ADR 0020), nil when it has none
}

const itemCols = "id, rev, deleted, kind, sort_time, data, created_at, updated_at"

func scanItems(rows *sql.Rows) ([]ItemRow, error) {
	defer rows.Close()
	out := []ItemRow{}
	for rows.Next() {
		var r ItemRow
		var data string
		if err := rows.Scan(&r.ID, &r.Rev, &r.Deleted, &r.Kind, &r.SortTime, &data, &r.CreatedAt, &r.UpdatedAt); err != nil {
			return nil, err
		}
		r.Data = []byte(data)
		out = append(out, r)
	}
	return out, rows.Err()
}

// ItemChanges returns items with rev > since, oldest first (the items feed).
func (s *Store) ItemChanges(ctx context.Context, since int64, limit int) ([]ItemRow, bool, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+itemCols+" FROM items WHERE rev > ? ORDER BY rev LIMIT ?", since, limit+1)
	if err != nil {
		return nil, false, err
	}
	out, err := scanItems(rows)
	if err != nil {
		return nil, false, err
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	return out, more, s.attachEdits(ctx, out)
}

// Item returns one item (a tombstone too).
func (s *Store) Item(ctx context.Context, id string) (ItemRow, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+itemCols+" FROM items WHERE id = ?", id)
	if err != nil {
		return ItemRow{}, err
	}
	out, err := scanItems(rows)
	if err != nil {
		return ItemRow{}, err
	}
	if len(out) == 0 {
		return ItemRow{}, ErrNotFound
	}
	return out[0], s.attachEdits(ctx, out)
}

// ItemQuery filters live items (GET /v1/items).
type ItemQuery struct {
	Kinds    []string
	From, To *int64 // sort time, [From, To)
	FolderID string
	Dir      string // with FolderID: the directory and everything under it
	Desc     bool
	After    *ItemKey // keyset position
	Limit    int
}

// ItemKey is a position in the sort order.
type ItemKey struct {
	SortTime int64
	ID       string
}

// QueryItems lists live items in sort-time order (then id).
func (s *Store) QueryItems(ctx context.Context, q ItemQuery) ([]ItemRow, bool, error) {
	where := []string{"deleted = 0"}
	var args []any
	if len(q.Kinds) > 0 {
		where = append(where, "kind IN ("+placeholders(len(q.Kinds))+")")
		for _, k := range q.Kinds {
			args = append(args, k)
		}
	}
	if q.From != nil {
		where = append(where, "sort_time >= ?")
		args = append(args, *q.From)
	}
	if q.To != nil {
		where = append(where, "sort_time < ?")
		args = append(args, *q.To)
	}
	if q.FolderID != "" {
		cond := "EXISTS (SELECT 1 FROM item_sources s WHERE s.item_id = items.id AND s.source = 'nas' AND s.folder_id = ?"
		args = append(args, q.FolderID)
		if q.Dir != "" {
			cond += ` AND (s.dir = ? OR s.dir LIKE ? ESCAPE '\')`
			args = append(args, q.Dir, likeEscape(q.Dir)+"/%")
		}
		where = append(where, cond+")")
	}
	order := "ASC"
	cmp := ">"
	if q.Desc {
		order, cmp = "DESC", "<"
	}
	if q.After != nil {
		where = append(where, "(sort_time, id) "+cmp+" (?, ?)")
		args = append(args, q.After.SortTime, q.After.ID)
	}
	args = append(args, q.Limit+1)
	//nolint:gosec // only fixed clauses and "?" placeholders are joined; values are arguments
	rows, err := s.db.QueryContext(ctx, "SELECT "+itemCols+" FROM items WHERE "+strings.Join(where, " AND ")+
		" ORDER BY sort_time "+order+", id "+order+" LIMIT ?", args...)
	if err != nil {
		return nil, false, err
	}
	out, err := scanItems(rows)
	if err != nil {
		return nil, false, err
	}
	more := len(out) > q.Limit
	if more {
		out = out[:q.Limit]
	}
	return out, more, s.attachEdits(ctx, out)
}

func likeEscape(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return r.Replace(s)
}

// ItemFilesByFingerprint returns the live items that have any of the fingerprints as a member.
func (s *Store) ItemFilesByFingerprint(ctx context.Context, fps []string) ([]ItemFile, error) {
	var out []ItemFile
	for start := 0; start < len(fps); start += 500 {
		chunk := fps[start:min(start+500, len(fps))]
		args := make([]any, len(chunk))
		for i, fp := range chunk {
			args[i] = fp
		}
		//nolint:gosec // placeholders only; the fingerprints are arguments
		rows, err := s.db.QueryContext(ctx, `SELECT f.fingerprint, f.item_id, f.role FROM item_files f JOIN items i ON i.id = f.item_id
			WHERE i.deleted = 0 AND f.fingerprint IN (`+placeholders(len(chunk))+`) ORDER BY f.fingerprint, (i.main_fp = f.fingerprint) DESC, f.item_id`, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var f ItemFile
			if err := rows.Scan(&f.Fingerprint, &f.ItemID, &f.Role); err != nil {
				rows.Close()
				return nil, err
			}
			out = append(out, f)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// ItemCount counts live items.
func (s *Store) ItemCount(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM items WHERE deleted = 0").Scan(&n)
	return n, err
}

// UploadedOriginal is an original a device uploaded.
type UploadedOriginal struct {
	Fingerprint string
	SHA256      string
	Size        int64
	CreatedAt   int64 // ms
}

// UploadedOriginals lists every uploaded original with its blob.
func (s *Store) UploadedOriginals(ctx context.Context) ([]UploadedOriginal, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT o.fingerprint, b.sha256, b.size, b.created_at FROM originals o JOIN blobs b ON b.sha256 = o.sha256
		ORDER BY o.fingerprint`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []UploadedOriginal
	for rows.Next() {
		var o UploadedOriginal
		if err := rows.Scan(&o.Fingerprint, &o.SHA256, &o.Size, &o.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	return out, rows.Err()
}

// ItemMain returns an item's main fingerprint ("" when the item does not exist).
func (t *ItemTx) ItemMain(id string) (string, error) {
	var fp string
	err := t.tx.QueryRowContext(t.ctx, "SELECT main_fp FROM items WHERE id = ?", id).Scan(&fp)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return fp, err
}

// FileMetas returns the stored metadata (JSON) of the given fingerprints.
func (s *Store) FileMetas(ctx context.Context, fps []string) (map[string][]byte, error) {
	return fileMetas(ctx, s.db, fps)
}
