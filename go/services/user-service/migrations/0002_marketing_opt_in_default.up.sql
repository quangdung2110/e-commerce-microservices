-- Marketing is opt-in (docs/user/data.md §3, docs/notification/08-decide.md §5).
--
-- 0001 created user_preferences.marketing_enabled DEFAULT TRUE, while
-- Notification Service treats a user without a preference row as "marketing
-- off" because sending marketing to somebody who never agreed is a compliance
-- risk. The daily reconciliation sweep, and any preference change, replicated
-- the TRUE default to Notification, so marketing was switched on for users who
-- never opted in. From now on the default is FALSE; the application also
-- writes FALSE explicitly at bootstrap.
ALTER TABLE "user".user_preferences
    ALTER COLUMN marketing_enabled SET DEFAULT FALSE;

-- Backfill: a row whose updated_at still equals the creation time of its
-- profile was never touched by its owner, because bootstrap writes both with
-- the same instant and the trigger moves updated_at on every real change. For
-- those rows TRUE is just the old default, not consent, so it is switched off.
--
-- The trigger fires on this update and stamps a new updated_at, which the next
-- reconciliation sweep publishes; Notification accepts it as the newer state.
--
-- A user who changed only the email or in-app flag also has a later updated_at
-- and therefore keeps marketing_enabled = TRUE: the data cannot tell that user
-- apart from one who opted in on purpose, so those rows are deliberately left
-- alone rather than guessed at.
UPDATE "user".user_preferences AS preferences
SET marketing_enabled = FALSE
FROM "user".user_profiles AS profiles
WHERE profiles.id = preferences.user_id
  AND preferences.marketing_enabled
  AND preferences.updated_at = profiles.created_at;
