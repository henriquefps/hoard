-- Smart previews named by live photo records (ADR 0017 "Dropping a smart preview"): the reference check of
-- DELETE /v1/blobs/{sha256} is one index lookup instead of a scan of every record.
CREATE INDEX records_preview ON records (json_extract(data, '$.preview.sha256'))
    WHERE kind = 'photo' AND deleted = 0 AND data IS NOT NULL;
