package store

import (
	"context"
	"database/sql"
	"errors"
)

// Preview states (ADR 0015).
const (
	PreviewPending = "pending"
	PreviewRunning = "running"
	PreviewDone    = "done"
	PreviewFailed  = "failed"
)

// Preview priorities: lower runs first.
const (
	PriorityWaiting = 0 // a client asked for it and waits (202)
	PriorityWarm    = 1 // a client asked to warm it
	PriorityRecent  = 2 // the item was added recently
	PriorityBacklog = 3
)

// Preview is one item's job: the queue entry and what is on disk.
type Preview struct {
	ItemID    string
	Job       string
	State     string
	Priority  int
	OrderKey  int64
	NotBefore int64
	Attempts  int
	Error     string
	WantKey   string
	Key       string
	Outputs   []byte // JSON: file name -> {w, h, bytes}
	Bytes     int64
	Generator string
	Ms        int64
	UpdatedAt int64
}

const previewCols = "item_id, job, state, priority, order_key, not_before, attempts, error, want_key, key, outputs, bytes, generator, ms, updated_at"

func scanPreview(row interface{ Scan(...any) error }) (Preview, error) {
	var p Preview
	var outputs string
	err := row.Scan(&p.ItemID, &p.Job, &p.State, &p.Priority, &p.OrderKey, &p.NotBefore, &p.Attempts, &p.Error, &p.WantKey,
		&p.Key, &outputs, &p.Bytes, &p.Generator, &p.Ms, &p.UpdatedAt)
	p.Outputs = []byte(outputs)
	return p, err
}

// Previews returns an item's preview rows by job.
func (s *Store) Previews(ctx context.Context, itemID string) (map[string]Preview, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+previewCols+" FROM previews WHERE item_id = ?", itemID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]Preview{}
	for rows.Next() {
		p, err := scanPreview(rows)
		if err != nil {
			return nil, err
		}
		out[p.Job] = p
	}
	return out, rows.Err()
}

// PreviewWant is a job an item needs: its current content key, at a priority.
type PreviewWant struct {
	ItemID   string
	Job      string
	WantKey  string
	Priority int
	OrderKey int64
}

// WantPreviews records what items need, in one transaction (ADR 0015 "Queue"):
//   - no row: a pending row;
//   - a row for another key (the source or the recipe changed): pending again for the new key, keeping the files it
//     has (served, stale, until the new ones are made); a failure of the old key is forgotten;
//   - a pending row: raised to the new priority if that is more urgent (with its order key), or, for a client's
//     request, moved to the front of its priority (the newest request first: what the user looks at now);
//   - a running, done or failed row for the same key: unchanged.
//
// It returns how many rows became (or stay) pending with a raised priority, so the caller can wake its workers.
func (s *Store) WantPreviews(ctx context.Context, wants []PreviewWant) (int, error) {
	if len(wants) == 0 {
		return 0, nil
	}
	now := s.nowMs()
	changed := 0
	err := s.tx(ctx, func(tx *sql.Tx) error {
		for _, w := range wants {
			var state, wantKey, key string
			var priority int
			var orderKey int64
			err := tx.QueryRowContext(ctx, "SELECT state, want_key, key, priority, order_key FROM previews WHERE item_id = ? AND job = ?",
				w.ItemID, w.Job).Scan(&state, &wantKey, &key, &priority, &orderKey)
			switch {
			case errors.Is(err, sql.ErrNoRows):
				if _, err := tx.ExecContext(ctx, `INSERT INTO previews (item_id, job, state, priority, order_key, want_key, updated_at)
					VALUES (?, ?, 'pending', ?, ?, ?, ?)`, w.ItemID, w.Job, w.Priority, w.OrderKey, w.WantKey, now); err != nil {
					return err
				}
				changed++
			case err != nil:
				return err
			case wantKey != w.WantKey:
				if key == w.WantKey { // back to what is on disk (a recipe or source change undone)
					if _, err := tx.ExecContext(ctx, `UPDATE previews SET state = 'done', want_key = ?, attempts = 0, error = '',
						not_before = 0, updated_at = ? WHERE item_id = ? AND job = ?`, w.WantKey, now, w.ItemID, w.Job); err != nil {
						return err
					}
					continue
				}
				if _, err := tx.ExecContext(ctx, `UPDATE previews SET state = 'pending', want_key = ?, priority = ?, order_key = ?,
					attempts = 0, error = '', not_before = 0, updated_at = ? WHERE item_id = ? AND job = ?`,
					w.WantKey, w.Priority, w.OrderKey, now, w.ItemID, w.Job); err != nil {
					return err
				}
				changed++
			case state == PreviewPending && (w.Priority < priority || w.Priority == priority && w.Priority <= PriorityWarm && w.OrderKey > orderKey):
				if _, err := tx.ExecContext(ctx, `UPDATE previews SET priority = ?, order_key = ?, not_before = 0, updated_at = ?
					WHERE item_id = ? AND job = ?`, w.Priority, w.OrderKey, now, w.ItemID, w.Job); err != nil {
					return err
				}
				changed++
			}
		}
		return nil
	})
	return changed, err
}

