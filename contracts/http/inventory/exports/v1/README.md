# Inventory Export API v1

[OpenAPI](openapi.yaml) is authorized only for the Search projection. After a
Product-owned SKU document is created, Search registers its readiness. Inventory must
not publish availability for that SKU before registration. Search creates a 24-hour
fixed-watermark snapshot session, imports per-SKU availability pages, then applies
changes whose `sequence` is greater than that watermark.

The change feed is retained for at least 30 days. `410 REPLAY_WINDOW_EXPIRED` means
the availability fields in existing Product documents must be rebuilt from a new
snapshot; Search does not create a separate Inventory index. This API is never a
checkout or reservation dependency.
