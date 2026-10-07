DROP TABLE IF EXISTS deleted_user_tombstone;
DROP TABLE IF EXISTS notification_ordering_lease;
DROP TABLE IF EXISTS notification_subject_sequence;
ALTER TABLE notification_delivery DROP COLUMN IF EXISTS source_sequence;
ALTER TABLE notification_delivery DROP COLUMN IF EXISTS source;
DROP INDEX IF EXISTS idx_request_subject_sequence;
ALTER TABLE notification_request DROP COLUMN IF EXISTS source_sequence;