// laneWhere selects a worker lane's jobs (?1 = the lane): the edited previews of ADR 0020 ("edit:<version>") run in
// the image lane.
const laneWhere = "(job = ?1 OR (?1 = 'image' AND job >= 'edit:' AND job < 'edit;'))"

// LeasePreview takes the most urgent pending job of the given lane that may run now and marks it running.
func (s *Store) LeasePreview(ctx context.Context, job string) (Preview, bool, error) {
	var p Preview
	found := false
	err := s.tx(ctx, func(tx *sql.Tx) error {
		row := tx.QueryRowContext(ctx, "SELECT "+previewCols+` FROM previews WHERE state = 'pending' AND `+laneWhere+` AND not_before <= ?2
			ORDER BY priority, order_key DESC LIMIT 1`, job, s.nowMs())
		var err error
		p, err = scanPreview(row)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		found = true
		p.State = PreviewRunning
		p.Attempts++
		_, err = tx.ExecContext(ctx, "UPDATE previews SET state = 'running', attempts = attempts + 1, updated_at = ? WHERE item_id = ? AND job = ?",
			s.nowMs(), p.ItemID, p.Job)
		return err
	})
	return p, found, err
}

// NextPreviewAt returns when the earliest delayed pending job of a kind may run (0 when none waits).
func (s *Store) NextPreviewAt(ctx context.Context, job string) (int64, error) {
	var t sql.NullInt64
	err := s.db.QueryRowContext(ctx, "SELECT min(not_before) FROM previews WHERE state = 'pending' AND "+laneWhere, job).Scan(&t)
	return t.Int64, err
}

// FinishPreview records the files a run made for key, and returns the key of the files they replace ("" when none,
// or when another row still uses them). The row is done if key is still what the item wants, else pending again.
func (s *Store) FinishPreview(ctx context.Context, itemID, job, key string, outputs []byte, bytes int64, generator string, ms int64) (string, error) {
	var old string
	err := s.tx(ctx, func(tx *sql.Tx) error {
		var wantKey string
		err := tx.QueryRowContext(ctx, "SELECT want_key, key FROM previews WHERE item_id = ? AND job = ?", itemID, job).Scan(&wantKey, &old)
		if errors.Is(err, sql.ErrNoRows) {
			old = key // the row went (item deleted) while it ran: the new files are unused
			return nil
		}
		if err != nil {
			return err
		}
		state := PreviewDone
		if wantKey != key {
			state = PreviewPending
		}
		if _, err := tx.ExecContext(ctx, `UPDATE previews SET state = ?, key = ?, outputs = ?, bytes = ?, generator = ?, ms = ?,
			error = '', attempts = CASE WHEN ? = 'pending' THEN 0 ELSE attempts END, updated_at = ? WHERE item_id = ? AND job = ?`,
			state, key, string(outputs), bytes, generator, ms, state, s.nowMs(), itemID, job); err != nil {
			return err
		}
		if old == key {
			old = ""
		}
		return nil
	})
	if err != nil || old == "" {
		return "", err
	}
	used, err := s.PreviewKeyUsed(ctx, old)
	if err != nil || used {
		return "", err
	}
	return old, nil
}

// PreviewKeyUsed reports whether any row has files under key.
func (s *Store) PreviewKeyUsed(ctx context.Context, key string) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, "SELECT count(*) FROM previews WHERE key = ?", key).Scan(&n)
	return n > 0, err
}

