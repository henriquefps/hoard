-- hoard-server items (ADR 0014): every photo and video as one item per shot, kept next to Reveal's records.
-- Additive: no existing table changes meaning. nas_files gains the media class of a row ('image' for every row the
-- earlier walks wrote); the NAS feed (GET /v1/nas/files) keeps serving images only.

ALTER TABLE nas_files ADD COLUMN media TEXT NOT NULL DEFAULT 'image';
CREATE INDEX nas_files_folder_media ON nas_files (folder_id, media);

-- The items feed has its own revision counter.
INSERT INTO counters (name, value) VALUES ('items', 0);

-- What the server read from a file's content, per fingerprint (the same bytes have the same metadata wherever they
-- live). version = media.MetaVersion of the rules that wrote it.
CREATE TABLE file_meta (
    fingerprint TEXT PRIMARY KEY,
    version     INTEGER NOT NULL,
    meta        TEXT    NOT NULL,
    updated_at  INTEGER NOT NULL
) STRICT;

-- Items: id derived from the main file's fingerprint; a tombstone keeps its row forever (deleted = 1).
CREATE TABLE items (
    id         TEXT PRIMARY KEY,
    main_fp    TEXT    NOT NULL UNIQUE,
    rev        INTEGER NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0,
    kind       TEXT    NOT NULL,
    sort_time  INTEGER NOT NULL DEFAULT 0,
    data       TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
) STRICT;
CREATE UNIQUE INDEX items_rev ON items (rev);
CREATE INDEX items_time ON items (sort_time, id) WHERE deleted = 0;
CREATE INDEX items_kind_time ON items (kind, sort_time, id) WHERE deleted = 0;

-- Where items come from: one row per NAS group (folder, directory, lower-case stem) or uploaded original (key = its
-- fingerprint). members is the JSON list of the source's files; signature changes when they do.
CREATE TABLE item_sources (
    id        INTEGER PRIMARY KEY,
    source    TEXT NOT NULL,
    folder_id TEXT NOT NULL DEFAULT '',
    dir       TEXT NOT NULL DEFAULT '',
    key       TEXT NOT NULL,
    item_id   TEXT NOT NULL,
    main_fp   TEXT NOT NULL,
    signature TEXT NOT NULL,
    members   TEXT NOT NULL,
    UNIQUE (source, folder_id, dir, key)
) STRICT;
CREATE INDEX item_sources_item ON item_sources (item_id);

-- Every member file of a live item, for the lookup by fingerprint.
CREATE TABLE item_files (
    fingerprint TEXT NOT NULL,
    item_id     TEXT NOT NULL,
    role        TEXT NOT NULL,
    PRIMARY KEY (fingerprint, item_id)
) STRICT, WITHOUT ROWID;
CREATE INDEX item_files_item ON item_files (item_id);
