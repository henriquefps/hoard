-- hoard-server 0.7.0, Reveal's review of ADR 0016 (finding A): when a device declared the "culling" capability
-- (PATCH /v1/device {"features": ["culling"]}) or first used POST /v1/culling (Unix ms; NULL: never). From then on the
-- bridge from Reveal's photo records ignores that device's culling fields. Additive.
ALTER TABLE devices ADD COLUMN culling_since INTEGER;
