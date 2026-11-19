package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
)

// Video compression (ADR 0025): jobs, their items (the queue and the record, with the replace and restore journals)
// and the per-fingerprint probe.

// Compression item states.
const (
	CompressQueued     = "queued"
	CompressEstimating = "estimating"
	CompressSkipped    = "skipped"
	CompressEncoding   = "encoding"
	CompressVerifying  = "verifying"
	CompressFailed     = "failed"
	CompressReady      = "ready"
	CompressReplacing  = "replacing"
	CompressReplaced   = "replaced"
	CompressRestored   = "restored"
	CompressPurged     = "purged"
	CompressCancelled  = "cancelled"
)

// Compression job states.
const (
	JobActive    = "active"
	JobPaused    = "paused"
	JobCancelled = "cancelled"
	JobDone      = "done"
)

// ErrConflict is returned when a row is not in a state the operation accepts.
var ErrConflict = errors.New("conflict")

// CompressJob is one row of compress_jobs.
type CompressJob struct {
	ID         string
	Target     string
	PhysicalID string
	CreatedBy  string
	Name       string
	Selection  json.RawMessage
	Profile    string
	Options    json.RawMessage
	Auto       bool
	Priority   int
	State      string
	CreatedAt  int64
	FinishedAt *int64
	RequestID  string
}

// CompressItem is one row of compress_items.
type CompressItem struct {
	ID             int64
	JobID          string
	ItemID         string
	Fingerprint    string
	SHA256         string
	Size           int64
	Source         string // nas | blob
	FolderID       string
	RelPath        string
	MirrorEntryID  *int64
	State          string
	Reason         string
	Priority       int
	OrderKey       int64
	NotBefore      int64
	Attempts       int
	EstSize        *int64
	EstMs          *int64
	DurationMs     *int64
	Q              *int
	OutFingerprint string
	OutSHA256      string
	OutSize        *int64
	VmafMean       *float64
	VmafLow        *float64
	VmafMode       string
	EncodeMs       *int64
	VerifyMs       *int64
	Decoder        string
	MetaSidecar    bool
	ReplaceStep    string
	TrashPath      string
	ReplacedAt     *int64
	TrashedAt      *int64
	PurgeAfter     *int64
	PurgedAt       *int64
	RestoredAt     *int64
	StalledAt      *int64 // a journal waiting since (nil: not waiting)
	UpdatedAt      int64
}

const compressJobCols = "id, target, coalesce(physical_id, ''), created_by, name, selection, profile, options, auto, priority, state, created_at, finished_at, coalesce(request_id, '')"

func scanCompressJob(row interface{ Scan(...any) error }) (CompressJob, error) {
	var j CompressJob
	var sel, opt string
	err := row.Scan(&j.ID, &j.Target, &j.PhysicalID, &j.CreatedBy, &j.Name, &sel, &j.Profile, &opt, &j.Auto, &j.Priority, &j.State,
		&j.CreatedAt, &j.FinishedAt, &j.RequestID)
	if errors.Is(err, sql.ErrNoRows) {
		return j, ErrNotFound
	}
	j.Selection, j.Options = json.RawMessage(sel), json.RawMessage(opt)
	return j, err
}

const compressItemCols = `id, job_id, item_id, fingerprint, coalesce(sha256, ''), size, source, coalesce(folder_id, ''), coalesce(rel_path, ''),
	mirror_entry_id, state, reason, priority, order_key, not_before, attempts, est_size, est_ms, duration_ms, q,
	coalesce(out_fingerprint, ''), coalesce(out_sha256, ''), out_size, vmaf_mean, vmaf_low, coalesce(vmaf_mode, ''), encode_ms, verify_ms,
	coalesce(decoder, ''), meta_sidecar, replace_step, coalesce(trash_path, ''), replaced_at, trashed_at, purge_after, purged_at,
	restored_at, stalled_at, updated_at`

func scanCompressItem(row interface{ Scan(...any) error }) (CompressItem, error) {
	var it CompressItem
	err := row.Scan(&it.ID, &it.JobID, &it.ItemID, &it.Fingerprint, &it.SHA256, &it.Size, &it.Source, &it.FolderID, &it.RelPath,
		&it.MirrorEntryID, &it.State, &it.Reason, &it.Priority, &it.OrderKey, &it.NotBefore, &it.Attempts, &it.EstSize, &it.EstMs,
		&it.DurationMs, &it.Q, &it.OutFingerprint, &it.OutSHA256, &it.OutSize, &it.VmafMean, &it.VmafLow, &it.VmafMode, &it.EncodeMs,
		&it.VerifyMs, &it.Decoder, &it.MetaSidecar, &it.ReplaceStep, &it.TrashPath, &it.ReplacedAt, &it.TrashedAt, &it.PurgeAfter,
		&it.PurgedAt, &it.RestoredAt, &it.StalledAt, &it.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return it, ErrNotFound
	}
	return it, err
}

