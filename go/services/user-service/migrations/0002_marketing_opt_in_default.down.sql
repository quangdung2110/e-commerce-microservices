-- Restores the column default of 0001. The backfill of the up migration is not
-- reversed: rows it switched off cannot be told apart from rows the owner
-- switched off, and turning marketing back on would restore the consent
-- problem this change fixes.
ALTER TABLE "user".user_preferences
    ALTER COLUMN marketing_enabled SET DEFAULT TRUE;
