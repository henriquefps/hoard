-- hoard-server web gallery (ADR 0018): sessions of browsers signed in to the gallery with the admin password. Kept
-- apart from the setup page's sessions (a gallery cookie never opens /admin). device_id is the culling writer of the
-- session ("web:" + 16 hex, ADR 0016); last_seen_at for the admin page.

CREATE TABLE web_sessions (
    id_hash      BLOB PRIMARY KEY,
    device_id    TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL
) STRICT;

-- The gallery's index follows preview keys by update time (store.PreviewKeysSince).
CREATE INDEX previews_updated ON previews (job, updated_at);
