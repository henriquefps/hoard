-- hoard-server 0.7.0: culling records (ADR 0016) and physical devices (ADR 0017). Additive: no existing table changes
-- meaning. (0004 is another change's number; migrations run in order of their numbers.)

-- Culling: one record per item, three fields, each with its own revision from the items counter.
CREATE TABLE culling (
    item_id TEXT PRIMARY KEY,
    rev     INTEGER NOT NULL
) STRICT;
CREATE UNIQUE INDEX culling_rev ON culling (rev);

CREATE TABLE culling_fields (
    item_id     TEXT    NOT NULL,
    field       TEXT    NOT NULL,
    value       TEXT    NOT NULL, -- JSON: 4, "pick", "Red"
    rev         INTEGER NOT NULL,
    modified_at INTEGER NOT NULL,
    device_id   TEXT    NOT NULL, -- a device id, or 'nas'
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (item_id, field)
) STRICT, WITHOUT ROWID;

-- What each NAS sidecar held when the server last read it (NULL: the field was absent).
CREATE TABLE culling_sidecars (
    folder_id TEXT    NOT NULL REFERENCES nas_folders (id) ON DELETE CASCADE,
    rel_path  TEXT    NOT NULL,
    size      INTEGER NOT NULL,
    mtime     INTEGER NOT NULL,
    item_id   TEXT    NOT NULL,
    rating    INTEGER,
    flag      TEXT,
    label     TEXT,
    PRIMARY KEY (folder_id, rel_path)
) STRICT;

-- Physical devices: the devices (app installations) that run on one piece of hardware.
CREATE TABLE physical_devices (
    id         TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL
) STRICT;

ALTER TABLE devices ADD COLUMN physical_id TEXT REFERENCES physical_devices (id);
ALTER TABLE devices ADD COLUMN app TEXT;
ALTER TABLE devices ADD COLUMN manufacturer TEXT;
ALTER TABLE devices ADD COLUMN model TEXT;

INSERT INTO physical_devices (id, created_at) SELECT id, created_at FROM devices;
UPDATE devices SET physical_id = id;
CREATE INDEX devices_physical ON devices (physical_id);
