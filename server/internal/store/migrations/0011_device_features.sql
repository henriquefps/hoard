-- hoard-server 0.12.0: the capabilities a device declared besides "culling" (which keeps culling_since), as a sorted,
-- comma-separated list of names [a-z0-9-] (ADR 0024 "Amendment (S1)": "freed-adopt"). Additive: a column with a default.
ALTER TABLE devices ADD COLUMN features TEXT NOT NULL DEFAULT '';
