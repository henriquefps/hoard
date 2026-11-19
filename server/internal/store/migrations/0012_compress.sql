-- hoard-server 0.13.0: video compression (ADR 0025). Forward only, additive: new tables and indexes on them, no
-- existing table changes meaning and no index on an existing table (the migration needs no temporary files).
-- New values in existing TEXT columns need no change here: mirror_entries.removed_reason gains 'compressed' and
-- item_sources.source gains 'kept' (phone jobs, phase S2).

-- A compression job: a selection compressed with one profile, on demand or as a folder's "Compress automatically".
CREATE TABLE compress_jobs (
    id           TEXT PRIMARY KEY,           -- UUID
    target       TEXT NOT NULL,              -- 'nas' | 'phone' | 'backup'
    physical_id  TEXT,                       -- phone jobs: the physical device whose files are replaced
    created_by   TEXT NOT NULL,              -- device id, or 'admin' (web gallery session)
    name         TEXT NOT NULL DEFAULT '',
    selection    TEXT NOT NULL,              -- JSON, as sent (folders, filters, items)
    profile      TEXT NOT NULL,              -- 'hevc-cq28' | 'hevc-cq30' | 'av1-cq34'
    options      TEXT NOT NULL,              -- JSON: quality mode and floor, option set
    auto         INTEGER NOT NULL DEFAULT 0, -- 1: a folder's "Compress automatically" (a standing job)
    priority     INTEGER NOT NULL,           -- 1 on-demand phone, 2 on-demand library and backup, 3 automatic
    state        TEXT NOT NULL,              -- active | paused | cancelled | done (an automatic job stays active)
    created_at   INTEGER NOT NULL,
    finished_at  INTEGER,
    request_id   TEXT UNIQUE                 -- the client's idempotency key
) STRICT;
CREATE UNIQUE INDEX compress_jobs_auto ON compress_jobs (target, selection) WHERE auto = 1 AND state != 'cancelled';

-- One video of a job, from the queue to the purge of the file it trashed.
CREATE TABLE compress_items (
    id              INTEGER PRIMARY KEY,
    job_id          TEXT NOT NULL REFERENCES compress_jobs (id),
    item_id         TEXT NOT NULL,              -- the item when queued (the original's)
    fingerprint     TEXT NOT NULL,              -- the original's v1: fingerprint
    sha256          TEXT,                       -- the original's SHA-256
    size            INTEGER NOT NULL,           -- the original's
    source          TEXT NOT NULL,              -- 'nas' | 'blob'
    folder_id       TEXT,                       -- nas: the NAS folder and path being replaced
    rel_path        TEXT,
    mirror_entry_id INTEGER,                    -- phone and backup: the mirror entry
    state           TEXT NOT NULL,
    reason          TEXT NOT NULL DEFAULT '',   -- skip or failure reason
    priority        INTEGER NOT NULL,
    order_key       INTEGER NOT NULL DEFAULT 0, -- newest shot first within a priority
    not_before      INTEGER NOT NULL DEFAULT 0,
    attempts        INTEGER NOT NULL DEFAULT 0,
    est_size        INTEGER, est_ms INTEGER, duration_ms INTEGER,
    q               INTEGER,                    -- the q actually used
    out_fingerprint TEXT, out_sha256 TEXT, out_size INTEGER,
    vmaf_mean REAL, vmaf_low REAL, vmaf_mode TEXT, -- 'full' | 'spot'
    encode_ms INTEGER, verify_ms INTEGER, decoder TEXT, -- 'cuda' | 'cpu'
    meta_sidecar    INTEGER NOT NULL DEFAULT 0, -- 1: a per-frame JSON was saved
    replace_step    TEXT NOT NULL DEFAULT '',   -- the replace or restore journal
    trash_path      TEXT,                       -- nas: the trashed file, relative to the folder
    replaced_at INTEGER, trashed_at INTEGER, purge_after INTEGER, purged_at INTEGER, restored_at INTEGER,
    updated_at      INTEGER NOT NULL
) STRICT;
CREATE INDEX compress_items_queue ON compress_items (priority, order_key DESC) WHERE state = 'queued';
CREATE INDEX compress_items_job ON compress_items (job_id, state);
CREATE INDEX compress_items_fp ON compress_items (fingerprint);
CREATE INDEX compress_items_out ON compress_items (out_fingerprint) WHERE out_fingerprint IS NOT NULL;
CREATE INDEX compress_items_purge ON compress_items (purge_after) WHERE state IN ('replaced', 'restored');
CREATE UNIQUE INDEX compress_items_active ON compress_items (fingerprint)
    WHERE state NOT IN ('skipped', 'failed', 'cancelled', 'purged');

-- What compression needs to know of a video beyond file_meta (per fingerprint, versioned by compress.ProbeVersion),
-- read lazily by one ffprobe run.
CREATE TABLE video_probe (fingerprint TEXT PRIMARY KEY, version INTEGER NOT NULL, data TEXT NOT NULL) STRICT;
