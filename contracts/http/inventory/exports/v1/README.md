# Inventory Export API v1

[OpenAPI](openapi.yaml) is authorized only for the Search projection. After a
Product-owned SKU document is created, Search registers its readiness. Inventory must
not publish availability for that SKU before registration. Search creates a 24-hour
fixed-watermark snapshot session, imports per-SKU availability pages, then applies
changes whose `sequence` is greater than that watermark. Snapshot rows are current
state and may be newer than the watermark; Search's strict per-SKU revision guard makes
the overlap safe. Export sequence is assigned after commit by a single fenced
sequencer, so a reader never misses a fact behind its cursor
([Inventory ADR-022](../../../../../docs/inventory/decisions/ADR-022-commit-ordered-export-sequence.md)).

The change feed is retained for 30 days. A snapshot watermark is never below the retention
floor, and an expired snapshot session answers `410 SNAPSHOT_SESSION_EXPIRED`, later `404`
once purged; both mean create a new session
([Inventory ADR-028](../../../../../docs/inventory/decisions/ADR-028-post-implementation-audit-corrections.md)).
`410 REPLAY_WINDOW_EXPIRED` means
the availability fields in existing Product documents must be rebuilt from a new
snapshot; Search does not create a separate Inventory index. This API is never a
checkout or reservation dependency.
