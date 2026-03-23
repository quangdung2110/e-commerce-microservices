-- Theo thiết kế shipping_db_design.md mục 2.2: Scylla keyspace/table cho shipment events.
CREATE KEYSPACE IF NOT EXISTS shipping_keyspace
WITH REPLICATION = {'class':'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS shipping_keyspace.shipment_events (
    shipment_id BIGINT,
    occurred_at TIMESTAMP,
    standardized_status TEXT,
    provider_status TEXT,
    description TEXT,
    location TEXT,
    raw_payload TEXT,
    PRIMARY KEY ((shipment_id), occurred_at)
) WITH CLUSTERING ORDER BY (occurred_at DESC)
  AND default_time_to_live = 63072000;
