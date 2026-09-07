# Inventory Export API v1

[OpenAPI](openapi.yaml) is authorized only for Search Projection bootstrap and
recovery. Search creates a 24-hour fixed-watermark snapshot session, imports
warehouse and stock-position pages, then applies changes whose `sequence` is greater
than that watermark.

The change feed is retained for at least 30 days. `410 REPLAY_WINDOW_EXPIRED` means
the projection must be discarded and bootstrapped again. This API is never a checkout
or reservation dependency.
