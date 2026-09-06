---
doc_id: product-detail-bff-development
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Development Guide

Implementation cần PostgreSQL, Kafka, Product internal client và Review internal client.
Không cần Elasticsearch và không đọc database của Product/Inventory/Review.

Test bắt buộc: Product nhiều SKU với trạng thái mixed; snapshot về 0 rồi có hàng lại;
event trùng/cũ; event tới trước Product; projection stale; bootstrap/export rồi replay;
Product `HIDDEN`; Checkout reserve thất bại sau khi PDP đã hiện `IN_STOCK`; Review có
aggregate 0; Review có preview; Review timeout/5xx; và response Review sai contract.

Definition of Done: OpenAPI/internal contract có contract test; projection rebuild được
từ export; revision guard có integration test; observability/freshness runbook hoàn chỉnh;
Review provider/consumer contract test pass; lỗi Review degrade đúng; và không có Product
stock field, Review table/consumer/command handler hoặc BFF dependency sang Search.
