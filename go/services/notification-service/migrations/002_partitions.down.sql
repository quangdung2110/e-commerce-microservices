DROP FUNCTION IF EXISTS notification_expired_partitions(text, timestamptz);
DROP FUNCTION IF EXISTS notification_partition_future_months(text, timestamptz);
DROP FUNCTION IF EXISTS notification_ensure_partitions(timestamptz, int);
DROP FUNCTION IF EXISTS notification_ensure_partition(text, date);
DROP FUNCTION IF EXISTS notification_partition_name(text, date);
DROP FUNCTION IF EXISTS notification_partitioned_tables();
