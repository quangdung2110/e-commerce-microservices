-- Remove obsolete exclusivity flag from promotions.
-- Safe for both existing databases and fresh environments.
ALTER TABLE promotions
DROP COLUMN IF EXISTS is_exclusive;
