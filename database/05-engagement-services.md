# Group 5: User Engagement Services

> **Services**: Review Service, Notification Service, Chat Service
> **Pattern**: Event Sourcing + CQRS + Saga (Temporal)
> **Database**: PostgreSQL (Review, Notification) | ScyllaDB (Chat)

---

## Table of Contents
1. [Review Service](#1-review-service)
2. [Notification Service](#2-notification-service)
3. [Chat Service](#3-chat-service)
4. [Cross-Service Integration](#4-cross-service-integration)
5. [Kafka Topics & Redis Caching](#5-kafka-topics--redis-caching)

---

## 1. Review Service

### 1.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing `order_id` verification | HIGH | Link review to order for verified purchase |
| 2 | No `review_eligibility` check | HIGH | Add eligibility rules (delivered orders only) |
| 3 | Missing `review_media` table | HIGH | Separate table for images/videos |
| 4 | No `review_votes` (helpful/not helpful) | MEDIUM | Add voting system |
| 5 | Missing `review_reports` for moderation | MEDIUM | Add reporting system |
| 6 | No `seller_response` support | MEDIUM | Sellers should reply to reviews |
| 7 | Missing `review_edit_history` | MEDIUM | Track edit history |
| 8 | No `verified_purchase` badge | MEDIUM | Add verification flag |
| 9 | Missing `review_summary` per product | LOW | Denormalized stats |
| 10 | No `review_incentive` tracking | LOW | Track review rewards |
| 11 | Missing `variant_attributes` in review | LOW | Know which variant was reviewed |
| 12 | No anonymous review option | LOW | Some users prefer anonymity |

### 1.2 Database Schema

#### Table: `product_reviews`
```sql
CREATE TABLE product_reviews (
    review_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Product
    product_id          UUID NOT NULL,
    sku_id              UUID,  -- Specific variant reviewed
    seller_id           UUID NOT NULL,
    
    -- Reviewer
    user_id             UUID NOT NULL,
    
    -- Order verification
    order_id            UUID NOT NULL,
    order_item_id       UUID NOT NULL,
    
    -- Rating
    rating              SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    
    -- Content
    title               VARCHAR(200),
    content             TEXT,
    
    -- Variant info (snapshot)
    variant_info        VARCHAR(200),  -- "Màu: Đỏ, Size: L"
    
    -- Verification
    is_verified_purchase BOOLEAN NOT NULL DEFAULT true,
    
    -- Display options
    is_anonymous        BOOLEAN NOT NULL DEFAULT false,
    display_name        VARCHAR(100),  -- Masked name if anonymous
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'HIDDEN', 'DELETED')
    ),
    rejection_reason    TEXT,
    
    -- Moderation
    moderated_by        UUID,
    moderated_at        TIMESTAMPTZ,
    
    -- Engagement metrics
    helpful_count       INTEGER NOT NULL DEFAULT 0,
    not_helpful_count   INTEGER NOT NULL DEFAULT 0,
    reply_count         INTEGER NOT NULL DEFAULT 0,
    
    -- Edit tracking
    is_edited           BOOLEAN NOT NULL DEFAULT false,
    last_edited_at      TIMESTAMPTZ,
    edit_count          SMALLINT NOT NULL DEFAULT 0,
    
    -- Incentive
    reward_points       INTEGER DEFAULT 0,
    reward_granted_at   TIMESTAMPTZ,
    
    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT uq_user_order_item_review UNIQUE (user_id, order_item_id)
);

CREATE INDEX idx_reviews_product ON product_reviews(product_id, status, created_at DESC);
CREATE INDEX idx_reviews_seller ON product_reviews(seller_id, status, created_at DESC);
CREATE INDEX idx_reviews_user ON product_reviews(user_id, created_at DESC);
CREATE INDEX idx_reviews_rating ON product_reviews(product_id, rating) WHERE status = 'APPROVED';
CREATE INDEX idx_reviews_pending ON product_reviews(status, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_reviews_order ON product_reviews(order_id);
```

#### Table: `review_media`
```sql
CREATE TABLE review_media (
    media_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id           UUID NOT NULL REFERENCES product_reviews(review_id) ON DELETE CASCADE,
    
    -- Media type
    media_type          VARCHAR(10) NOT NULL CHECK (media_type IN ('IMAGE', 'VIDEO')),
    
    -- Storage
    storage_key         VARCHAR(500) NOT NULL,
    thumbnail_key       VARCHAR(500),
    
    -- Metadata
    width               INTEGER,
    height              INTEGER,
    file_size_bytes     INTEGER,
    duration_seconds    INTEGER,  -- For video
    
    -- Display order
    display_order       SMALLINT NOT NULL DEFAULT 0,
    
    -- Moderation
    is_approved         BOOLEAN NOT NULL DEFAULT false,
    moderation_status   VARCHAR(20) DEFAULT 'PENDING',
    
    -- Timestamp
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_media_review ON review_media(review_id, display_order);
```

#### Table: `review_votes`
```sql
CREATE TABLE review_votes (
    vote_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id           UUID NOT NULL REFERENCES product_reviews(review_id) ON DELETE CASCADE,
    user_id             UUID NOT NULL,
    
    -- Vote type
    vote_type           VARCHAR(15) NOT NULL CHECK (vote_type IN ('HELPFUL', 'NOT_HELPFUL')),
    
    -- Timestamp
    voted_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_review_vote UNIQUE (review_id, user_id)
);

CREATE INDEX idx_review_votes_review ON review_votes(review_id);
CREATE INDEX idx_review_votes_user ON review_votes(user_id);
```

#### Table: `review_reports`
```sql
CREATE TABLE review_reports (
    report_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id           UUID NOT NULL REFERENCES product_reviews(review_id),
    
    -- Reporter
    reported_by         UUID NOT NULL,
    
    -- Reason
    reason_code         VARCHAR(30) NOT NULL CHECK (
        reason_code IN (
            'SPAM',
            'FAKE_REVIEW',
            'INAPPROPRIATE_CONTENT',
            'HARASSMENT',
            'IRRELEVANT',
            'WRONG_PRODUCT',
            'OTHER'
        )
    ),
    reason_details      TEXT,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'REVIEWED', 'ACTION_TAKEN', 'DISMISSED')
    ),
    
    -- Resolution
    resolved_by         UUID,
    resolved_at         TIMESTAMPTZ,
    resolution_notes    TEXT,
    action_taken        VARCHAR(50),  -- HIDDEN, DELETED, WARNING_ISSUED, NONE
    
    -- Timestamp
    reported_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_reports_review ON review_reports(review_id);
CREATE INDEX idx_review_reports_status ON review_reports(status, reported_at);
```

#### Table: `review_edit_history`
```sql
CREATE TABLE review_edit_history (
    history_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id           UUID NOT NULL REFERENCES product_reviews(review_id) ON DELETE CASCADE,
    
    -- Previous content
    previous_rating     SMALLINT,
    previous_title      VARCHAR(200),
    previous_content    TEXT,
    
    -- Edit metadata
    edit_number         SMALLINT NOT NULL,
    edited_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_review_history_review ON review_edit_history(review_id, edit_number DESC);
```

#### Table: `seller_responses`
```sql
CREATE TABLE seller_responses (
    response_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id           UUID NOT NULL REFERENCES product_reviews(review_id),
    
    -- Seller
    seller_id           UUID NOT NULL,
    responded_by        UUID NOT NULL,  -- Staff member
    
    -- Content
    content             TEXT NOT NULL,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'HIDDEN', 'DELETED')
    ),
    
    -- Edit tracking
    is_edited           BOOLEAN NOT NULL DEFAULT false,
    last_edited_at      TIMESTAMPTZ,
    
    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_seller_response UNIQUE (review_id, seller_id)
);

CREATE INDEX idx_seller_responses_review ON seller_responses(review_id);
CREATE INDEX idx_seller_responses_seller ON seller_responses(seller_id);
```

#### Table: `product_review_stats`
```sql
-- Denormalized stats per product (updated via triggers/events)
CREATE TABLE product_review_stats (
    product_id          UUID PRIMARY KEY,
    
    -- Counts
    total_reviews       INTEGER NOT NULL DEFAULT 0,
    approved_reviews    INTEGER NOT NULL DEFAULT 0,
    
    -- Rating distribution
    rating_1_count      INTEGER NOT NULL DEFAULT 0,
    rating_2_count      INTEGER NOT NULL DEFAULT 0,
    rating_3_count      INTEGER NOT NULL DEFAULT 0,
    rating_4_count      INTEGER NOT NULL DEFAULT 0,
    rating_5_count      INTEGER NOT NULL DEFAULT 0,
    
    -- Averages
    average_rating      DECIMAL(3, 2),
    
    -- Media counts
    reviews_with_photos INTEGER NOT NULL DEFAULT 0,
    reviews_with_videos INTEGER NOT NULL DEFAULT 0,
    
    -- Verification
    verified_reviews    INTEGER NOT NULL DEFAULT 0,
    
    -- Last review
    last_review_at      TIMESTAMPTZ,
    
    -- Updated timestamp
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

#### Table: `review_eligibility`
```sql
-- Track which orders can be reviewed
CREATE TABLE review_eligibility (
    eligibility_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Order reference
    order_id            UUID NOT NULL,
    order_item_id       UUID NOT NULL,
    user_id             UUID NOT NULL,
    product_id          UUID NOT NULL,
    sku_id              UUID,
    
    -- Status
    is_eligible         BOOLEAN NOT NULL DEFAULT false,
    became_eligible_at  TIMESTAMPTZ,
    
    -- Review tracking
    has_reviewed        BOOLEAN NOT NULL DEFAULT false,
    review_id           UUID REFERENCES product_reviews(review_id),
    
    -- Expiry
    eligibility_expires_at TIMESTAMPTZ,  -- 90 days after delivery
    
    -- Reminder tracking
    reminder_sent       BOOLEAN NOT NULL DEFAULT false,
    reminder_sent_at    TIMESTAMPTZ,
    
    CONSTRAINT uq_order_item_eligibility UNIQUE (order_item_id)
);

CREATE INDEX idx_eligibility_user ON review_eligibility(user_id, is_eligible, has_reviewed);
CREATE INDEX idx_eligibility_product ON review_eligibility(product_id);
CREATE INDEX idx_eligibility_reminder ON review_eligibility(is_eligible, has_reviewed, reminder_sent) 
    WHERE is_eligible = true AND has_reviewed = false AND reminder_sent = false;
```

### 1.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class ReviewEvent extends BaseEvent {
    protected String reviewId;
    protected String productId;
    protected String userId;
}

@DomainEvent(aggregateType = "Review")
public class ReviewCreatedEvent extends ReviewEvent {
    private String orderId;
    private String orderItemId;
    private int rating;
    private String title;
    private String content;
    private boolean isVerifiedPurchase;
    private List<ReviewMediaDto> media;
}

@DomainEvent(aggregateType = "Review")
public class ReviewApprovedEvent extends ReviewEvent {
    private String moderatorId;
}

@DomainEvent(aggregateType = "Review")
public class ReviewRejectedEvent extends ReviewEvent {
    private String moderatorId;
    private String reason;
}

@DomainEvent(aggregateType = "Review")
public class ReviewEditedEvent extends ReviewEvent {
    private int previousRating;
    private int newRating;
    private String previousContent;
    private String newContent;
    private int editNumber;
}

@DomainEvent(aggregateType = "Review")
public class ReviewVotedEvent extends ReviewEvent {
    private String voterId;
    private String voteType;
    private int newHelpfulCount;
    private int newNotHelpfulCount;
}

@DomainEvent(aggregateType = "Review")
public class ReviewReportedEvent extends ReviewEvent {
    private String reporterId;
    private String reasonCode;
}

@DomainEvent(aggregateType = "Review")
public class SellerRespondedEvent extends ReviewEvent {
    private String sellerId;
    private String responseId;
    private String content;
}

@DomainEvent(aggregateType = "ProductReviewStats")
public class ProductReviewStatsUpdatedEvent extends BaseEvent {
    private String productId;
    private int totalReviews;
    private BigDecimal averageRating;
    private Map<Integer, Integer> ratingDistribution;
}
```

---

## 2. Notification Service

### 2.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing `notification_templates` | HIGH | Add template management |
| 2 | No multi-channel support | HIGH | Add `notification_channels` table |
| 3 | Missing `notification_providers` | HIGH | Abstract provider integrations |
| 4 | No `delivery_status` per channel | HIGH | Track delivery per channel |
| 5 | Missing `notification_settings` per user | MEDIUM | User preferences |
| 6 | No `notification_batching` | MEDIUM | Batch notifications to reduce spam |
| 7 | Missing `scheduled_notifications` | MEDIUM | Support future delivery |
| 8 | No `notification_categories` | MEDIUM | Categorize for filtering |
| 9 | Missing `push_tokens` management | MEDIUM | Track mobile devices |
| 10 | No rate limiting tracking | MEDIUM | Prevent spam |
| 11 | Missing `notification_cooldown` | LOW | Prevent repeated similar notifications |
| 12 | No rich content support | LOW | HTML, images in notifications |

### 2.2 Database Schema

#### Table: `notification_providers`
```sql
CREATE TABLE notification_providers (
    provider_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Provider info
    provider_code       VARCHAR(50) NOT NULL UNIQUE,
    provider_name       VARCHAR(200) NOT NULL,
    
    -- Channel
    channel_type        VARCHAR(20) NOT NULL CHECK (
        channel_type IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP', 'WEBHOOK')
    ),
    
    -- Configuration
    config              JSONB NOT NULL,
    /*
    For EMAIL (SendGrid):
    {
        "api_key_ref": "SENDGRID_API_KEY",
        "from_email": "noreply@example.com",
        "from_name": "Example Store"
    }
    
    For SMS (Twilio):
    {
        "account_sid_ref": "TWILIO_SID",
        "auth_token_ref": "TWILIO_TOKEN",
        "from_number": "+84xxxxxxxxx"
    }
    
    For PUSH (Firebase):
    {
        "credentials_ref": "FIREBASE_CREDENTIALS",
        "project_id": "my-project"
    }
    */
    
    -- Priority (for failover)
    priority            INTEGER NOT NULL DEFAULT 0,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Rate limits
    rate_limit_per_second INTEGER,
    rate_limit_per_day  INTEGER,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_providers_channel ON notification_providers(channel_type, is_active, priority DESC);
```

#### Table: `notification_templates`
```sql
CREATE TABLE notification_templates (
    template_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Template identification
    template_code       VARCHAR(100) NOT NULL,
    channel_type        VARCHAR(20) NOT NULL,
    locale              VARCHAR(10) NOT NULL DEFAULT 'vi',
    
    -- Metadata
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    category            VARCHAR(50) NOT NULL,  -- ORDER, PAYMENT, SHIPPING, MARKETING, etc.
    
    -- Content
    subject             VARCHAR(500),  -- For email
    title               VARCHAR(200),  -- For push/in-app
    body                TEXT NOT NULL,
    body_html           TEXT,  -- For rich email
    
    -- Variables (for validation)
    variables           JSONB NOT NULL DEFAULT '[]',
    /*
    [
        {"name": "user_name", "type": "string", "required": true},
        {"name": "order_number", "type": "string", "required": true},
        {"name": "order_total", "type": "number", "required": false}
    ]
    */
    
    -- Rich content
    image_url           TEXT,
    action_url          TEXT,
    action_text         VARCHAR(100),
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')
    ),
    
    -- Version control
    version             INTEGER NOT NULL DEFAULT 1,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    
    CONSTRAINT uq_template_code_channel_locale UNIQUE (template_code, channel_type, locale)
);

CREATE INDEX idx_templates_code ON notification_templates(template_code);
CREATE INDEX idx_templates_category ON notification_templates(category, status);
```

#### Table: `notification_settings`
```sql
-- User notification preferences
CREATE TABLE notification_settings (
    setting_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    
    -- Category
    category            VARCHAR(50) NOT NULL,
    
    -- Channel preferences
    email_enabled       BOOLEAN NOT NULL DEFAULT true,
    sms_enabled         BOOLEAN NOT NULL DEFAULT false,
    push_enabled        BOOLEAN NOT NULL DEFAULT true,
    in_app_enabled      BOOLEAN NOT NULL DEFAULT true,
    
    -- Frequency
    frequency           VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE' CHECK (
        frequency IN ('IMMEDIATE', 'HOURLY_DIGEST', 'DAILY_DIGEST', 'WEEKLY_DIGEST', 'OFF')
    ),
    
    -- Time preferences
    quiet_hours_start   TIME,
    quiet_hours_end     TIME,
    timezone            VARCHAR(50) DEFAULT 'Asia/Ho_Chi_Minh',
    
    -- Audit
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_user_category_setting UNIQUE (user_id, category)
);

CREATE INDEX idx_notification_settings_user ON notification_settings(user_id);
```

#### Table: `push_tokens`
```sql
CREATE TABLE push_tokens (
    token_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    
    -- Token
    token               TEXT NOT NULL,
    
    -- Device info
    device_id           VARCHAR(200),
    device_type         VARCHAR(20) NOT NULL CHECK (device_type IN ('IOS', 'ANDROID', 'WEB')),
    device_name         VARCHAR(200),
    app_version         VARCHAR(20),
    
    -- Provider
    provider_code       VARCHAR(50) NOT NULL,  -- fcm, apns
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Last used
    last_used_at        TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_user_token UNIQUE (user_id, token)
);

CREATE INDEX idx_push_tokens_user ON push_tokens(user_id, is_active);
CREATE INDEX idx_push_tokens_device ON push_tokens(device_id);
```

#### Table: `notification_events`
```sql
-- Notification requests (command side)
CREATE TABLE notification_events (
    event_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Recipient
    user_id             UUID,  -- NULL for system notifications
    recipient_email     VARCHAR(255),
    recipient_phone     VARCHAR(20),
    
    -- Template
    template_code       VARCHAR(100) NOT NULL,
    category            VARCHAR(50) NOT NULL,
    
    -- Content data
    variables           JSONB NOT NULL DEFAULT '{}',
    
    -- Channels to send
    channels            VARCHAR(20)[] NOT NULL,
    
    -- Priority
    priority            VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (
        priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')
    ),
    
    -- Scheduling
    scheduled_at        TIMESTAMPTZ,  -- NULL = immediate
    
    -- Idempotency
    idempotency_key     VARCHAR(200),
    
    -- Reference
    reference_type      VARCHAR(50),  -- ORDER, PAYMENT, SHIPPING, etc.
    reference_id        UUID,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'PARTIAL', 'FAILED')
    ),
    
    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ
);

CREATE INDEX idx_notif_events_user ON notification_events(user_id, created_at DESC);
CREATE INDEX idx_notif_events_status ON notification_events(status, scheduled_at);
CREATE INDEX idx_notif_events_scheduled ON notification_events(scheduled_at) 
    WHERE status = 'PENDING' AND scheduled_at IS NOT NULL;
CREATE INDEX idx_notif_events_ref ON notification_events(reference_type, reference_id);
CREATE INDEX idx_notif_events_idempotency ON notification_events(idempotency_key) 
    WHERE idempotency_key IS NOT NULL;
```

#### Table: `notification_deliveries`
```sql
-- Delivery attempts per channel (query side)
CREATE TABLE notification_deliveries (
    delivery_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            UUID NOT NULL REFERENCES notification_events(event_id),
    
    -- Channel
    channel_type        VARCHAR(20) NOT NULL,
    provider_code       VARCHAR(50) NOT NULL,
    
    -- Recipient (channel-specific)
    recipient           VARCHAR(500) NOT NULL,  -- email, phone, token
    
    -- Content sent
    subject             VARCHAR(500),
    content_preview     VARCHAR(500),
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED', 'BOUNCED', 'OPENED', 'CLICKED')
    ),
    
    -- Provider response
    provider_message_id VARCHAR(200),
    provider_response   JSONB,
    
    -- Error info
    error_code          VARCHAR(50),
    error_message       TEXT,
    
    -- Attempt tracking
    attempt_number      SMALLINT NOT NULL DEFAULT 1,
    max_attempts        SMALLINT NOT NULL DEFAULT 3,
    
    -- Engagement tracking
    opened_at           TIMESTAMPTZ,
    clicked_at          TIMESTAMPTZ,
    
    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ
);

CREATE INDEX idx_deliveries_event ON notification_deliveries(event_id);
CREATE INDEX idx_deliveries_status ON notification_deliveries(status, created_at);
CREATE INDEX idx_deliveries_provider ON notification_deliveries(provider_code, status);
CREATE INDEX idx_deliveries_provider_msg ON notification_deliveries(provider_message_id);
```

#### Table: `notification_cooldowns`
```sql
-- Prevent notification spam
CREATE TABLE notification_cooldowns (
    cooldown_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Key (what to throttle)
    user_id             UUID NOT NULL,
    category            VARCHAR(50) NOT NULL,
    reference_key       VARCHAR(200),  -- Optional specific key
    
    -- Cooldown
    cooldown_until      TIMESTAMPTZ NOT NULL,
    
    -- Tracking
    notification_count  INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT uq_cooldown_key UNIQUE (user_id, category, COALESCE(reference_key, ''))
);

CREATE INDEX idx_cooldowns_user ON notification_cooldowns(user_id, category);
CREATE INDEX idx_cooldowns_expires ON notification_cooldowns(cooldown_until);
```

### 2.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class NotificationEvent extends BaseEvent {
    protected String eventId;
    protected String userId;
}

@DomainEvent(aggregateType = "Notification")
public class NotificationRequestedEvent extends NotificationEvent {
    private String templateCode;
    private String category;
    private Map<String, Object> variables;
    private List<String> channels;
    private String priority;
    private Instant scheduledAt;
}

@DomainEvent(aggregateType = "Notification")
public class NotificationSentEvent extends NotificationEvent {
    private String deliveryId;
    private String channelType;
    private String providerCode;
    private String providerMessageId;
}

@DomainEvent(aggregateType = "Notification")
public class NotificationDeliveredEvent extends NotificationEvent {
    private String deliveryId;
    private Instant deliveredAt;
}

@DomainEvent(aggregateType = "Notification")
public class NotificationFailedEvent extends NotificationEvent {
    private String deliveryId;
    private String errorCode;
    private String errorMessage;
    private int attemptNumber;
}

@DomainEvent(aggregateType = "Notification")
public class NotificationEngagedEvent extends NotificationEvent {
    private String deliveryId;
    private String engagementType;  // OPENED, CLICKED
    private Instant engagedAt;
}
```

---

## 3. Chat Service

### 3.1 Overview

Chat Service sử dụng **ScyllaDB** cho write-heavy workload và real-time messaging, kết hợp **Redis** cho transient state.

### 3.2 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Using PostgreSQL for chat | HIGH | Migrate to ScyllaDB for scale |
| 2 | Missing `message_status` per recipient | HIGH | Add read receipts |
| 3 | No `typing_indicator` support | MEDIUM | Use Redis pub/sub |
| 4 | Missing `room_participants` table | MEDIUM | Track active participants |
| 5 | No `message_reactions` | MEDIUM | Add emoji reactions |
| 6 | Missing `pinned_messages` | MEDIUM | Pin important messages |
| 7 | No `media_gallery` per room | LOW | Quick media access |
| 8 | Missing `room_settings` | LOW | Per-room configuration |
| 9 | No handover history (bot→human) | MEDIUM | Track escalations |
| 10 | Missing `room_watermarks` | MEDIUM | Track read position |
| 11 | No `incident_reports` | MEDIUM | Track issues |
| 12 | Missing `room_archive` | LOW | Archive old conversations |

### 3.3 ScyllaDB Schema

#### Table: `messages_by_room` (Primary)
```sql
-- Partitioned by room_id, clustered by timestamp
CREATE TABLE messages_by_room (
    room_id         UUID,
    message_id      TIMEUUID,
    
    -- Sender
    sender_id       UUID,
    sender_type     TEXT,  -- 'USER', 'SELLER', 'BOT', 'SYSTEM'
    sender_name     TEXT,
    sender_avatar   TEXT,
    
    -- Content
    message_type    TEXT,  -- 'TEXT', 'IMAGE', 'VIDEO', 'FILE', 'PRODUCT', 'ORDER', 'SYSTEM'
    content         TEXT,
    content_data    MAP<TEXT, TEXT>,  -- For rich content (product_id, order_id, etc.)
    
    -- Media
    media_url       TEXT,
    media_thumbnail TEXT,
    media_type      TEXT,
    media_size      INT,
    
    -- Reply/Thread
    reply_to_id     TIMEUUID,
    thread_id       TIMEUUID,
    
    -- Status
    status          TEXT,  -- 'SENT', 'DELIVERED', 'READ', 'DELETED'
    
    -- Metadata
    metadata        MAP<TEXT, TEXT>,
    
    -- Timestamps
    created_at      TIMESTAMP,
    edited_at       TIMESTAMP,
    deleted_at      TIMESTAMP,
    
    PRIMARY KEY ((room_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC)
  AND compaction = {'class': 'TimeWindowCompactionStrategy', 'compaction_window_size': 1, 'compaction_window_unit': 'DAYS'}
  AND default_time_to_live = 31536000;  -- 1 year TTL
```

#### Table: `rooms_by_participant`
```sql
-- Find rooms for a user
CREATE TABLE rooms_by_participant (
    participant_id  UUID,
    participant_type TEXT,  -- 'USER', 'SELLER'
    room_id         UUID,
    
    -- Room info (denormalized)
    room_type       TEXT,  -- 'BUYER_SELLER', 'SUPPORT', 'BOT'
    room_name       TEXT,
    room_avatar     TEXT,
    
    -- Other participant (for 1:1 chats)
    other_participant_id    UUID,
    other_participant_name  TEXT,
    other_participant_avatar TEXT,
    
    -- Last message preview
    last_message_id         TIMEUUID,
    last_message_content    TEXT,
    last_message_sender_id  UUID,
    last_message_at         TIMESTAMP,
    
    -- Unread count
    unread_count    INT,
    
    -- Status
    is_archived     BOOLEAN,
    is_muted        BOOLEAN,
    is_blocked      BOOLEAN,
    
    -- Timestamps
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    
    PRIMARY KEY ((participant_id, participant_type), updated_at, room_id)
) WITH CLUSTERING ORDER BY (updated_at DESC, room_id ASC);
```

#### Table: `room_metadata`
```sql
-- Room configuration and state
CREATE TABLE room_metadata (
    room_id         UUID PRIMARY KEY,
    
    -- Room info
    room_type       TEXT,  -- 'BUYER_SELLER', 'SUPPORT', 'BOT', 'GROUP'
    room_name       TEXT,
    room_avatar     TEXT,
    
    -- Context
    context_type    TEXT,  -- 'ORDER', 'PRODUCT', 'GENERAL', 'SUPPORT'
    context_id      UUID,  -- order_id, product_id, etc.
    
    -- Seller (for buyer-seller chat)
    seller_id       UUID,
    
    -- Status
    status          TEXT,  -- 'ACTIVE', 'ARCHIVED', 'CLOSED'
    
    -- Bot state
    bot_active      BOOLEAN,
    bot_context     TEXT,  -- JSON string
    
    -- Support escalation
    escalated_to    UUID,
    escalated_at    TIMESTAMP,
    
    -- Settings
    settings        MAP<TEXT, TEXT>,
    
    -- Stats
    message_count   COUNTER,
    
    -- Timestamps
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    closed_at       TIMESTAMP
);
```

#### Table: `room_participants`
```sql
-- Track participants in a room
CREATE TABLE room_participants (
    room_id             UUID,
    participant_id      UUID,
    participant_type    TEXT,
    
    -- Role
    role                TEXT,  -- 'OWNER', 'MEMBER', 'ADMIN', 'BOT'
    
    -- Display
    display_name        TEXT,
    avatar_url          TEXT,
    
    -- Status
    is_active           BOOLEAN,
    
    -- Permissions
    can_send            BOOLEAN,
    can_delete_own      BOOLEAN,
    can_delete_any      BOOLEAN,
    
    -- Notifications
    is_muted            BOOLEAN,
    muted_until         TIMESTAMP,
    
    -- Timestamps
    joined_at           TIMESTAMP,
    left_at             TIMESTAMP,
    
    PRIMARY KEY ((room_id), participant_id)
);
```

#### Table: `unread_counter`
```sql
-- Counter table for unread messages
CREATE TABLE unread_counter (
    participant_id      UUID,
    room_id             UUID,
    
    unread_count        COUNTER,
    
    PRIMARY KEY ((participant_id), room_id)
);
```

#### Table: `room_watermarks`
```sql
-- Track read position per participant
CREATE TABLE room_watermarks (
    participant_id      UUID,
    room_id             UUID,
    
    -- Last read message
    last_read_message_id TIMEUUID,
    last_read_at        TIMESTAMP,
    
    -- Last delivered
    last_delivered_id   TIMEUUID,
    
    PRIMARY KEY ((participant_id), room_id)
);
```

#### Table: `message_reactions`
```sql
CREATE TABLE message_reactions (
    room_id         UUID,
    message_id      TIMEUUID,
    user_id         UUID,
    
    -- Reaction
    reaction        TEXT,  -- emoji code
    
    -- Timestamp
    reacted_at      TIMESTAMP,
    
    PRIMARY KEY ((room_id, message_id), user_id)
);
```

#### Table: `pinned_messages`
```sql
CREATE TABLE pinned_messages (
    room_id         UUID,
    message_id      TIMEUUID,
    
    -- Who pinned
    pinned_by       UUID,
    pinned_at       TIMESTAMP,
    
    -- Message content (denormalized)
    content_preview TEXT,
    sender_name     TEXT,
    
    PRIMARY KEY ((room_id), pinned_at, message_id)
) WITH CLUSTERING ORDER BY (pinned_at DESC, message_id DESC);
```

#### Table: `media_gallery`
```sql
-- Quick access to media in a room
CREATE TABLE media_gallery (
    room_id         UUID,
    media_type      TEXT,  -- 'IMAGE', 'VIDEO', 'FILE'
    message_id      TIMEUUID,
    
    -- Media info
    media_url       TEXT,
    thumbnail_url   TEXT,
    file_name       TEXT,
    file_size       INT,
    
    -- Sender
    sender_id       UUID,
    sender_name     TEXT,
    
    -- Timestamp
    sent_at         TIMESTAMP,
    
    PRIMARY KEY ((room_id, media_type), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

#### Table: `room_control`
```sql
-- For support escalation and bot control
CREATE TABLE room_control (
    room_id         UUID PRIMARY KEY,
    
    -- Current handler
    handler_type    TEXT,  -- 'BOT', 'HUMAN', 'HYBRID'
    handler_id      UUID,  -- bot_id or agent_id
    
    -- Queue position (for support)
    queue_position  INT,
    queue_entered_at TIMESTAMP,
    
    -- SLA tracking
    first_response_at   TIMESTAMP,
    response_deadline   TIMESTAMP,
    
    -- Resolution
    resolved_at     TIMESTAMP,
    resolution_code TEXT,
    csat_score      INT,
    
    -- Audit
    updated_at      TIMESTAMP
);
```

#### Table: `handover_history`
```sql
-- Track bot → human handovers
CREATE TABLE handover_history (
    room_id         UUID,
    handover_id     TIMEUUID,
    
    -- From
    from_type       TEXT,  -- 'BOT', 'HUMAN'
    from_id         UUID,
    
    -- To
    to_type         TEXT,
    to_id           UUID,
    
    -- Reason
    reason          TEXT,  -- 'USER_REQUEST', 'BOT_CONFIDENCE_LOW', 'COMPLEX_ISSUE', 'ESCALATION'
    
    -- Context
    context         TEXT,  -- JSON with bot conversation state
    
    -- Timestamp
    handover_at     TIMESTAMP,
    
    PRIMARY KEY ((room_id), handover_id)
) WITH CLUSTERING ORDER BY (handover_id DESC);
```

#### Table: `room_incident_reports`
```sql
-- Track reported issues in chat
CREATE TABLE room_incident_reports (
    room_id         UUID,
    report_id       TIMEUUID,
    
    -- Reporter
    reported_by     UUID,
    
    -- Reported participant
    reported_user   UUID,
    
    -- Reason
    reason          TEXT,
    description     TEXT,
    
    -- Evidence
    message_ids     LIST<TIMEUUID>,
    
    -- Status
    status          TEXT,  -- 'PENDING', 'REVIEWED', 'RESOLVED'
    
    -- Resolution
    resolved_by     UUID,
    resolution      TEXT,
    action_taken    TEXT,
    
    -- Timestamps
    reported_at     TIMESTAMP,
    resolved_at     TIMESTAMP,
    
    PRIMARY KEY ((room_id), report_id)
) WITH CLUSTERING ORDER BY (report_id DESC);
```

### 3.4 Redis Data Structures (for Real-time State)

```
# Online presence (SET with TTL refresh)
SADD online:room:{room_id} {user_id}
EXPIRE online:room:{room_id} 300

# Typing indicator (String with short TTL)
SET typing:{room_id}:{user_id} 1
EXPIRE typing:{room_id}:{user_id} 5

# Unread count cache (Hash)
HINCRBY unread:{user_id} {room_id} 1
HSET unread:{user_id} {room_id} 0  # Reset on read

# Recent rooms (Sorted Set by last message time)
ZADD user:rooms:{user_id} {timestamp} {room_id}
ZREVRANGE user:rooms:{user_id} 0 20

# Message delivery status (Hash with TTL)
HSET msg:status:{message_id} 
    "sent" "1706793600"
    "delivered:{user_1}" "1706793601"
    "read:{user_1}" "1706793605"
EXPIRE msg:status:{message_id} 86400

# Bot conversation context (String)
SET bot:context:{room_id} '{"intent": "order_inquiry", "entities": {...}}'
EXPIRE bot:context:{room_id} 3600
```

### 3.5 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class ChatEvent extends BaseEvent {
    protected String roomId;
}

@DomainEvent(aggregateType = "ChatRoom")
public class ChatRoomCreatedEvent extends ChatEvent {
    private String roomType;
    private String contextType;
    private String contextId;
    private List<ParticipantDto> participants;
}

@DomainEvent(aggregateType = "ChatRoom")
public class MessageSentEvent extends ChatEvent {
    private String messageId;
    private String senderId;
    private String senderType;
    private String messageType;
    private String content;
    private Map<String, String> contentData;
    private String replyToId;
}

@DomainEvent(aggregateType = "ChatRoom")
public class MessageDeliveredEvent extends ChatEvent {
    private String messageId;
    private String recipientId;
    private Instant deliveredAt;
}

@DomainEvent(aggregateType = "ChatRoom")
public class MessageReadEvent extends ChatEvent {
    private String messageId;
    private String readerId;
    private Instant readAt;
}

@DomainEvent(aggregateType = "ChatRoom")
public class MessageDeletedEvent extends ChatEvent {
    private String messageId;
    private String deletedBy;
    private boolean hardDelete;
}

@DomainEvent(aggregateType = "ChatRoom")
public class ParticipantJoinedEvent extends ChatEvent {
    private String participantId;
    private String participantType;
    private String role;
}

@DomainEvent(aggregateType = "ChatRoom")
public class BotHandoverEvent extends ChatEvent {
    private String fromHandler;
    private String toHandler;
    private String reason;
    private Map<String, Object> context;
}

@DomainEvent(aggregateType = "ChatRoom")
public class ChatRoomClosedEvent extends ChatEvent {
    private String closedBy;
    private String reason;
    private Integer csatScore;
}
```

---

## 4. Cross-Service Integration

### 4.1 Event Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      Engagement Services Event Flow                      │
└──────────────────────────────────────────────────────────────────────────┘

Order Service ─────────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── OrderDeliveredEvent ─────────────────────────────────────────────▶│
    │    (Trigger review eligibility)                          Review Service
    │                                                                      │
    │─── OrderCreatedEvent ───────────────────────────────────────────────▶│
    │    (Send order confirmation)                        Notification Service
    │                                                                      │
    │─── OrderStatusChangedEvent ─────────────────────────────────────────▶│
    │    (Update notifications)                           Notification Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

Review Service ────────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── ReviewCreatedEvent ──────────────────────────────────────────────▶│
    │    (Notify seller)                                  Notification Service
    │                                                                      │
    │─── ReviewStatsUpdatedEvent ─────────────────────────────────────────▶│
    │    (Update product rating)                          Product Service
    │                                                                      │
    │─── SellerRespondedEvent ────────────────────────────────────────────▶│
    │    (Notify reviewer)                                Notification Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

Chat Service ──────────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── MessageSentEvent ────────────────────────────────────────────────▶│
    │    (Push notification)                              Notification Service
    │                                                                      │
    │─── BotHandoverEvent ────────────────────────────────────────────────▶│
    │    (Alert support team)                             Notification Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘
```

### 4.2 Review Eligibility Workflow

```
┌────────────────────────────────────────────────────────────────────────┐
│                    Review Eligibility Workflow                         │
└────────────────────────────────────────────────────────────────────────┘

Order Service                              Review Service
     │                                           │
     │──── OrderDeliveredEvent ─────────────────▶│
     │     {orderId, userId, items}              │
     │                                           │
     │                                    ┌──────┴──────┐
     │                                    │ Create      │
     │                                    │ Eligibility │
     │                                    │ Records     │
     │                                    └──────┬──────┘
     │                                           │
     │                               7 days later...
     │                                           │
     │                                    ┌──────┴──────┐
     │                                    │ Send Review │
     │                                    │ Reminder    │
     │                                    └─────────────┘
     │                                           │
     │                                           ▼
     │                              Notification Service
     │                                    (Email/Push)
```

---

## 5. Kafka Topics & Redis Caching

### 5.1 Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `review-events` | Review Service | Product, Notification, Analytics | ReviewEvent |
| `notification-events` | Notification Service | Analytics | NotificationEvent |
| `notification-commands` | All Services | Notification Service | SendNotificationCommand |
| `chat-events` | Chat Service | Notification, Analytics | ChatEvent |

### 5.2 Redis Caching

| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `review:product:{product_id}:stats` | 5 min | Review statistics |
| `review:product:{product_id}:recent` | 5 min | Recent reviews |
| `review:eligibility:{user_id}` | 1 hour | Pending review items |
| `notif:template:{code}:{locale}` | 30 min | Template cache |
| `notif:settings:{user_id}` | 10 min | User preferences |
| `notif:cooldown:{user_id}:{category}` | Variable | Spam prevention |
| `chat:room:{room_id}:meta` | 5 min | Room metadata |
| `chat:online:{room_id}` | 5 min | Online users |
| `chat:typing:{room_id}` | 5 sec | Typing indicators |
| `chat:unread:{user_id}` | Persistent | Unread counts |

### 5.3 Redis Data Structures

```
# Review stats cache (Hash)
HSET review:product:prod-123:stats
    "total" "150"
    "average" "4.5"
    "r1" "5"
    "r2" "10"
    "r3" "15"
    "r4" "40"
    "r5" "80"
EXPIRE review:product:prod-123:stats 300

# Notification template cache (String)
SET notif:template:ORDER_CONFIRMED:vi '{...}'
EXPIRE notif:template:ORDER_CONFIRMED:vi 1800

# Notification cooldown (String)
SETEX notif:cooldown:user-1:MARKETING 86400 "1"

# Chat room metadata (Hash)
HSET chat:room:room-abc:meta
    "type" "BUYER_SELLER"
    "seller_id" "seller-1"
    "status" "ACTIVE"
EXPIRE chat:room:room-abc:meta 300

# Online presence (Set)
SADD chat:online:room-abc "user-1" "seller-1"
EXPIRE chat:online:room-abc 300

# Typing indicator (String with TTL)
SETEX chat:typing:room-abc:user-1 5 "1"

# Unread counts (Hash - no expiry)
HINCRBY chat:unread:user-1 "room-abc" 1
```

---

## Summary

### Tables Created

| Service | Database | Tables | Purpose |
|---------|----------|--------|---------|
| **Review** | PostgreSQL | product_reviews, review_media, review_votes, review_reports, review_edit_history, seller_responses, product_review_stats, review_eligibility | Product reviews & ratings |
| **Notification** | PostgreSQL | notification_providers, notification_templates, notification_settings, push_tokens, notification_events, notification_deliveries, notification_cooldowns | Multi-channel notifications |
| **Chat** | ScyllaDB | messages_by_room, rooms_by_participant, room_metadata, room_participants, unread_counter, room_watermarks, message_reactions, pinned_messages, media_gallery, room_control, handover_history, room_incident_reports | Real-time messaging |

### Key Design Decisions

1. **Review eligibility** enforces verified purchase requirement
2. **ScyllaDB for chat** handles write-heavy real-time messaging
3. **Redis for transient chat state** (typing, presence, unread)
4. **Multi-channel notification** with template system
5. **Bot handover tracking** for AI → human escalation
6. **Notification cooldowns** prevent spam
7. **Event-driven stats updates** for review aggregations
