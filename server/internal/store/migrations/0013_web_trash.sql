-- hoard-server 0.14.0: the trash from the web gallery (ADR 0023 Amendment (0.14.0)). Forward only, additive: new tables
-- and their indexes only (no index over an existing big table, so no temporary files).

-- The server trash: an item that is not on the NAS (phone backups, uploads) trashed by the web gallery's admin. Its
-- blobs stay (pinned) until the purge; the item is trashed while its row exists. fingerprints are its member files at
-- the time of the move (server_trash_files, for the ownership checks).
CREATE TABLE server_trash (
    item_id     TEXT    PRIMARY KEY,
    main_fp     TEXT    NOT NULL,
    trashed_at  INTEGER NOT NULL, -- ms
    purge_after INTEGER NOT NULL, -- ms: the auto-empty date (compress.trashDays, 30 by default)
    trashed_by  TEXT    NOT NULL, -- 'admin'
    size        INTEGER NOT NULL DEFAULT 0,
    purged_at   INTEGER           -- emptied: the item stays trashed until the backup pass drops its sources
) STRICT;
CREATE INDEX server_trash_purge ON server_trash (purge_after);
CREATE TABLE server_trash_files (
    fingerprint TEXT NOT NULL,
    item_id     TEXT NOT NULL REFERENCES server_trash (item_id) ON DELETE CASCADE,
    PRIMARY KEY (fingerprint, item_id)
) STRICT, WITHOUT ROWID;
CREATE INDEX server_trash_files_item ON server_trash_files (item_id);

-- The journal of the server's own moves in NAS folders (through the write mount): written before each move and
-- settled after, so a restart resumes or rolls back by looking at both paths (ADR 0023's "moving" rows). A row stays
-- while the file is in the trash (state trashed): what the server's auto-empty takes once purge_after passes. Moves made
-- by Hoard have no row (the path is the manifest); restoring or deleting one writes a row for the time it runs.
CREATE TABLE trash_moves (
    id          INTEGER PRIMARY KEY,
    folder_id   TEXT    NOT NULL,
    rel_path    TEXT    NOT NULL, -- the original path, relative to the folder
    trash_path  TEXT    NOT NULL, -- <trash root>/.hoard-trash/<day>/<path from the trash root>
    state       TEXT    NOT NULL, -- moving | trashed | restoring | purging
    size        INTEGER NOT NULL,
    mtime       INTEGER NOT NULL, -- ns
    item_id     TEXT    NOT NULL DEFAULT '',
    moved_at    INTEGER NOT NULL, -- ms
    purge_after INTEGER NOT NULL  -- ms
) STRICT;
CREATE UNIQUE INDEX trash_moves_trash ON trash_moves (folder_id, trash_path);
CREATE INDEX trash_moves_state ON trash_moves (state, purge_after);
