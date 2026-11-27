-- hoard-server 0.15.0 (ADR 0023 Amendment (0.15.0)): the trash for paired devices. Who asked the server to move a file
-- into a NAS trash: 'admin' (the web gallery; every row before 0.15.0) or a device id (POST /v1/trash/move). The unit's
-- `by` and its group (web, hoard) follow it.
ALTER TABLE trash_moves ADD COLUMN moved_by TEXT NOT NULL DEFAULT 'admin';
