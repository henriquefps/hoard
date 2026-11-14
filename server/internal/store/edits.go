package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

// Edit is Reveal's edited preview of one version of an item (ADR 0020).
type Edit struct {
	VersionID     string `json:"versionId"`
	ItemID        string `json:"-"`
	DeviceID      string `json:"deviceId"`
	RecipeDigest  string `json:"recipeDigest"`
	EditedAt      int64  `json:"editedAt"`
	Label         string `json:"label"`
	IsVirtualCopy bool   `json:"isVirtualCopy"`
	Primary       bool   `json:"primary"`
	SHA256        string `json:"-"`
	Format        string `json:"format"`
	Width         int    `json:"width"`
	Height        int    `json:"height"`
	Bytes         int64  `json:"bytes"`
	CreatedAt     int64  `json:"-"`
	UpdatedAt     int64  `json:"updatedAt"`
}

const editCols = `version_id, item_id, device_id, recipe_digest, edited_at, label, is_virtual_copy, is_primary, sha256, format,
	width, height, bytes, created_at, updated_at`

// editOrder: the primary first, then the others in the order they were first uploaded.
const editOrder = " ORDER BY is_primary DESC, created_at, version_id"

func scanEdits(rows *sql.Rows) ([]Edit, error) {
	defer rows.Close()
	var out []Edit
	for rows.Next() {
		var e Edit
		if err := rows.Scan(&e.VersionID, &e.ItemID, &e.DeviceID, &e.RecipeDigest, &e.EditedAt, &e.Label, &e.IsVirtualCopy, &e.Primary,
			&e.SHA256, &e.Format, &e.Width, &e.Height, &e.Bytes, &e.CreatedAt, &e.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// ItemEdits returns an item's edits, the primary first.
func (s *Store) ItemEdits(ctx context.Context, itemID string) ([]Edit, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+editCols+" FROM item_edits WHERE item_id = ?"+editOrder, itemID)
	if err != nil {
		return nil, err
	}
	return scanEdits(rows)
}

// EditsByItem returns the edits of the given items, by item, each list the primary first.
func (s *Store) EditsByItem(ctx context.Context, ids []string) (map[string][]Edit, error) {
	out := map[string][]Edit{}
	for start := 0; start < len(ids); start += 500 {
		chunk := ids[start:min(start+500, len(ids))]
		args := make([]any, len(chunk))
		for i, id := range chunk {
			args[i] = id
		}
		//nolint:gosec // placeholders only; the ids are arguments
		rows, err := s.db.QueryContext(ctx, "SELECT "+editCols+" FROM item_edits WHERE item_id IN ("+placeholders(len(chunk))+")"+
			" ORDER BY item_id, is_primary DESC, created_at, version_id", args...)
		if err != nil {
			return nil, err
		}
		list, err := scanEdits(rows)
		if err != nil {
			return nil, err
		}
		for _, e := range list {
			out[e.ItemID] = append(out[e.ItemID], e)
		}
	}
	return out, nil
}

// Edit returns one version's edit.
func (s *Store) Edit(ctx context.Context, versionID string) (Edit, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+editCols+" FROM item_edits WHERE version_id = ?", versionID)
	if err != nil {
		return Edit{}, err
	}
	list, err := scanEdits(rows)
	if err != nil {
		return Edit{}, err
	}
	if len(list) == 0 {
		return Edit{}, ErrNotFound
	}
	return list[0], nil
}

// attachEdits fills the Edits JSON of item rows (live items with edits only).
func (s *Store) attachEdits(ctx context.Context, rows []ItemRow) error {
	var ids []string
	for _, r := range rows {
		if !r.Deleted {
			ids = append(ids, r.ID)
		}
	}
	if len(ids) == 0 {
		return nil
	}
	by, err := s.EditsByItem(ctx, ids)
	if err != nil || len(by) == 0 {
		return err
	}
	for i := range rows {
		if list := by[rows[i].ID]; len(list) > 0 {
			b, err := json.Marshal(list)
			if err != nil {
				return err
			}
			rows[i].Edits = b
		}
	}
	return nil
}

// Edit write outcomes.
const (
	EditCreated   = "created"   // a new edit
	EditReplaced  = "replaced"  // a new image (another recipe digest) for the version
	EditUpdated   = "updated"   // the same image, other metadata (label, primary, virtual copy, editedAt)
	EditUnchanged = "unchanged" // nothing changed
)

// ErrEditOlder: the server has a different edit of the version made later (editedAt); the upload is refused.
var ErrEditOlder = errors.New("a newer edit of this version is stored")

// ErrTooManyEdits: the item already has the most edits it may have.
var ErrTooManyEdits = errors.New("too many edits on this item")

// ErrItemGone: the item is not live.
var ErrItemGone = errors.New("no such live item")

// ErrImageRequired: a PUT without an image for a version the server has no edit of with the same recipe digest.
var ErrImageRequired = errors.New("an image is required")

// PutEdit stores an edit. e carries the image fields (SHA256, Format, Width, Height, Bytes) when the request had an
// image; without one (SHA256 == "") only the metadata of a stored edit with the same recipe digest can change. In one
// transaction: the item must be live; an edit with an older editedAt than the stored one is refused (ErrEditOlder,
// with the stored edit); the same recipe digest keeps the stored image; a version stored on another item moves here;
// a primary edit clears the item's other primary; the item (and the one it moved from) gets a new revision unless
// nothing changed. It returns the outcome, the stored edit, the image no edit uses any more ("" when none) and the
// item's revision.
func (s *Store) PutEdit(ctx context.Context, e Edit, maxPerItem int) (string, Edit, string, int64, error) {
	now := s.nowMs()
	var status, oldSHA string
	var rev int64
	err := s.tx(ctx, func(tx *sql.Tx) error {
		var deleted bool
		if err := tx.QueryRowContext(ctx, "SELECT deleted, rev FROM items WHERE id = ?", e.ItemID).Scan(&deleted, &rev); errors.Is(err, sql.ErrNoRows) || err == nil && deleted {
			return ErrItemGone
		} else if err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, "SELECT "+editCols+" FROM item_edits WHERE version_id = ?", e.VersionID)
		if err != nil {
			return err
		}
		list, err := scanEdits(rows)
		if err != nil {
			return err
		}
		var old *Edit
		if len(list) == 1 {
			old = &list[0]
		}
		switch {
		case old == nil:
			if e.SHA256 == "" {
				return ErrImageRequired
			}
			var n int
			if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM item_edits WHERE item_id = ?", e.ItemID).Scan(&n); err != nil {
				return err
			}
			if n >= maxPerItem {
				return ErrTooManyEdits
			}
			status = EditCreated
		case e.EditedAt < old.EditedAt:
			e = *old
			return ErrEditOlder
		case old.RecipeDigest == e.RecipeDigest:
			// The same render: the stored image stays (an upload's bytes are not even read again).
			e.SHA256, e.Format, e.Width, e.Height, e.Bytes = old.SHA256, old.Format, old.Width, old.Height, old.Bytes
			if old.ItemID == e.ItemID && old.Label == e.Label && old.IsVirtualCopy == e.IsVirtualCopy && old.Primary == e.Primary &&
				old.EditedAt == e.EditedAt {
				status = EditUnchanged
				e = *old
				return nil
			}
			status = EditUpdated
		case e.SHA256 == "":
			return ErrImageRequired
		default:
			status = EditReplaced
			if old.SHA256 != e.SHA256 {
				oldSHA = old.SHA256
			}
		}
		e.CreatedAt, e.UpdatedAt = now, now
		if old != nil {
			e.CreatedAt = old.CreatedAt
			if old.ItemID != e.ItemID {
				if _, err := bumpItemRev(ctx, tx, old.ItemID, now); err != nil {
					return err
				}
			}
		}
		if e.Primary {
			if _, err := tx.ExecContext(ctx, "UPDATE item_edits SET is_primary = 0, updated_at = ? WHERE item_id = ? AND is_primary = 1 AND version_id != ?",
				now, e.ItemID, e.VersionID); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO item_edits (`+editCols+`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (version_id) DO UPDATE SET item_id = excluded.item_id, device_id = excluded.device_id,
			recipe_digest = excluded.recipe_digest, edited_at = excluded.edited_at, label = excluded.label,
			is_virtual_copy = excluded.is_virtual_copy, is_primary = excluded.is_primary, sha256 = excluded.sha256,
			format = excluded.format, width = excluded.width, height = excluded.height, bytes = excluded.bytes,
			updated_at = excluded.updated_at`,
			e.VersionID, e.ItemID, e.DeviceID, e.RecipeDigest, e.EditedAt, e.Label, e.IsVirtualCopy, e.Primary, e.SHA256, e.Format,
			e.Width, e.Height, e.Bytes, e.CreatedAt, e.UpdatedAt); err != nil {
			return err
		}
		rev, err = bumpItemRev(ctx, tx, e.ItemID, now)
		return err
	})
	if errors.Is(err, ErrEditOlder) {
		return "", e, "", rev, err
	}
	if err != nil {
		return "", Edit{}, "", 0, err
	}
	if oldSHA != "" {
		if used, err := s.EditImageUsed(ctx, oldSHA); err != nil || used {
			oldSHA = ""
		}
	}
	return status, e, oldSHA, rev, nil
}

// DeleteEdit removes a version's edit from an item. It reports whether one was there, the image no edit uses any
// more ("" when none) and the item's revision.
func (s *Store) DeleteEdit(ctx context.Context, itemID, versionID string) (bool, string, int64, error) {
	var found bool
	var sha string
	var rev int64
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if err := tx.QueryRowContext(ctx, "SELECT rev FROM items WHERE id = ?", itemID).Scan(&rev); err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		err := tx.QueryRowContext(ctx, "SELECT sha256 FROM item_edits WHERE version_id = ? AND item_id = ?", versionID, itemID).Scan(&sha)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		found = true
		if _, err := tx.ExecContext(ctx, "DELETE FROM item_edits WHERE version_id = ?", versionID); err != nil {
			return err
		}
		var err2 error
		rev, err2 = bumpItemRev(ctx, tx, itemID, s.nowMs())
		return err2
	})
	if err != nil || !found {
		return found, "", rev, err
	}
	if used, err := s.EditImageUsed(ctx, sha); err != nil || used {
		return true, "", rev, err
	}
	return true, sha, rev, nil
}

// EditImageUsed reports whether any edit names the image.
func (s *Store) EditImageUsed(ctx context.Context, sha string) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM item_edits WHERE sha256 = ?", sha).Scan(&n)
	return n > 0, err
}

// EditImages returns every image an edit names (for sweeping files no edit uses).
func (s *Store) EditImages(ctx context.Context) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT DISTINCT sha256 FROM item_edits")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var sha string
		if err := rows.Scan(&sha); err != nil {
			return nil, err
		}
		out[sha] = true
	}
	return out, rows.Err()
}

// EditStats is the setup page's report on edits.
type EditStats struct {
	Edits         int   `json:"edits"`
	Items         int   `json:"items"`
	VirtualCopies int   `json:"virtualCopies"`
	Images        int   `json:"images"`       // distinct uploaded files
	Bytes         int64 `json:"bytes"`        // their size
	PreviewBytes  int64 `json:"previewBytes"` // thumbs and screens made from them
	Previews      int   `json:"previews"`     // edits whose previews are on disk
	Pending       int   `json:"pending"`      // queued or running
	Failed        int   `json:"failed"`
}

// EditStatistics counts edits, their uploaded files and their previews.
func (s *Store) EditStatistics(ctx context.Context) (EditStats, error) {
	var st EditStats
	err := s.db.QueryRowContext(ctx, `SELECT count(*), count(DISTINCT item_id), coalesce(sum(is_virtual_copy), 0) FROM item_edits`).
		Scan(&st.Edits, &st.Items, &st.VirtualCopies)
	if err != nil {
		return st, err
	}
	if err := s.db.QueryRowContext(ctx, `SELECT count(*), coalesce(sum(bytes), 0) FROM (SELECT sha256, max(bytes) AS bytes FROM item_edits GROUP BY sha256)`).
		Scan(&st.Images, &st.Bytes); err != nil {
		return st, err
	}
	err = s.db.QueryRowContext(ctx, `SELECT coalesce(sum(CASE WHEN key != '' THEN bytes ELSE 0 END), 0),
		coalesce(sum(CASE WHEN key != '' THEN 1 ELSE 0 END), 0), coalesce(sum(CASE WHEN state IN ('pending', 'running') THEN 1 ELSE 0 END), 0),
		coalesce(sum(CASE WHEN state = 'failed' THEN 1 ELSE 0 END), 0) FROM previews WHERE job LIKE 'edit:%'`).
		Scan(&st.PreviewBytes, &st.Previews, &st.Pending, &st.Failed)
	return st, err
}

// bumpItemRev gives a live item a new revision (its edits changed: the items feed sends it again) and returns it.
func bumpItemRev(ctx context.Context, tx *sql.Tx, itemID string, now int64) (int64, error) {
	rev, err := next(ctx, tx, "items")
	if err != nil {
		return 0, err
	}
	_, err = tx.ExecContext(ctx, "UPDATE items SET rev = ?, updated_at = ? WHERE id = ? AND deleted = 0", rev, now, itemID)
	return rev, err
}

// editsOfTombstone handles the edits of an item that became a tombstone: they follow the item that replaced it (its
// RAW appeared, ADR 0014 "Item id"), else they are removed with it (ADR 0020 "Lifecycle"). On a move the item keeps
// at most one primary (the latest edited).
func editsOfTombstone(ctx context.Context, tx *sql.Tx, itemID string, tombstone []byte, now int64) error {
	var n int
	if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM item_edits WHERE item_id = ?", itemID).Scan(&n); err != nil || n == 0 {
		return err
	}
	var t struct {
		ReplacedBy string `json:"replacedBy"`
	}
	_ = json.Unmarshal(tombstone, &t)
	if t.ReplacedBy == "" {
		_, err := tx.ExecContext(ctx, "DELETE FROM item_edits WHERE item_id = ?", itemID)
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE item_edits SET item_id = ? WHERE item_id = ?", t.ReplacedBy, itemID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE item_edits SET is_primary = 0 WHERE item_id = ?1 AND is_primary = 1 AND version_id != (
		SELECT version_id FROM item_edits WHERE item_id = ?1 AND is_primary = 1 ORDER BY edited_at DESC, version_id LIMIT 1)`, t.ReplacedBy); err != nil {
		return err
	}
	_, err := bumpItemRev(ctx, tx, t.ReplacedBy, now)
	return err
}
