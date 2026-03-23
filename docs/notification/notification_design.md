# Notification Service Design Document

## 1. Overview
The Notification service handles all external communications via Email, SMS, Push notification, and In-App messages.

## 2. Technology Choice
- **Event Bus**: Kafka (Listens to topics from Order, Cart, User, Seller etc.).
- **Database**: PostgreSQL / MongoDB (To store notification templates and history of delivered messages).
- **Push Vendors**: FCM / APNs / Twilio / SendGrid.
- **Cache/In-Memory**: Redis Pub/Sub or Streams for reliable real-time WebSocket delivery of In-App notifications.

## 3. Workflow
1. Event is ingested from Kafka (e.g. `order.created`).
2. Event matching against Notification Preferences (`user_preferences`).
3. Template rendering (e.g. inject order ID into localized HTML template).
4. Submission to Provider Queue.
5. Feedback webhook from Provider updates Notification Delivery status.

## 4. Idempotency & Rate Limiting
- Deduplication using Redis to ensure users are not spammed for duplicate events.
- Rate limits per user per channel using Token Bucket.
