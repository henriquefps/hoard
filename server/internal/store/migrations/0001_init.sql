-- reveal-sync schema version 1 (ADR 0017).

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) STRICT;

-- Revision counters: 'records' (the change feed) and 'nas' (the NAS file feed).
CREATE TABLE counters (
    name  TEXT PRIMARY KEY,
    value INTEGER NOT NULL
) STRICT;
INSERT INTO counters (name, value) VALUES ('records', 0), ('nas', 0);

CREATE TABLE admin (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    password_hash TEXT    NOT NULL,
    created_at    INTEGER NOT NULL
) STRICT;

CREATE TABLE sessions (
    id_hash    BLOB PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
) STRICT;

CREATE TABLE devices (
    id           TEXT PRIMARY KEY,
    name         TEXT    NOT NULL,
    platform     TEXT    NOT NULL,
    app_version  TEXT    NOT NULL,
    key_id       TEXT    NOT NULL UNIQUE,
    key_hash     BLOB    NOT NULL,
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER,
    last_sync_at INTEGER,
    revoked_at   INTEGER
) STRICT;

CREATE TABLE pairing_tokens (
    id         TEXT PRIMARY KEY,
    token_hash BLOB    NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at    INTEGER,
    device_id  TEXT REFERENCES devices (id) ON DELETE SET NULL
) STRICT;

CREATE TABLE records (
    kind        TEXT    NOT NULL,
    id          TEXT    NOT NULL,
    rev         INTEGER NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,
    modified_at INTEGER NOT NULL,
    device_id   TEXT    NOT NULL,
    copy_of     TEXT,
    data        TEXT,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (kind, id)
) STRICT;
CREATE UNIQUE INDEX records_rev ON records (rev);
CREATE INDEX records_copy_of ON records (copy_of) WHERE copy_of IS NOT NULL;

CREATE TABLE blobs (
    sha256      TEXT PRIMARY KEY,
    size        INTEGER NOT NULL,
    kind        TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    device_id   TEXT
) STRICT;

-- Fingerprints of uploaded originals (one blob can carry several: the same bytes are one fingerprint, but keep it general).
CREATE TABLE originals (
    fingerprint TEXT PRIMARY KEY,
    sha256      TEXT NOT NULL REFERENCES blobs (sha256)
) STRICT;

CREATE TABLE uploads (
    id          TEXT PRIMARY KEY,
    device_id   TEXT    NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    sha256      TEXT    NOT NULL,
    size        INTEGER NOT NULL,
    kind        TEXT    NOT NULL,
    fingerprint TEXT,
    "offset"    INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    UNIQUE (device_id, sha256)
) STRICT;

CREATE TABLE nas_folders (
    id         TEXT PRIMARY KEY,
    path       TEXT    NOT NULL UNIQUE,
    name       TEXT    NOT NULL,
    enabled    INTEGER NOT NULL DEFAULT 1,
    state      TEXT    NOT NULL DEFAULT 'pending',
    error      TEXT,
    file_count INTEGER NOT NULL DEFAULT 0,
    indexed_at INTEGER,
    created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE nas_files (
    id          INTEGER PRIMARY KEY,
    folder_id   TEXT    NOT NULL REFERENCES nas_folders (id) ON DELETE CASCADE,
    rel_path    TEXT    NOT NULL,
    size        INTEGER NOT NULL,
    mtime       INTEGER NOT NULL,
    fingerprint TEXT,
    removed     INTEGER NOT NULL DEFAULT 0,
    rev         INTEGER NOT NULL,
    UNIQUE (folder_id, rel_path)
) STRICT;
CREATE INDEX nas_files_fingerprint ON nas_files (fingerprint);
CREATE UNIQUE INDEX nas_files_rev ON nas_files (rev);