func scanCompressItems(rows *sql.Rows) ([]CompressItem, error) {
	defer rows.Close()
	out := []CompressItem{}
	for rows.Next() {
		it, err := scanCompressItem(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// ActiveCompressStates are the states in which a fingerprint is held by an unfinished (or not yet purged) item: the
// partial unique index compress_items_active.
func compressHeld(state string) bool {
	switch state {
	case CompressSkipped, CompressFailed, CompressCancelled, CompressPurged:
		return false
	}
	return true
}

// CreateCompressJob stores a job and its items in one transaction. An item whose fingerprint another unfinished item
// holds is not inserted: its fingerprint is returned in busy. With a request id that exists, the existing job is
// returned with existed true and nothing is written.
func (s *Store) CreateCompressJob(ctx context.Context, j CompressJob, items []CompressItem) (CompressJob, []string, bool, error) {
	var busy []string
	existed := false
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		if j.RequestID != "" {
			old, err := scanCompressJob(tx.QueryRowContext(ctx, "SELECT "+compressJobCols+" FROM compress_jobs WHERE request_id = ?", j.RequestID))
			if err == nil {
				j, existed = old, true
				return nil
			}
			if !errors.Is(err, ErrNotFound) {
				return err
			}
		}
		if j.Auto {
			var n int
			if err := tx.QueryRowContext(ctx, "SELECT count(*) FROM compress_jobs WHERE target = ? AND selection = ? AND auto = 1 AND state != 'cancelled'",
				j.Target, string(j.Selection)).Scan(&n); err != nil {
				return err
			}
			if n > 0 {
				return ErrConflict
			}
		}
		j.CreatedAt = now
		if _, err := tx.ExecContext(ctx, `INSERT INTO compress_jobs (id, target, physical_id, created_by, name, selection, profile, options, auto,
			priority, state, created_at, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, j.ID, j.Target, nullString(j.PhysicalID),
			j.CreatedBy, j.Name, string(j.Selection), j.Profile, string(j.Options), j.Auto, j.Priority, j.State, j.CreatedAt,
			nullString(j.RequestID)); err != nil {
			return err
		}
		var err error
		busy, err = insertCompressItems(ctx, tx, j, items, now)
		return err
	})
	return j, busy, existed, err
}

func insertCompressItems(ctx context.Context, tx *sql.Tx, j CompressJob, items []CompressItem, now int64) ([]string, error) {
	var busy []string
	for _, it := range items {
		if compressHeld(it.State) {
			var other int64
			err := tx.QueryRowContext(ctx, `SELECT id FROM compress_items WHERE fingerprint = ?
				AND state NOT IN ('skipped', 'failed', 'cancelled', 'purged') LIMIT 1`, it.Fingerprint).Scan(&other)
			if err == nil {
				busy = append(busy, it.Fingerprint)
				continue
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return nil, err
			}
		}
		if it.State == "" {
			it.State = CompressQueued
		}
		if it.Priority == 0 {
			it.Priority = j.Priority
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO compress_items (job_id, item_id, fingerprint, size, source, folder_id, rel_path,
			mirror_entry_id, state, reason, priority, order_key, est_size, est_ms, duration_ms, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, j.ID, it.ItemID, it.Fingerprint, it.Size, it.Source,
			nullString(it.FolderID), nullString(it.RelPath), it.MirrorEntryID, it.State, it.Reason, it.Priority, it.OrderKey,
			it.EstSize, it.EstMs, it.DurationMs, now); err != nil {
			return nil, err
		}
	}
	return busy, nil
}

// AppendCompressItems adds items to an existing job (a folder compressed automatically). Fingerprints already held
// are skipped and returned.
func (s *Store) AppendCompressItems(ctx context.Context, jobID string, items []CompressItem) ([]string, error) {
	var busy []string
	err := s.tx(ctx, func(tx *sql.Tx) error {
		j, err := scanCompressJob(tx.QueryRowContext(ctx, "SELECT "+compressJobCols+" FROM compress_jobs WHERE id = ?", jobID))
		if err != nil {
			return err
		}
		busy, err = insertCompressItems(ctx, tx, j, items, s.nowMs())
		return err
	})
	return busy, err
}

// CompressJob returns one job.
func (s *Store) CompressJob(ctx context.Context, id string) (CompressJob, error) {
	return scanCompressJob(s.db.QueryRowContext(ctx, "SELECT "+compressJobCols+" FROM compress_jobs WHERE id = ?", id))
}

// CompressJobByRequest returns the job made for a client's idempotency key.
func (s *Store) CompressJobByRequest(ctx context.Context, requestID string) (CompressJob, error) {
	return scanCompressJob(s.db.QueryRowContext(ctx, "SELECT "+compressJobCols+" FROM compress_jobs WHERE request_id = ?", requestID))
}

// CompressJobQuery filters GET /v1/compress/jobs.
type CompressJobQuery struct {
	State  string
	Auto   *bool
	Before string // cursor: list jobs created before this job (newest first)
	Limit  int
}

// CompressJobs lists jobs, newest first.
func (s *Store) CompressJobs(ctx context.Context, q CompressJobQuery) ([]CompressJob, bool, error) {
	where := []string{"1 = 1"}
	var args []any
	if q.State != "" {
		where = append(where, "state = ?")
		args = append(args, q.State)
	}
	if q.Auto != nil {
		where = append(where, "auto = ?")
		args = append(args, *q.Auto)
	}
	if q.Before != "" {
		where = append(where, "(created_at, id) < (SELECT created_at, id FROM compress_jobs WHERE id = ?)")
		args = append(args, q.Before)
	}
	if q.Limit <= 0 {
		q.Limit = 100
	}
	args = append(args, q.Limit+1)
	//nolint:gosec // fixed clauses and placeholders only
	rows, err := s.db.QueryContext(ctx, "SELECT "+compressJobCols+" FROM compress_jobs WHERE "+strings.Join(where, " AND ")+
		" ORDER BY created_at DESC, id DESC LIMIT ?", args...)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	out := []CompressJob{}
	for rows.Next() {
		j, err := scanCompressJob(rows)
		if err != nil {
			return nil, false, err
		}
		out = append(out, j)
	}
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	more := len(out) > q.Limit
	if more {
		out = out[:q.Limit]
	}
	return out, more, nil
}

// AutoCompressJobs lists the active (or paused) standing jobs of a target.
func (s *Store) AutoCompressJobs(ctx context.Context, target string) ([]CompressJob, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+compressJobCols+" FROM compress_jobs WHERE auto = 1 AND target = ? AND state IN ('active', 'paused') ORDER BY created_at",
		target)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CompressJob
	for rows.Next() {
		j, err := scanCompressJob(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, j)
	}
	return out, rows.Err()
}

// SetCompressJob updates a job's state, priority (0: unchanged) and, with auto false, turns a standing job into a
// finished one (its switch is off).
func (s *Store) SetCompressJob(ctx context.Context, id, state string, priority int, autoOff bool) error {
	return s.tx(ctx, func(tx *sql.Tx) error {
		j, err := scanCompressJob(tx.QueryRowContext(ctx, "SELECT "+compressJobCols+" FROM compress_jobs WHERE id = ?", id))
		if err != nil {
			return err
		}
		if state != "" && state != j.State {
			var fin any
			if state == JobCancelled || state == JobDone {
				fin = s.nowMs()
			}
			if _, err := tx.ExecContext(ctx, "UPDATE compress_jobs SET state = ?, finished_at = ? WHERE id = ?", state, fin, id); err != nil {
				return err
			}
		}
		if priority > 0 && priority != j.Priority {
			if _, err := tx.ExecContext(ctx, "UPDATE compress_jobs SET priority = ? WHERE id = ?", priority, id); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "UPDATE compress_items SET priority = ?, updated_at = ? WHERE job_id = ? AND state = 'queued' AND priority != 0",
				priority, s.nowMs(), id); err != nil {
				return err
			}
		}
		if autoOff && j.Auto {
			if _, err := tx.ExecContext(ctx, "UPDATE compress_jobs SET state = 'cancelled', finished_at = ? WHERE id = ?", s.nowMs(), id); err != nil {
				return err
			}
		}
		return nil
	})
}

// FinishCompressJobs marks on-demand jobs done once none of their items is unsettled (queued to ready, replacing).
func (s *Store) FinishCompressJobs(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `UPDATE compress_jobs SET state = 'done', finished_at = ? WHERE auto = 0 AND state IN ('active', 'paused')
		AND NOT EXISTS (SELECT 1 FROM compress_items i WHERE i.job_id = compress_jobs.id
			AND i.state IN ('queued', 'estimating', 'encoding', 'verifying', 'ready', 'replacing'))`, s.nowMs())
	return err
}

// CompressJobCount is one state's count and bytes in a job.
type CompressJobCount struct {
	State   string
	Reason  string
	Items   int
	Size    int64 // originals
	OutSize int64
	EstSize int64
	EstMs   int64
}

// CompressJobCounts returns per job, per state and reason, the counts and sizes.
func (s *Store) CompressJobCounts(ctx context.Context, ids []string) (map[string][]CompressJobCount, error) {
	out := map[string][]CompressJobCount{}
	for start := 0; start < len(ids); start += 500 {
		chunk := ids[start:min(start+500, len(ids))]
		args := make([]any, len(chunk))
		for i, id := range chunk {
			args[i] = id
		}
		//nolint:gosec // placeholders only
		rows, err := s.db.QueryContext(ctx, `SELECT job_id, state, reason, count(*), sum(size), coalesce(sum(out_size), 0), coalesce(sum(est_size), 0),
			coalesce(sum(est_ms), 0) FROM compress_items WHERE job_id IN (`+placeholders(len(chunk))+`) GROUP BY job_id, state, reason`, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var id string
			var c CompressJobCount
			if err := rows.Scan(&id, &c.State, &c.Reason, &c.Items, &c.Size, &c.OutSize, &c.EstSize, &c.EstMs); err != nil {
				rows.Close()
				return nil, err
			}
			out[id] = append(out[id], c)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// CompressItem returns one item.
func (s *Store) CompressItem(ctx context.Context, id int64) (CompressItem, error) {
	return scanCompressItem(s.db.QueryRowContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE id = ?", id))
}

// CompressItemQuery filters GET /v1/compress/items.
type CompressItemQuery struct {
	JobID  string
	States []string
	// PhysicalID: only the phone items of this physical device (mine=true).
	PhysicalID string
	After      int64 // cursor: id
	Limit      int
}

// CompressItems lists items by id.
func (s *Store) CompressItems(ctx context.Context, q CompressItemQuery) ([]CompressItem, bool, error) {
	where := []string{"id > ?"}
	args := []any{q.After}
	if q.JobID != "" {
		where = append(where, "job_id = ?")
		args = append(args, q.JobID)
	}
	if len(q.States) > 0 {
		where = append(where, "state IN ("+placeholders(len(q.States))+")")
		for _, st := range q.States {
			args = append(args, st)
		}
	}
	if q.PhysicalID != "" {
		where = append(where, "job_id IN (SELECT id FROM compress_jobs WHERE target = 'phone' AND physical_id = ?)")
		args = append(args, q.PhysicalID)
	}
	if q.Limit <= 0 {
		q.Limit = 200
	}
	args = append(args, q.Limit+1)
	//nolint:gosec // fixed clauses and placeholders only
	rows, err := s.db.QueryContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE "+strings.Join(where, " AND ")+" ORDER BY id LIMIT ?", args...)
	if err != nil {
		return nil, false, err
	}
	out, err := scanCompressItems(rows)
	if err != nil {
		return nil, false, err
	}
	more := len(out) > q.Limit
	if more {
		out = out[:q.Limit]
	}
	return out, more, nil
}

// CompressItemsIn lists the items in the given states (all jobs), oldest first.
func (s *Store) CompressItemsIn(ctx context.Context, states ...string) ([]CompressItem, error) {
	args := make([]any, len(states))
	for i, st := range states {
		args[i] = st
	}
	//nolint:gosec // placeholders only
	rows, err := s.db.QueryContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE state IN ("+placeholders(len(states))+") ORDER BY id", args...)
	if err != nil {
		return nil, err
	}
	return scanCompressItems(rows)
}

// CompressItemsByFingerprint returns the items whose original or output is one of fps (newest first per fingerprint).
func (s *Store) CompressItemsByFingerprint(ctx context.Context, fps []string) ([]CompressItem, error) {
	var out []CompressItem
	for start := 0; start < len(fps); start += 400 {
		chunk := fps[start:min(start+400, len(fps))]
		args := make([]any, 0, 2*len(chunk))
		for _, fp := range chunk {
			args = append(args, fp)
		}
		for _, fp := range chunk {
			args = append(args, fp)
		}
		//nolint:gosec // placeholders only
		rows, err := s.db.QueryContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE fingerprint IN ("+placeholders(len(chunk))+
			") OR out_fingerprint IN ("+placeholders(len(chunk))+") ORDER BY id DESC", args...)
		if err != nil {
			return nil, err
		}
		list, err := scanCompressItems(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, list...)
	}
	return out, nil
}

// CompressHeldFingerprints returns which of fps an unfinished (or not purged) item holds as its original, or any item
// produced as its output.
func (s *Store) CompressHeldFingerprints(ctx context.Context, fps []string) (held, outputs map[string]bool, err error) {
	held, outputs = map[string]bool{}, map[string]bool{}
	list, err := s.CompressItemsByFingerprint(ctx, fps)
	if err != nil {
		return nil, nil, err
	}
	want := map[string]bool{}
	for _, fp := range fps {
		want[fp] = true
	}
	for _, it := range list {
		if want[it.Fingerprint] && compressHeld(it.State) {
			held[it.Fingerprint] = true
		}
		if it.OutFingerprint != "" && want[it.OutFingerprint] {
			outputs[it.OutFingerprint] = true
		}
	}
	return held, outputs, nil
}

// LeaseCompressItem takes the most urgent queued item of an active job (priority, then newest shot, then id) whose
// not_before has passed, and marks it estimating. maxPriority bounds the priorities taken (3: every item; 2: no
// automatic one, outside the night window). ok is false when there is none.
func (s *Store) LeaseCompressItem(ctx context.Context, maxPriority int) (CompressItem, bool, error) {
	var it CompressItem
	ok := false
	now := s.nowMs()
	err := s.tx(ctx, func(tx *sql.Tx) error {
		row := tx.QueryRowContext(ctx, `SELECT `+compressItemCols+` FROM compress_items i WHERE state = 'queued' AND priority <= ? AND not_before <= ?
			AND EXISTS (SELECT 1 FROM compress_jobs j WHERE j.id = i.job_id AND j.state = 'active')
			ORDER BY priority, order_key DESC, id LIMIT 1`, maxPriority, now)
		var err error
		it, err = scanCompressItem(row)
		if errors.Is(err, ErrNotFound) {
			return nil
		}
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE compress_items SET state = 'estimating', reason = '', updated_at = ? WHERE id = ?", now, it.ID); err != nil {
			return err
		}
		it.State, it.Reason, it.UpdatedAt, ok = CompressEstimating, "", now, true
		return nil
	})
	return it, ok, err
}

// SaveCompressItem writes every mutable column of an item when its stored state is one of from (none: any). ok is
// false when the state moved on meanwhile (a cancel): nothing is written.
func (s *Store) SaveCompressItem(ctx context.Context, it CompressItem, from ...string) (bool, error) {
	it.UpdatedAt = s.nowMs()
	return saveCompressItem(ctx, s.db, it, from...)
}

type sqlExecer interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
}

func saveCompressItem(ctx context.Context, db sqlExecer, it CompressItem, from ...string) (bool, error) {
	q := `UPDATE compress_items SET sha256 = ?, size = ?, state = ?, reason = ?, priority = ?, not_before = ?, attempts = ?, est_size = ?,
		est_ms = ?, duration_ms = ?, q = ?, out_fingerprint = ?, out_sha256 = ?, out_size = ?, vmaf_mean = ?, vmaf_low = ?, vmaf_mode = ?,
		encode_ms = ?, verify_ms = ?, decoder = ?, meta_sidecar = ?, replace_step = ?, trash_path = ?, replaced_at = ?, trashed_at = ?,
		purge_after = ?, purged_at = ?, restored_at = ?, stalled_at = ?, updated_at = ? WHERE id = ?`
	args := []any{nullString(it.SHA256), it.Size, it.State, it.Reason, it.Priority, it.NotBefore, it.Attempts, it.EstSize, it.EstMs,
		it.DurationMs, it.Q, nullString(it.OutFingerprint), nullString(it.OutSHA256), it.OutSize, it.VmafMean, it.VmafLow,
		nullString(it.VmafMode), it.EncodeMs, it.VerifyMs, nullString(it.Decoder), it.MetaSidecar, it.ReplaceStep, nullString(it.TrashPath),
		it.ReplacedAt, it.TrashedAt, it.PurgeAfter, it.PurgedAt, it.RestoredAt, it.StalledAt, it.UpdatedAt, it.ID}
	if len(from) > 0 {
		q += " AND state IN (" + placeholders(len(from)) + ")" //nolint:gosec // placeholders only
		for _, f := range from {
			args = append(args, f)
		}
	}
	res, err := db.ExecContext(ctx, q, args...)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n == 1, err
}

// ResetRunningCompress puts items a restart interrupted back in the queue (attempts unchanged) and returns them.
func (s *Store) ResetRunningCompress(ctx context.Context) ([]CompressItem, error) {
	list, err := s.CompressItemsIn(ctx, CompressEstimating, CompressEncoding, CompressVerifying)
	if err != nil || len(list) == 0 {
		return list, err
	}
	_, err = s.db.ExecContext(ctx, "UPDATE compress_items SET state = 'queued', updated_at = ? WHERE state IN ('estimating', 'encoding', 'verifying')", s.nowMs())
	return list, err
}

// CancelCompressItems cancels the given items (or every item of a job) that have not reached replacing: queued to
// ready. It returns the items whose state it changed and, for the others, their state.
func (s *Store) CancelCompressItems(ctx context.Context, jobID string, ids []int64) (cancelled []CompressItem, others []CompressItem, err error) {
	err = s.tx(ctx, func(tx *sql.Tx) error {
		var rows *sql.Rows
		var err error
		switch {
		case jobID != "":
			rows, err = tx.QueryContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE job_id = ? ORDER BY id", jobID)
		case len(ids) == 0:
			return nil
		default:
			args := make([]any, len(ids))
			for i, id := range ids {
				args[i] = id
			}
			//nolint:gosec // placeholders only
			rows, err = tx.QueryContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE id IN ("+placeholders(len(ids))+") ORDER BY id", args...)
		}
		if err != nil {
			return err
		}
		list, err := scanCompressItems(rows)
		if err != nil {
			return err
		}
		now := s.nowMs()
		for _, it := range list {
			switch it.State {
			case CompressQueued, CompressEstimating, CompressEncoding, CompressVerifying, CompressReady:
				if _, err := tx.ExecContext(ctx, "UPDATE compress_items SET state = 'cancelled', updated_at = ? WHERE id = ?", now, it.ID); err != nil {
					return err
				}
				it.State = CompressCancelled
				cancelled = append(cancelled, it)
			default:
				others = append(others, it)
			}
		}
		return nil
	})
	return cancelled, others, err
}

// CompressPurgeDue lists replaced and restored items whose trashed file is due for the purge.
func (s *Store) CompressPurgeDue(ctx context.Context, now int64) ([]CompressItem, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+compressItemCols+" FROM compress_items WHERE state IN ('replaced', 'restored') AND purge_after <= ? ORDER BY purge_after",
		now)
	if err != nil {
		return nil, err
	}
	return scanCompressItems(rows)
}

// CompressStatsRow is the accounting of the admin page and the Compression view.
type CompressStatsRow struct {
	State   string
	Reason  string
	Items   int
	Size    int64
	OutSize int64
	Oldest  *int64 // trashed_at of the oldest in the trash
	EncMs   int64
}

// CompressStats counts every item by state and reason.
func (s *Store) CompressStats(ctx context.Context) ([]CompressStatsRow, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT state, reason, count(*), sum(size), coalesce(sum(out_size), 0), min(trashed_at), coalesce(sum(encode_ms), 0)
		FROM compress_items GROUP BY state, reason ORDER BY state, reason`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CompressStatsRow
	for rows.Next() {
		var r CompressStatsRow
		if err := rows.Scan(&r.State, &r.Reason, &r.Items, &r.Size, &r.OutSize, &r.Oldest, &r.EncMs); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// --- video_probe -------------------------------------------------------------------------------------------------

// VideoProbes returns the stored probes (JSON) of the given fingerprints made with the given version.
func (s *Store) VideoProbes(ctx context.Context, version int, fps []string) (map[string][]byte, error) {
	out := map[string][]byte{}
	for start := 0; start < len(fps); start += 500 {
		chunk := fps[start:min(start+500, len(fps))]
		args := make([]any, 0, len(chunk)+1)
		args = append(args, version)
		for _, fp := range chunk {
			args = append(args, fp)
		}
		//nolint:gosec // placeholders only
		rows, err := s.db.QueryContext(ctx, "SELECT fingerprint, data FROM video_probe WHERE version = ? AND fingerprint IN ("+placeholders(len(chunk))+")", args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var fp, d string
			if err := rows.Scan(&fp, &d); err != nil {
				rows.Close()
				return nil, err
			}
			out[fp] = []byte(d)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// PutVideoProbe stores one probe.
func (s *Store) PutVideoProbe(ctx context.Context, fp string, version int, data []byte) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO video_probe (fingerprint, version, data) VALUES (?, ?, ?)
		ON CONFLICT (fingerprint) DO UPDATE SET version = excluded.version, data = excluded.data`, fp, version, string(data))
	return err
}

// --- what the compression needs of the library ----------------------------------------------------------------------

// NasVideoFile is a live (not trashed) NAS video file of a folder with its item.
type NasVideoFile struct {
	FolderID    string
	RelPath     string
	Size        int64
	MTime       int64 // ns
	Fingerprint string
	ItemID      string
	SortTime    int64
	Kind        string
	CreatedAt   int64 // the item's first appearance (ms)
}

// NasVideoFiles lists the live NAS files with a fingerprint whose live item is a video, in a folder (all folders: "")
// under dir ("" = the whole folder; recursive false: dir itself only), or of the given items.
func (s *Store) NasVideoFiles(ctx context.Context, folderID, dir string, recursive bool, itemIDs []string) ([]NasVideoFile, error) {
	where := []string{"n.removed = 0", "n.origin_path IS NULL", "n.trash_path IS NULL", `n.rel_path NOT LIKE '.hoard-%'`,
		`n.rel_path NOT LIKE '%/.hoard-%'`, "n.fingerprint IS NOT NULL", "n.media = 'video'", "d.enabled = 1",
		"i.deleted = 0", "i.trashed_at IS NULL"}
	var args []any
	if folderID != "" {
		where = append(where, "n.folder_id = ?")
		args = append(args, folderID)
	}
	if dir != "" {
		if recursive {
			where = append(where, `n.rel_path LIKE ? ESCAPE '\'`)
			args = append(args, likeEscape(dir)+"/%")
		} else {
			where = append(where, `n.rel_path LIKE ? ESCAPE '\' AND n.rel_path NOT LIKE ? ESCAPE '\'`)
			args = append(args, likeEscape(dir)+"/%", likeEscape(dir)+"/%/%")
		}
	} else if !recursive && folderID != "" {
		where = append(where, "n.rel_path NOT LIKE '%/%'")
	}
	if len(itemIDs) > 0 {
		where = append(where, "f.item_id IN ("+placeholders(len(itemIDs))+")")
		for _, id := range itemIDs {
			args = append(args, id)
		}
	}
	//nolint:gosec // fixed clauses and placeholders only
	rows, err := s.db.QueryContext(ctx, `SELECT n.folder_id, n.rel_path, n.size, n.mtime, n.fingerprint, f.item_id, i.sort_time, i.kind, i.created_at
		FROM nas_files n JOIN nas_folders d ON d.id = n.folder_id JOIN item_files f ON f.fingerprint = n.fingerprint
		JOIN items i ON i.id = f.item_id WHERE `+strings.Join(where, " AND ")+` ORDER BY i.sort_time DESC, n.folder_id, n.rel_path`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []NasVideoFile
	for rows.Next() {
		var v NasVideoFile
		if err := rows.Scan(&v.FolderID, &v.RelPath, &v.Size, &v.MTime, &v.Fingerprint, &v.ItemID, &v.SortTime, &v.Kind, &v.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// NasFileAt returns a live NAS file of a folder by path ("" fingerprint when it was never read).
func (s *Store) NasFileAt(ctx context.Context, folderID, rel string) (NasFileState, error) {
	var st NasFileState
	err := s.db.QueryRowContext(ctx, `SELECT id, size, mtime, coalesce(fingerprint, ''), removed, media, coalesce(origin_path, ''), coalesce(trash_path, '')
		FROM nas_files WHERE folder_id = ? AND rel_path = ?`, folderID, rel).Scan(&st.ID, &st.Size, &st.MTime, &st.Fingerprint, &st.Removed,
		&st.Media, &st.Origin, &st.TrashPath)
	if errors.Is(err, sql.ErrNoRows) {
		return st, ErrNotFound
	}
	return st, err
}

// LiveNasCopy reports whether a live (not trashed) indexed NAS file with this fingerprint exists other than the given
// folder and path (a purge's last check that the trashed file is not the only copy).
func (s *Store) LiveNasCopy(ctx context.Context, fp, folderID, rel string) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM nas_files f JOIN nas_folders d ON d.id = f.folder_id
		WHERE f.fingerprint = ? AND f.removed = 0 AND f.origin_path IS NULL AND f.trash_path IS NULL
		AND f.rel_path NOT LIKE '.hoard-%' AND f.rel_path NOT LIKE '%/.hoard-%' AND d.enabled = 1 AND NOT (f.folder_id = ? AND f.rel_path = ?)`,
		fp, folderID, rel).Scan(&n)
	return n > 0, err
}

// --- the heir rule (ItemTx) ---------------------------------------------------------------------------------------

// CompressLink is a compression record as the items indexer sees it.
type CompressLink struct {
	ID             int64
	ItemID         string
	Fingerprint    string
	OutFingerprint string
	State          string
	Profile        string
	Q              int
	Size           int64
	MetaSidecar    bool
	ReplacedAt     int64
}

// CompressLinks returns the records whose original or output is fp and that link two items: ready, replacing,
// replaced, restored or purged; newest first.
func (t *ItemTx) CompressLinks(fp string) ([]CompressLink, error) {
	rows, err := t.tx.QueryContext(t.ctx, `SELECT c.id, c.item_id, c.fingerprint, c.out_fingerprint, c.state, j.profile, coalesce(c.q, 0), c.size,
		c.meta_sidecar, coalesce(c.replaced_at, 0) FROM compress_items c JOIN compress_jobs j ON j.id = c.job_id
		WHERE (c.fingerprint = ?1 OR c.out_fingerprint = ?1) AND c.out_fingerprint IS NOT NULL
		AND c.state IN ('ready', 'replacing', 'replaced', 'restored', 'purged') ORDER BY c.updated_at DESC, c.id DESC`, fp)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CompressLink
	for rows.Next() {
		var l CompressLink
		if err := rows.Scan(&l.ID, &l.ItemID, &l.Fingerprint, &l.OutFingerprint, &l.State, &l.Profile, &l.Q, &l.Size, &l.MetaSidecar,
			&l.ReplacedAt); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// ItemReplacedBy returns the replacedBy a stored item names ("" for none or no item).
func (t *ItemTx) ItemReplacedBy(id string) (string, error) {
	var data string
	err := t.tx.QueryRowContext(t.ctx, "SELECT data FROM items WHERE id = ?", id).Scan(&data)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	var d struct {
		ReplacedBy string `json:"replacedBy"`
	}
	_ = json.Unmarshal([]byte(data), &d)
	return d.ReplacedBy, nil
}

// MoveEdits moves an item's edits to the item that replaced it (ADR 0020 "Lifecycle", as for a tombstone).
func (t *ItemTx) MoveEdits(from, to string) error {
	tomb, _ := json.Marshal(map[string]string{"replacedBy": to})
	return editsOfTombstone(t.ctx, t.tx, from, tomb, t.now)
}

// TouchCulling sends an item's culling record again (it now names the item that replaced it).
func (t *ItemTx) TouchCulling(id string) error { return touchCulling(t.ctx, t.tx, id) }

// ItemMainFingerprint returns an item's main fingerprint ("" when unknown).
func (s *Store) ItemMainFingerprint(ctx context.Context, id string) (string, error) {
	var fp string
	err := s.db.QueryRowContext(ctx, "SELECT main_fp FROM items WHERE id = ?", id).Scan(&fp)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return fp, err
}

// SetCompressItemsPriority sets the priority of a job's queued items.
func (s *Store) SetCompressItemsPriority(ctx context.Context, jobID string, priority int) error {
	_, err := s.db.ExecContext(ctx, "UPDATE compress_items SET priority = ?, updated_at = ? WHERE job_id = ? AND state = 'queued'", priority, s.nowMs(), jobID)
	return err
}

// CompressAttention is what needs the owner's eye: journals waiting (count, oldest) and originals a failed replace left
// in a trash untracked (another file took the path meanwhile).
type CompressAttention struct {
	Stalled        int    `json:"stalled"`
	StalledSince   *int64 `json:"stalledSince"`
	UntrackedTrash int    `json:"untrackedTrash"`
}

// CompressAttentionNow counts stalled journals and untracked trashed originals.
func (s *Store) CompressAttentionNow(ctx context.Context) (CompressAttention, error) {
	var a CompressAttention
	err := s.db.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM compress_items WHERE state = 'replacing' AND stalled_at IS NOT NULL),
		(SELECT min(stalled_at) FROM compress_items WHERE state = 'replacing' AND stalled_at IS NOT NULL),
		(SELECT count(*) FROM compress_items WHERE state = 'failed' AND trash_path IS NOT NULL)`).Scan(&a.Stalled, &a.StalledSince, &a.UntrackedTrash)
	return a, err
}

// HeldTrash lists, per folder, the trash paths an unfinished compression journal holds (its original or, during a
// restore, the output it moved): Hoard's Empty and auto-empty must leave them, and so does the server's purge.
func (t *ItemTx) HeldTrash(folderID string) (map[string]bool, error) {
	rows, err := t.tx.QueryContext(t.ctx, "SELECT coalesce(trash_path, ''), replace_step FROM compress_items WHERE state = 'replacing' AND folder_id = ?", folderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var p, step string
		if err := rows.Scan(&p, &step); err != nil {
			return nil, err
		}
		if p != "" {
			out[p] = true
		}
		// restore:trash:<path>, restore:swap:<path>, reswap:…: the file the restore moved into the trash.
		if parts := strings.SplitN(step, ":", 3); len(parts) == 3 && parts[2] != "" && (parts[0] == "restore" || parts[0] == "reswap") {
			out[parts[2]] = true
		}
	}
	return out, rows.Err()
}
