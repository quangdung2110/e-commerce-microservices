# Seller Service Design Document

## 1. Overview
The Seller Service manages vendor onboarding, store profiles, seller verification status, commission rates, and payouts info.

## 2. Technology Choice
- **Database**: PostgreSQL (Store metadata, legal info, tier settings).
- **Blob Storage**: AWS S3 / MinIO (For KYC documents, store logos).
- **Cache**: Redis (Store details queried mostly at checkout and product view).

## 3. Key Entities
- `sellers`: `id(PK)`, `user_id`, `company_name`, `tax_id`, `status(PENDING,VERIFIED,SUSPENDED)`
- `store_profiles`: `store_id(PK)`, `seller_id(FK)`, `name`, `description`, `logo_url`, `theme`
- `seller_configs`: `seller_id(PK)`, `return_policy`, `shipping_tiers`

## 4. Background Workers
- **KYC Verification Worker**: Processes uploaded docs asynchronously, calls 3rd-party APIs for verifications.
- **Status Sync**: Informs Product service and Search service via Kafka to deactivate listings when a seller is banned.
