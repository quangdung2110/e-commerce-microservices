-- Debezium reads only the outbox (publication.autocreate.mode = disabled in
-- deploy/debezium/notification-outbox-connector.json). No table with PII is
-- ever published (13-development.md §4).
CREATE PUBLICATION notification_outbox_publication FOR TABLE outbox;
