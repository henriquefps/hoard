-- Reveal's edited previews (ADR 0020, hoard-server 0.9.0): one row per Reveal version that has an edited preview on
-- the server. Additive: no existing table changes.
--
-- version_id is Reveal's version uuid (ADR 0013 "Records"): a version has at most one edit on the server, on one item.
-- sha256 names the cleaned file under <data dir>/edits (content-addressed: two versions with the same image share it).
-- At most one row per item has is_primary = 1 (the server clears the others when one is set).
CREATE TABLE item_edits (
    version_id      TEXT    PRIMARY KEY,
    item_id         TEXT    NOT NULL,
    device_id       TEXT    NOT NULL,
    recipe_digest   TEXT    NOT NULL,
    edited_at       INTEGER NOT NULL,
    label           TEXT    NOT NULL DEFAULT '',
    is_virtual_copy INTEGER NOT NULL DEFAULT 0,
    is_primary      INTEGER NOT NULL DEFAULT 0,
    sha256          TEXT    NOT NULL,
    format          TEXT    NOT NULL,
    width           INTEGER NOT NULL,
    height          INTEGER NOT NULL,
    bytes           INTEGER NOT NULL,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
) STRICT;
CREATE INDEX item_edits_item ON item_edits (item_id);
CREATE INDEX item_edits_sha ON item_edits (sha256);
