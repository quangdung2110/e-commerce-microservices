# User Service Design Document

## 1. Overview
The User service manages user profiles, addresses, preferences, and payment settings. It is queried frequently for profile rendering and order addressing.

## 2. Technology Choice
- **Language**: Core logic in Go / Java.
- **Database**: PostgreSQL.
- **Cache**: Redis (High-hit profile caching).
- **Message Broker**: Apache Kafka (Publishing User Created, Address Updated events).

## 3. Database Schema Overview
- `users`: `id(PK)`, `email`, `phone`, `first_name`, `last_name`, `status`, `created_at`
- `user_addresses`: `id(PK)`, `user_id(FK)`, `street`, `city`, `zip`, `is_default`
- `user_preferences`: `user_id(PK)`, `newsletter_enabled`, `currency`

## 4. Synchronization & Events
- **Topic**: `user.profile.events`
- Allows Order, Cart, and Notification services to update data asynchronously.

## 5. Cache Validation Pattern
Using Redis with Read-Through/Write-Through caching for active profiles to serve high-RPS endpoint reads.
