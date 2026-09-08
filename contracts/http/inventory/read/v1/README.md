# Inventory Storefront Availability Read API v1

[OpenAPI](openapi.yaml) is the target internal contract for Product Detail BFF. BFF
submits one bounded unique-SKU batch after Product has resolved a catalog-active PDP and
receives advisory `IN_STOCK`, `OUT_OF_STOCK`, or `UNKNOWN` per SKU.

The API is mTLS/service-identity protected and has no side effect. It exposes no
quantity, warehouse, allocation or reservation data; `UNKNOWN` and `503` must degrade
the PDP UI rather than be treated as out of stock. Checkout/Order Reserve remains the
only authoritative stock decision.
