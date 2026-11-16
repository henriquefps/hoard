-- hoard-server 0.12.0: phone backup (ADR 0024). Forward only, additive: originals, blobs and uploads keep their
-- meaning; nothing here builds an index over an existing big table (the new indexes are on new, empty tables), so the
-- migration needs no temporary files.

-- A physical device with a backup; it outlives its physical_devices row while entries exist. dir_name is its folder
-- under the backup root, derived once and never renamed by the server on its own.
CREATE TABLE mirror_devices (
    physical_id TEXT PRIMARY KEY,
    dir_name    TEXT    NOT NULL UNIQUE,
    created_at  INTEGER NOT NULL
) STRICT;

-- A folder the owner chose on a phone. types (ADR 0024 "GET /v1/backup/roots"): the JSON list of the kinds of files
-- the phone backs up from it.
CREATE TABLE mirror_roots (
    id             TEXT PRIMARY KEY,
    physical_id    TEXT    NOT NULL REFERENCES mirror_devices (physical_id) ON UPDATE CASCADE,
    volume         TEXT    NOT NULL, -- 'primary' or the volume UUID, upper-case
    path           TEXT    NOT NULL, -- relative, '/'-separated, no leading or trailing '/'; '' = the whole volume
    created_by     TEXT    NOT NULL, -- device id
    created_at     INTEGER NOT NULL,
    paused_at      INTEGER,
    stopped_at     INTEGER,          -- no new entries; the mirror stays
    last_report_at INTEGER,
    types          TEXT    NOT NULL DEFAULT '["image","video"]',
    UNIQUE (physical_id, volume, path)
) STRICT;

-- One file of a phone, current or kept. path is relative to the volume.
CREATE TABLE mirror_entries (
    id              INTEGER PRIMARY KEY,
    physical_id     TEXT    NOT NULL,
    volume          TEXT    NOT NULL,
    path            TEXT    NOT NULL,
    root_id         TEXT    NOT NULL REFERENCES mirror_roots (id),
    fingerprint     TEXT    NOT NULL,
    sha256          TEXT,             -- of the phone's bytes, when the phone sent it or the store proved it
    size            INTEGER NOT NULL,
    modified_at     INTEGER NOT NULL, -- the phone's mtime (ms)
    store           TEXT    NOT NULL, -- 'blob' | 'nas' | 'none' (waiting for an upload)
    linked          INTEGER NOT NULL DEFAULT 0,
    on_device       INTEGER NOT NULL DEFAULT 1,
    removed_at      INTEGER,
    removed_reason  TEXT,             -- 'deleted' | 'moved' | 'freed' | 'replaced'
    pinned          INTEGER NOT NULL DEFAULT 0, -- freed on the phone (the only copies) until the phone has it again;
                                                -- kept when the path is rewritten or lost in a merge
    verified_sha256 TEXT,             -- what the server's own re-hash of its copy found
    verified_at     INTEGER,
    reported_by     TEXT    NOT NULL, -- device id
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    tree_path       TEXT,             -- where its link is, relative to the device folder (NULL: none)
    lost_at         INTEGER           -- 'none' since its only copy, a NAS file, went (ADR 0024 "With ADR 0023's trash")
) STRICT;
CREATE UNIQUE INDEX mirror_entries_path ON mirror_entries (physical_id, volume, path)
    WHERE removed_reason IS NULL OR removed_reason IN ('deleted', 'freed'); -- one entry owns each path in the tree
CREATE INDEX mirror_entries_fp ON mirror_entries (fingerprint);
CREATE INDEX mirror_entries_sha ON mirror_entries (sha256) WHERE sha256 IS NOT NULL;
CREATE INDEX mirror_entries_waiting ON mirror_entries (fingerprint) WHERE store = 'none';
CREATE INDEX mirror_entries_root ON mirror_entries (root_id, path);

-- Who asked the server to keep an original (ADR 0024 "Ownership"). Everything uploaded before 0.12.0 is claimed.
CREATE TABLE original_claims (
    fingerprint TEXT    NOT NULL,
    device_id   TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    PRIMARY KEY (fingerprint, device_id)
) STRICT, WITHOUT ROWID;
INSERT INTO original_claims (fingerprint, device_id, created_at)
    SELECT o.fingerprint, coalesce(b.device_id, ''), b.created_at FROM originals o JOIN blobs b ON b.sha256 = o.sha256;

-- Uploads made with "claim": false (the mirror's own): no device claim when they complete. A row lives as long as
-- its upload.
CREATE TABLE upload_unclaimed (
    upload_id TEXT PRIMARY KEY
) STRICT, WITHOUT ROWID;

-- The server's re-hash of a stored blob for free up (ADR 0024 "POST /v1/backup/verify"), once per content.
CREATE TABLE blob_verifications (
    sha256      TEXT    PRIMARY KEY,
    result      TEXT    NOT NULL, -- the SHA-256 the re-hash found ('' = the blob could not be read)
    verified_at INTEGER NOT NULL
) STRICT, WITHOUT ROWID;

-- Full hash of a NAS file, made only for verification, and the mtime it was made for.
ALTER TABLE nas_files ADD COLUMN sha256 TEXT;
ALTER TABLE nas_files ADD COLUMN sha_mtime INTEGER;

