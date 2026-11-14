-- Gallery previews (ADR 0015): one row per item and job (image: thumb + screen; proxy: the video proxy). The row is
-- both the queue entry and the record of what is on disk. Additive: no existing table changes.
--
-- want_key is the content key the item needs now (source + recipe + PREVIEW_VERSION); key is the content key of the
-- files on disk ('' = none yet). key != want_key means the files are stale and served until the new ones are made.
-- state: pending (queued), running, done (key = want_key), failed (want_key cannot be made; error says why).
-- priority: 0 a client waits, 1 a client asked to warm it, 2 recently added, 3 backlog; order_key orders a priority
-- (newest request or newest shot first).
CREATE TABLE previews (
    item_id    TEXT    NOT NULL,
    job        TEXT    NOT NULL,
    state      TEXT    NOT NULL,
    priority   INTEGER NOT NULL,
    order_key  INTEGER NOT NULL DEFAULT 0,
    not_before INTEGER NOT NULL DEFAULT 0,
    attempts   INTEGER NOT NULL DEFAULT 0,
    error      TEXT    NOT NULL DEFAULT '',
    want_key   TEXT    NOT NULL,
    key        TEXT    NOT NULL DEFAULT '',
    outputs    TEXT    NOT NULL DEFAULT '{}',
    bytes      INTEGER NOT NULL DEFAULT 0,
    generator  TEXT    NOT NULL DEFAULT '',
    ms         INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (item_id, job)
) STRICT, WITHOUT ROWID;
CREATE INDEX previews_queue ON previews (job, priority, order_key DESC) WHERE state = 'pending';
CREATE INDEX previews_key ON previews (key) WHERE key != '';
