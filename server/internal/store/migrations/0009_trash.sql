-- hoard-server 0.11.0: the trash (ADR 0023). Forward only; no existing column changes meaning.
--
-- A file under <P>/.hoard-trash/<yyyy-MM-dd(-n)>/<R> is a trash row of nas_files: its rel_path is where it is now and
-- origin_path is <P>/<R>, where it came from. Trash rows never appear in the NAS feed. A removed original whose file is
-- in a trash keeps the trash row's path in trash_path (the NAS feed then says "trashed": true).
ALTER TABLE nas_files ADD COLUMN origin_path TEXT;
ALTER TABLE nas_files ADD COLUMN trash_path TEXT;
CREATE INDEX nas_files_origin ON nas_files (folder_id, origin_path) WHERE origin_path IS NOT NULL;

-- An item whose every location is in a trash is trashed (not deleted): trashed_at is when it was first seen so, NULL
-- for a live item or a tombstone. Trash groups are item_sources rows with source = 'trash'.
ALTER TABLE items ADD COLUMN trashed_at INTEGER;

-- The indexes of live items exclude trashed ones; trashed items have their own.
DROP INDEX items_time;
DROP INDEX items_kind_time;
CREATE INDEX items_time ON items (sort_time, id) WHERE deleted = 0 AND trashed_at IS NULL;
CREATE INDEX items_kind_time ON items (kind, sort_time, id) WHERE deleted = 0 AND trashed_at IS NULL;
CREATE INDEX items_trashed_time ON items (sort_time, id) WHERE deleted = 0 AND trashed_at IS NOT NULL;