// RetryPreview puts a running job back in the queue, not before the given time (a source that is offline, a tool
// that failed once).
func (s *Store) RetryPreview(ctx context.Context, itemID, job, errMsg string, notBefore int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE previews SET state = 'pending', error = ?, not_before = ?, updated_at = ?
		WHERE item_id = ? AND job = ? AND state = 'running'`, errMsg, notBefore, s.nowMs(), itemID, job)
	return err
}

// FailPreview marks a job as impossible for its current key.
func (s *Store) FailPreview(ctx context.Context, itemID, job, errMsg string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE previews SET state = 'failed', error = ?, updated_at = ?
		WHERE item_id = ? AND job = ? AND state = 'running'`, errMsg, s.nowMs(), itemID, job)
	return err
}

// SetPreviewWant changes the key a running job makes (the item changed between the lease and the run).
func (s *Store) SetPreviewWant(ctx context.Context, itemID, job, wantKey string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE previews SET want_key = ?, updated_at = ? WHERE item_id = ? AND job = ?", wantKey, s.nowMs(), itemID, job)
	return err
}

// DeletePreviews removes an item's rows (all jobs when job is ""), and returns the keys no row uses any more.
func (s *Store) DeletePreviews(ctx context.Context, itemID, job string) ([]string, error) {
	var keys []string
	err := s.tx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, "SELECT key FROM previews WHERE item_id = ? AND (? = '' OR job = ?) AND key != ''", itemID, job, job)
		if err != nil {
			return err
		}
		var cand []string
		for rows.Next() {
			var k string
			if err := rows.Scan(&k); err != nil {
				rows.Close()
				return err
			}
			cand = append(cand, k)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM previews WHERE item_id = ? AND (? = '' OR job = ?)", itemID, job, job); err != nil {
			return err
		}
		for _, k := range cand {
			var n int
			if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM previews WHERE key = ?", k).Scan(&n); err != nil {
				return err
			}
			if n == 0 {
				keys = append(keys, k)
			}
		}
		return nil
	})
	return keys, err
}

// TouchPreview marks a job's files as just served (the full-resolution cache keeps the most recently used).
func (s *Store) TouchPreview(ctx context.Context, itemID, job string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE previews SET updated_at = ? WHERE item_id = ? AND job = ? AND key != ''", s.nowMs(), itemID, job)
	return err
}

// PreviewsByAge returns a job's rows that have files, least recently made or served first.
func (s *Store) PreviewsByAge(ctx context.Context, job string) ([]Preview, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+previewCols+" FROM previews WHERE job = ? AND key != '' ORDER BY updated_at, item_id", job)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Preview
	for rows.Next() {
		p, err := scanPreview(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// ResetRunningPreviews puts jobs a stopped server was running back in the queue (the queue resumes after a restart).
func (s *Store) ResetRunningPreviews(ctx context.Context) (int64, error) {
	res, err := s.db.ExecContext(ctx, "UPDATE previews SET state = 'pending' WHERE state = 'running'")
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// PreviewKeys returns every key with files on disk (for sweeping files no row names).
func (s *Store) PreviewKeys(ctx context.Context) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT DISTINCT key FROM previews WHERE key != ''")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		out[k] = true
	}
	return out, rows.Err()
}

// PreviewStat is one line of the previews report: rows of a job in a state, and the bytes of their files.
type PreviewStat struct {
	Job   string `json:"job"`
	State string `json:"state"`
	Count int    `json:"count"`
	Bytes int64  `json:"bytes"`
	Stale int    `json:"stale"` // rows serving files of an older key
}

// PreviewStats reports the queue and the storage (files shared by several rows are counted once per row).
func (s *Store) PreviewStats(ctx context.Context) ([]PreviewStat, error) {
	// Edited previews (ADR 0020, one job per version) are counted together as "edit".
	rows, err := s.db.QueryContext(ctx, `SELECT CASE WHEN job >= 'edit:' AND job < 'edit;' THEN 'edit' ELSE job END AS j, state, count(*),
		coalesce(sum(bytes), 0), coalesce(sum(CASE WHEN key != '' AND key != want_key THEN 1 ELSE 0 END), 0) FROM previews
		GROUP BY j, state ORDER BY j, state`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PreviewStat
	for rows.Next() {
		var p PreviewStat
		if err := rows.Scan(&p.Job, &p.State, &p.Count, &p.Bytes, &p.Stale); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// PreviewErrors returns the most common reasons of failed jobs.
func (s *Store) PreviewErrors(ctx context.Context, limit int) (map[string]int, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT error, count(*) FROM previews WHERE state = 'failed' GROUP BY error ORDER BY count(*) DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var e string
		var n int
		if err := rows.Scan(&e, &n); err != nil {
			return nil, err
		}
		out[e] = n
	}
	return out, rows.Err()
}
