# Group 6: Intelligence Services

> **Services**: Analytics Service, AI Agent Service
> **Pattern**: Event Sourcing + CQRS + Saga (Temporal)
> **Database**: TimescaleDB (Analytics) | PostgreSQL + pgvector (AI Agent)

---

## Table of Contents
1. [Analytics Service](#1-analytics-service)
2. [AI Agent Service](#2-ai-agent-service)
3. [Cross-Service Integration](#3-cross-service-integration)
4. [Kafka Topics & Redis Caching](#4-kafka-topics--redis-caching)

---

## 1. Analytics Service

### 1.1 Overview

Analytics Service sử dụng **TimescaleDB** (PostgreSQL extension) cho time-series data với:
- **Hypertables** cho automatic partitioning theo thời gian
- **Continuous Aggregates** cho pre-computed metrics
- **Compression** cho cost-effective storage
- **Retention Policies** cho automatic data lifecycle

### 1.2 Database Schema

#### Extension Setup
```sql
-- Enable TimescaleDB
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Enable PostGIS for location analytics (optional)
CREATE EXTENSION IF NOT EXISTS postgis;
```

#### Table: `behavior_events` (Hypertable)
```sql
-- Raw event stream - all user actions
CREATE TABLE behavior_events (
    event_id            UUID DEFAULT gen_random_uuid(),
    event_time          TIMESTAMPTZ NOT NULL,
    
    -- Event type
    event_type          VARCHAR(50) NOT NULL,
    event_category      VARCHAR(30) NOT NULL,
    /*
    Categories:
    - PAGEVIEW: page_view, product_view, category_view
    - ENGAGEMENT: add_to_cart, remove_from_cart, add_to_wishlist, share
    - SEARCH: search_query, search_result_click, filter_apply
    - TRANSACTION: checkout_start, payment_attempt, order_complete
    - USER: login, logout, signup, profile_update
    - REVIEW: review_submit, review_vote
    */
    
    -- User context
    user_id             UUID,
    session_id          VARCHAR(100) NOT NULL,
    device_id           VARCHAR(200),
    
    -- Device info
    device_type         VARCHAR(20),  -- mobile, desktop, tablet
    os                  VARCHAR(50),
    browser             VARCHAR(50),
    app_version         VARCHAR(20),
    
    -- Location
    ip_address          INET,
    country_code        VARCHAR(2),
    city                VARCHAR(100),
    
    -- Page context
    page_url            TEXT,
    page_type           VARCHAR(50),
    referrer_url        TEXT,
    referrer_type       VARCHAR(30),  -- direct, search, social, email, paid
    
    -- UTM tracking
    utm_source          VARCHAR(100),
    utm_medium          VARCHAR(100),
    utm_campaign        VARCHAR(100),
    utm_content         VARCHAR(100),
    utm_term            VARCHAR(100),
    
    -- Event-specific data
    properties          JSONB NOT NULL DEFAULT '{}',
    /*
    Examples:
    - product_view: {"product_id": "...", "category_id": "...", "price": 250000}
    - add_to_cart: {"product_id": "...", "sku_id": "...", "quantity": 2, "price": 250000}
    - search_query: {"query": "áo thun", "results_count": 150, "filters": {...}}
    - order_complete: {"order_id": "...", "total": 500000, "items_count": 3}
    */
    
    -- Timestamp
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    PRIMARY KEY (event_id, event_time)
);

-- Convert to hypertable (partition by week)
SELECT create_hypertable('behavior_events', 'event_time', 
    chunk_time_interval => INTERVAL '1 week');

-- Create indexes
CREATE INDEX idx_events_user_time ON behavior_events (user_id, event_time DESC) 
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_events_session ON behavior_events (session_id, event_time DESC);
CREATE INDEX idx_events_type ON behavior_events (event_type, event_time DESC);
CREATE INDEX idx_events_category ON behavior_events (event_category, event_time DESC);
CREATE INDEX idx_events_properties ON behavior_events USING gin(properties);

-- Compression policy (compress data older than 7 days)
ALTER TABLE behavior_events SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'event_category',
    timescaledb.compress_orderby = 'event_time DESC'
);
SELECT add_compression_policy('behavior_events', INTERVAL '7 days');

-- Retention policy (keep raw data for 90 days)
SELECT add_retention_policy('behavior_events', INTERVAL '90 days');
```

#### Table: `user_sessions`
```sql
CREATE TABLE user_sessions (
    session_id          VARCHAR(100),
    session_start       TIMESTAMPTZ NOT NULL,
    
    -- User
    user_id             UUID,
    is_authenticated    BOOLEAN NOT NULL DEFAULT false,
    
    -- Device
    device_id           VARCHAR(200),
    device_type         VARCHAR(20),
    os                  VARCHAR(50),
    browser             VARCHAR(50),
    
    -- Location
    country_code        VARCHAR(2),
    city                VARCHAR(100),
    
    -- Acquisition
    landing_page        TEXT,
    referrer_url        TEXT,
    referrer_type       VARCHAR(30),
    utm_source          VARCHAR(100),
    utm_medium          VARCHAR(100),
    utm_campaign        VARCHAR(100),
    
    -- Session metrics (updated as session progresses)
    session_end         TIMESTAMPTZ,
    duration_seconds    INTEGER,
    page_views          INTEGER DEFAULT 0,
    product_views       INTEGER DEFAULT 0,
    add_to_cart_count   INTEGER DEFAULT 0,
    
    -- Conversion
    converted           BOOLEAN DEFAULT false,
    conversion_value    DECIMAL(15, 2),
    order_id            UUID,
    
    -- Engagement score
    engagement_score    DECIMAL(5, 2),
    
    PRIMARY KEY (session_id, session_start)
);

SELECT create_hypertable('user_sessions', 'session_start',
    chunk_time_interval => INTERVAL '1 day');

CREATE INDEX idx_sessions_user ON user_sessions (user_id, session_start DESC)
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_sessions_conversion ON user_sessions (converted, session_start DESC);
```

#### Continuous Aggregate: `hourly_metrics`
```sql
CREATE MATERIALIZED VIEW hourly_metrics
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', event_time) AS hour,
    event_category,
    event_type,
    device_type,
    country_code,
    
    COUNT(*) AS event_count,
    COUNT(DISTINCT user_id) AS unique_users,
    COUNT(DISTINCT session_id) AS unique_sessions
FROM behavior_events
GROUP BY 1, 2, 3, 4, 5
WITH NO DATA;

-- Refresh policy
SELECT add_continuous_aggregate_policy('hourly_metrics',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');
```

#### Continuous Aggregate: `daily_conversions`
```sql
CREATE MATERIALIZED VIEW daily_conversions
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', session_start) AS day,
    device_type,
    referrer_type,
    utm_source,
    country_code,
    
    COUNT(*) AS total_sessions,
    COUNT(*) FILTER (WHERE converted = true) AS converted_sessions,
    SUM(conversion_value) FILTER (WHERE converted = true) AS total_revenue,
    AVG(duration_seconds) AS avg_session_duration,
    AVG(page_views) AS avg_page_views
FROM user_sessions
GROUP BY 1, 2, 3, 4, 5
WITH NO DATA;

SELECT add_continuous_aggregate_policy('daily_conversions',
    start_offset => INTERVAL '2 days',
    end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day');
```

#### Table: `funnel_definitions`
```sql
-- Define conversion funnels
CREATE TABLE funnel_definitions (
    funnel_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Basic info
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    
    -- Steps (ordered)
    steps               JSONB NOT NULL,
    /*
    [
        {"step": 1, "name": "Product View", "event_type": "product_view"},
        {"step": 2, "name": "Add to Cart", "event_type": "add_to_cart"},
        {"step": 3, "name": "Checkout Start", "event_type": "checkout_start"},
        {"step": 4, "name": "Payment", "event_type": "payment_attempt"},
        {"step": 5, "name": "Order Complete", "event_type": "order_complete"}
    ]
    */
    
    -- Configuration
    conversion_window   INTERVAL NOT NULL DEFAULT '7 days',
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID
);
```

#### Table: `funnel_metrics_daily`
```sql
-- Pre-computed funnel metrics
CREATE TABLE funnel_metrics_daily (
    date                DATE NOT NULL,
    funnel_id           UUID NOT NULL REFERENCES funnel_definitions(funnel_id),
    
    -- Segmentation
    device_type         VARCHAR(20),
    country_code        VARCHAR(2),
    
    -- Step counts
    step_1_count        INTEGER NOT NULL DEFAULT 0,
    step_2_count        INTEGER NOT NULL DEFAULT 0,
    step_3_count        INTEGER NOT NULL DEFAULT 0,
    step_4_count        INTEGER NOT NULL DEFAULT 0,
    step_5_count        INTEGER NOT NULL DEFAULT 0,
    
    -- Conversion rates (stored for quick access)
    step_1_to_2_rate    DECIMAL(5, 4),
    step_2_to_3_rate    DECIMAL(5, 4),
    step_3_to_4_rate    DECIMAL(5, 4),
    step_4_to_5_rate    DECIMAL(5, 4),
    overall_conversion  DECIMAL(5, 4),
    
    PRIMARY KEY (date, funnel_id, device_type, country_code)
);

CREATE INDEX idx_funnel_metrics_date ON funnel_metrics_daily (date DESC);
CREATE INDEX idx_funnel_metrics_funnel ON funnel_metrics_daily (funnel_id, date DESC);
```

#### Table: `search_analytics`
```sql
CREATE TABLE search_analytics (
    search_time         TIMESTAMPTZ NOT NULL,
    
    -- Query
    query_text          TEXT NOT NULL,
    query_normalized    TEXT NOT NULL,
    
    -- User
    user_id             UUID,
    session_id          VARCHAR(100) NOT NULL,
    
    -- Results
    results_count       INTEGER NOT NULL,
    
    -- Engagement
    clicked_position    INTEGER,
    clicked_product_id  UUID,
    
    -- Conversion
    led_to_add_cart     BOOLEAN DEFAULT false,
    led_to_purchase     BOOLEAN DEFAULT false,
    
    -- Zero result
    is_zero_result      BOOLEAN DEFAULT false,
    
    PRIMARY KEY (query_normalized, search_time)
);

SELECT create_hypertable('search_analytics', 'search_time',
    chunk_time_interval => INTERVAL '1 day');

CREATE INDEX idx_search_zero ON search_analytics (is_zero_result, search_time DESC)
    WHERE is_zero_result = true;
```

#### Table: `product_performance_daily`
```sql
CREATE TABLE product_performance_daily (
    date                DATE NOT NULL,
    product_id          UUID NOT NULL,
    seller_id           UUID NOT NULL,
    category_id         UUID,
    
    -- Views
    view_count          INTEGER NOT NULL DEFAULT 0,
    unique_viewers      INTEGER NOT NULL DEFAULT 0,
    
    -- Cart
    add_to_cart_count   INTEGER NOT NULL DEFAULT 0,
    cart_conversion_rate DECIMAL(5, 4),
    
    -- Purchase
    order_count         INTEGER NOT NULL DEFAULT 0,
    units_sold          INTEGER NOT NULL DEFAULT 0,
    revenue             DECIMAL(15, 2) NOT NULL DEFAULT 0,
    purchase_conversion_rate DECIMAL(5, 4),
    
    -- Engagement
    wishlist_adds       INTEGER NOT NULL DEFAULT 0,
    share_count         INTEGER NOT NULL DEFAULT 0,
    
    -- Ranking
    search_impressions  INTEGER NOT NULL DEFAULT 0,
    search_clicks       INTEGER NOT NULL DEFAULT 0,
    search_ctr          DECIMAL(5, 4),
    
    PRIMARY KEY (date, product_id)
);

CREATE INDEX idx_product_perf_date ON product_performance_daily (date DESC);
CREATE INDEX idx_product_perf_seller ON product_performance_daily (seller_id, date DESC);
CREATE INDEX idx_product_perf_category ON product_performance_daily (category_id, date DESC);
```

#### Table: `seller_performance_daily`
```sql
CREATE TABLE seller_performance_daily (
    date                DATE NOT NULL,
    seller_id           UUID NOT NULL,
    
    -- Traffic
    store_views         INTEGER NOT NULL DEFAULT 0,
    product_views       INTEGER NOT NULL DEFAULT 0,
    unique_visitors     INTEGER NOT NULL DEFAULT 0,
    
    -- Conversion
    orders_count        INTEGER NOT NULL DEFAULT 0,
    units_sold          INTEGER NOT NULL DEFAULT 0,
    gmv                 DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- Gross Merchandise Value
    
    -- Order fulfillment
    orders_shipped      INTEGER NOT NULL DEFAULT 0,
    orders_delivered    INTEGER NOT NULL DEFAULT 0,
    avg_fulfillment_days DECIMAL(5, 2),
    
    -- Returns & Refunds
    return_count        INTEGER NOT NULL DEFAULT 0,
    refund_count        INTEGER NOT NULL DEFAULT 0,
    refund_amount       DECIMAL(15, 2) NOT NULL DEFAULT 0,
    
    -- Reviews
    reviews_received    INTEGER NOT NULL DEFAULT 0,
    avg_rating          DECIMAL(3, 2),
    
    -- Chat
    chat_response_rate  DECIMAL(5, 4),
    avg_response_time_minutes INTEGER,
    
    PRIMARY KEY (date, seller_id)
);

CREATE INDEX idx_seller_perf_date ON seller_performance_daily (date DESC);
```

### 1.3 Fraud Detection Schema

#### Table: `fraud_alerts`
```sql
CREATE TABLE fraud_alerts (
    alert_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_time          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Subject
    alert_type          VARCHAR(30) NOT NULL,
    /*
    Types:
    - ACCOUNT: suspicious_signup, account_takeover, multiple_accounts
    - PAYMENT: card_fraud, chargeback_risk, unusual_amount
    - ORDER: velocity_abuse, promo_abuse, fake_order
    - REVIEW: fake_review, review_manipulation
    - SELLER: fake_listings, counterfeit_risk
    */
    
    -- Target
    target_type         VARCHAR(20) NOT NULL,  -- USER, SELLER, ORDER, PAYMENT
    target_id           UUID NOT NULL,
    
    -- Risk assessment
    risk_score          DECIMAL(5, 2) NOT NULL,  -- 0-100
    risk_level          VARCHAR(10) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    
    -- Evidence
    signals             JSONB NOT NULL,
    /*
    {
        "signals": [
            {"name": "velocity_spike", "score": 30, "details": "5 orders in 1 hour"},
            {"name": "new_device", "score": 20, "details": "First seen device"},
            {"name": "high_value", "score": 25, "details": "Order 10x average"}
        ],
        "context": {
            "ip_address": "...",
            "device_id": "...",
            "session_id": "..."
        }
    }
    */
    
    -- Rules triggered
    rules_triggered     UUID[],
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (
        status IN ('OPEN', 'INVESTIGATING', 'CONFIRMED', 'FALSE_POSITIVE', 'RESOLVED')
    ),
    
    -- Resolution
    resolution          TEXT,
    action_taken        VARCHAR(50),  -- BLOCK, WARN, FLAG, NONE
    resolved_by         UUID,
    resolved_at         TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

SELECT create_hypertable('fraud_alerts', 'alert_time',
    chunk_time_interval => INTERVAL '1 month');

CREATE INDEX idx_fraud_alerts_target ON fraud_alerts (target_type, target_id, alert_time DESC);
CREATE INDEX idx_fraud_alerts_status ON fraud_alerts (status, risk_level, alert_time DESC);
CREATE INDEX idx_fraud_alerts_type ON fraud_alerts (alert_type, alert_time DESC);
```

#### Table: `fraud_rules`
```sql
CREATE TABLE fraud_rules (
    rule_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Rule info
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    
    -- Type
    alert_type          VARCHAR(30) NOT NULL,
    target_type         VARCHAR(20) NOT NULL,
    
    -- Condition (DSL or SQL-like)
    condition           JSONB NOT NULL,
    /*
    {
        "type": "threshold",
        "metric": "orders_per_hour",
        "operator": ">",
        "value": 5,
        "window": "1 hour"
    }
    
    OR
    
    {
        "type": "composite",
        "operator": "AND",
        "conditions": [
            {"metric": "device_age_days", "operator": "<", "value": 1},
            {"metric": "order_value", "operator": ">", "value": 5000000}
        ]
    }
    */
    
    -- Risk contribution
    risk_score_contribution INTEGER NOT NULL DEFAULT 10,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID
);

CREATE INDEX idx_fraud_rules_type ON fraud_rules (alert_type, target_type, is_active);
```

#### Table: `user_risk_profiles`
```sql
CREATE TABLE user_risk_profiles (
    user_id             UUID PRIMARY KEY,
    
    -- Current risk
    risk_score          DECIMAL(5, 2) NOT NULL DEFAULT 0,
    risk_level          VARCHAR(10) NOT NULL DEFAULT 'LOW',
    
    -- History
    total_alerts        INTEGER NOT NULL DEFAULT 0,
    confirmed_frauds    INTEGER NOT NULL DEFAULT 0,
    false_positives     INTEGER NOT NULL DEFAULT 0,
    
    -- Behavioral signals
    signals             JSONB NOT NULL DEFAULT '{}',
    /*
    {
        "account_age_days": 365,
        "verified_email": true,
        "verified_phone": true,
        "total_orders": 50,
        "total_spent": 25000000,
        "refund_rate": 0.02,
        "dispute_rate": 0.01,
        "device_count": 3,
        "address_count": 2,
        "payment_method_count": 2,
        "last_fraud_check": "2025-02-01T12:00:00Z"
    }
    */
    
    -- Restrictions
    is_restricted       BOOLEAN NOT NULL DEFAULT false,
    restriction_type    VARCHAR(30),  -- PAYMENT_LIMIT, ORDER_LIMIT, SUSPENDED
    restriction_reason  TEXT,
    restricted_at       TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_risk_level ON user_risk_profiles (risk_level, risk_score DESC);
CREATE INDEX idx_user_risk_restricted ON user_risk_profiles (is_restricted) WHERE is_restricted = true;
```

#### Table: `seller_risk_profiles`
```sql
CREATE TABLE seller_risk_profiles (
    seller_id           UUID PRIMARY KEY,
    
    -- Current risk
    risk_score          DECIMAL(5, 2) NOT NULL DEFAULT 0,
    risk_level          VARCHAR(10) NOT NULL DEFAULT 'LOW',
    
    -- History
    total_alerts        INTEGER NOT NULL DEFAULT 0,
    confirmed_violations INTEGER NOT NULL DEFAULT 0,
    
    -- Performance signals
    signals             JSONB NOT NULL DEFAULT '{}',
    /*
    {
        "account_age_days": 180,
        "total_orders": 1000,
        "total_gmv": 500000000,
        "return_rate": 0.05,
        "refund_rate": 0.03,
        "dispute_rate": 0.01,
        "avg_rating": 4.5,
        "review_count": 500,
        "fake_review_alerts": 0,
        "fulfillment_rate": 0.98,
        "late_shipment_rate": 0.02,
        "response_rate": 0.95
    }
    */
    
    -- Restrictions
    is_restricted       BOOLEAN NOT NULL DEFAULT false,
    restriction_type    VARCHAR(30),
    restriction_reason  TEXT,
    restricted_at       TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_seller_risk_level ON seller_risk_profiles (risk_level, risk_score DESC);
```

### 1.4 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class AnalyticsEvent extends BaseEvent {
    protected Instant eventTime;
    protected String eventCategory;
}

@DomainEvent(aggregateType = "Behavior")
public class BehaviorTrackedEvent extends AnalyticsEvent {
    private String eventType;
    private String userId;
    private String sessionId;
    private String deviceType;
    private String pageUrl;
    private Map<String, Object> properties;
}

@DomainEvent(aggregateType = "Session")
public class SessionStartedEvent extends AnalyticsEvent {
    private String sessionId;
    private String userId;
    private String deviceId;
    private String landingPage;
    private String referrerType;
    private String utmSource;
}

@DomainEvent(aggregateType = "Session")
public class SessionConvertedEvent extends AnalyticsEvent {
    private String sessionId;
    private String orderId;
    private BigDecimal conversionValue;
}

@DomainEvent(aggregateType = "Fraud")
public class FraudAlertCreatedEvent extends AnalyticsEvent {
    private String alertId;
    private String alertType;
    private String targetType;
    private String targetId;
    private BigDecimal riskScore;
    private String riskLevel;
    private List<FraudSignal> signals;
}

@DomainEvent(aggregateType = "Fraud")
public class FraudAlertResolvedEvent extends AnalyticsEvent {
    private String alertId;
    private String resolution;
    private String actionTaken;
    private String resolvedBy;
}

@DomainEvent(aggregateType = "UserRisk")
public class UserRiskUpdatedEvent extends AnalyticsEvent {
    private String userId;
    private BigDecimal previousScore;
    private BigDecimal newScore;
    private String previousLevel;
    private String newLevel;
    private String reason;
}
```

---

## 2. AI Agent Service

### 2.1 Overview

AI Agent Service implements **RAG (Retrieval-Augmented Generation)** pattern using:
- **pgvector** extension cho vector similarity search
- **Knowledge Base** với hierarchical document structure
- **Conversation Management** cho context continuity
- **Intent Classification** cho routing queries

### 2.2 Database Schema

#### Extension Setup
```sql
-- Enable pgvector for embeddings
CREATE EXTENSION IF NOT EXISTS vector;
```

#### Table: `knowledge_bases`
```sql
CREATE TABLE knowledge_bases (
    kb_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Basic info
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    
    -- Type
    kb_type             VARCHAR(30) NOT NULL CHECK (
        kb_type IN ('FAQ', 'PRODUCT', 'POLICY', 'SUPPORT', 'GENERAL')
    ),
    
    -- Scope
    scope               VARCHAR(20) NOT NULL DEFAULT 'GLOBAL' CHECK (
        scope IN ('GLOBAL', 'SELLER', 'CATEGORY')
    ),
    scope_id            UUID,  -- seller_id or category_id if scoped
    
    -- Embedding config
    embedding_model     VARCHAR(100) NOT NULL DEFAULT 'text-embedding-3-small',
    embedding_dimension INTEGER NOT NULL DEFAULT 1536,
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('BUILDING', 'ACTIVE', 'INACTIVE', 'ARCHIVED')
    ),
    
    -- Stats
    document_count      INTEGER NOT NULL DEFAULT 0,
    chunk_count         INTEGER NOT NULL DEFAULT 0,
    last_updated_at     TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID
);

CREATE INDEX idx_kb_type ON knowledge_bases (kb_type, status);
CREATE INDEX idx_kb_scope ON knowledge_bases (scope, scope_id) WHERE scope != 'GLOBAL';
```

#### Table: `knowledge_documents`
```sql
CREATE TABLE knowledge_documents (
    document_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id               UUID NOT NULL REFERENCES knowledge_bases(kb_id),
    
    -- Document info
    title               VARCHAR(500) NOT NULL,
    source_url          TEXT,
    source_type         VARCHAR(30) NOT NULL,  -- WEB, FILE, API, MANUAL
    
    -- Content
    content             TEXT NOT NULL,
    content_hash        VARCHAR(64) NOT NULL,  -- For deduplication
    
    -- Metadata
    metadata            JSONB NOT NULL DEFAULT '{}',
    /*
    {
        "category": "returns",
        "tags": ["refund", "policy"],
        "language": "vi",
        "last_verified": "2025-01-15",
        "author": "support-team"
    }
    */
    
    -- Processing status
    processing_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (
        processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    error_message       TEXT,
    
    -- Chunk stats
    chunk_count         INTEGER NOT NULL DEFAULT 0,
    
    -- Versioning
    version             INTEGER NOT NULL DEFAULT 1,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_docs_kb ON knowledge_documents (kb_id, processing_status);
CREATE INDEX idx_docs_hash ON knowledge_documents (content_hash);
CREATE INDEX idx_docs_metadata ON knowledge_documents USING gin(metadata);
```

#### Table: `knowledge_chunks`
```sql
CREATE TABLE knowledge_chunks (
    chunk_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES knowledge_documents(document_id) ON DELETE CASCADE,
    kb_id               UUID NOT NULL REFERENCES knowledge_bases(kb_id),
    
    -- Position
    chunk_index         INTEGER NOT NULL,
    
    -- Content
    content             TEXT NOT NULL,
    content_tokens      INTEGER NOT NULL,
    
    -- Embedding
    embedding           vector(1536) NOT NULL,
    
    -- Context (for better retrieval)
    title_context       TEXT,  -- Document title
    section_context     TEXT,  -- Section heading
    
    -- Metadata (inherited + chunk-specific)
    metadata            JSONB NOT NULL DEFAULT '{}',
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_document_chunk UNIQUE (document_id, chunk_index)
);

-- Vector similarity index (IVFFlat for large datasets)
CREATE INDEX idx_chunks_embedding ON knowledge_chunks 
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE INDEX idx_chunks_kb ON knowledge_chunks (kb_id);
CREATE INDEX idx_chunks_document ON knowledge_chunks (document_id);
CREATE INDEX idx_chunks_metadata ON knowledge_chunks USING gin(metadata);
```

#### Table: `intent_definitions`
```sql
CREATE TABLE intent_definitions (
    intent_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Intent info
    intent_code         VARCHAR(100) NOT NULL UNIQUE,
    intent_name         VARCHAR(200) NOT NULL,
    description         TEXT,
    
    -- Category
    category            VARCHAR(50) NOT NULL,
    /*
    Categories:
    - ORDER: order_status, order_track, order_cancel
    - PAYMENT: payment_issue, refund_request, payment_method
    - PRODUCT: product_inquiry, stock_check, price_inquiry
    - SHIPPING: shipping_status, shipping_method, delivery_time
    - ACCOUNT: account_issue, profile_update, password_reset
    - SUPPORT: general_inquiry, complaint, feedback
    */
    
    -- Example utterances (for training/matching)
    examples            TEXT[] NOT NULL,
    
    -- Response template
    response_template   TEXT,
    
    -- Required entities
    required_entities   VARCHAR(50)[],  -- e.g., ['order_id', 'product_id']
    
    -- Handling
    handler_type        VARCHAR(20) NOT NULL DEFAULT 'BOT' CHECK (
        handler_type IN ('BOT', 'HUMAN', 'HYBRID', 'API')
    ),
    handler_config      JSONB,
    /*
    For API handler:
    {
        "service": "order-service",
        "endpoint": "/api/orders/{order_id}/status",
        "method": "GET"
    }
    */
    
    -- Priority
    priority            INTEGER NOT NULL DEFAULT 0,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_intents_category ON intent_definitions (category, is_active);
CREATE INDEX idx_intents_handler ON intent_definitions (handler_type, is_active);
```

#### Table: `conversations`
```sql
CREATE TABLE conversations (
    conversation_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- User
    user_id             UUID,
    session_id          VARCHAR(100) NOT NULL,
    
    -- Channel
    channel             VARCHAR(20) NOT NULL,  -- WEB_CHAT, APP_CHAT, MESSENGER, ZALO
    room_id             UUID,  -- Reference to Chat Service room
    
    -- Context
    context_type        VARCHAR(30),  -- ORDER, PRODUCT, GENERAL
    context_id          UUID,  -- order_id, product_id
    context_data        JSONB,
    
    -- Intents detected
    primary_intent      VARCHAR(100),
    detected_intents    VARCHAR(100)[],
    
    -- Entities extracted
    entities            JSONB,
    /*
    {
        "order_id": "ORD-123456",
        "product_name": "áo thun nam",
        "date_range": {"from": "2025-01-01", "to": "2025-01-31"}
    }
    */
    
    -- Handler
    current_handler     VARCHAR(20) NOT NULL DEFAULT 'BOT',
    assigned_agent_id   UUID,  -- If escalated to human
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'WAITING_USER', 'WAITING_AGENT', 'RESOLVED', 'ABANDONED')
    ),
    
    -- Resolution
    resolution_type     VARCHAR(30),  -- SELF_SERVICE, BOT_RESOLVED, AGENT_RESOLVED
    resolution_notes    TEXT,
    
    -- Satisfaction
    csat_score          SMALLINT CHECK (csat_score BETWEEN 1 AND 5),
    csat_feedback       TEXT,
    
    -- Timestamps
    started_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_message_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ,
    
    -- Metrics
    turn_count          INTEGER NOT NULL DEFAULT 0,
    bot_turn_count      INTEGER NOT NULL DEFAULT 0,
    agent_turn_count    INTEGER NOT NULL DEFAULT 0,
    first_response_time_ms INTEGER,
    total_handle_time_ms INTEGER
);

CREATE INDEX idx_conversations_user ON conversations (user_id, started_at DESC) 
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_conversations_session ON conversations (session_id, started_at DESC);
CREATE INDEX idx_conversations_status ON conversations (status, last_message_at DESC);
CREATE INDEX idx_conversations_agent ON conversations (assigned_agent_id, status) 
    WHERE assigned_agent_id IS NOT NULL;
CREATE INDEX idx_conversations_intent ON conversations (primary_intent, started_at DESC);
```

#### Table: `conversation_turns`
```sql
CREATE TABLE conversation_turns (
    turn_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID NOT NULL REFERENCES conversations(conversation_id),
    
    -- Sequence
    turn_number         INTEGER NOT NULL,
    
    -- Sender
    sender_type         VARCHAR(20) NOT NULL CHECK (
        sender_type IN ('USER', 'BOT', 'AGENT', 'SYSTEM')
    ),
    sender_id           UUID,
    
    -- Content
    message_type        VARCHAR(20) NOT NULL DEFAULT 'TEXT',  -- TEXT, QUICK_REPLY, CARD, CAROUSEL
    content             TEXT NOT NULL,
    content_data        JSONB,  -- For rich content
    
    -- Intent & Entities (for user messages)
    detected_intent     VARCHAR(100),
    intent_confidence   DECIMAL(5, 4),
    extracted_entities  JSONB,
    
    -- Knowledge retrieval (for bot responses)
    retrieved_chunks    UUID[],  -- References to knowledge_chunks
    retrieval_scores    DECIMAL(5, 4)[],
    
    -- Generation metadata
    model_used          VARCHAR(100),
    prompt_tokens       INTEGER,
    completion_tokens   INTEGER,
    latency_ms          INTEGER,
    
    -- Timestamp
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_conversation_turn UNIQUE (conversation_id, turn_number)
);

CREATE INDEX idx_turns_conversation ON conversation_turns (conversation_id, turn_number);
CREATE INDEX idx_turns_time ON conversation_turns (created_at DESC);
```

#### Table: `prompt_templates`
```sql
CREATE TABLE prompt_templates (
    template_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Template info
    template_code       VARCHAR(100) NOT NULL UNIQUE,
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    
    -- Type
    template_type       VARCHAR(30) NOT NULL CHECK (
        template_type IN ('SYSTEM', 'RAG', 'INTENT', 'ENTITY', 'RESPONSE', 'SUMMARY')
    ),
    
    -- Content
    template            TEXT NOT NULL,
    /*
    Variables: {{context}}, {{user_query}}, {{knowledge}}, {{entities}}, {{history}}
    
    Example:
    "You are a helpful e-commerce assistant for {{company_name}}.
    
    Context from knowledge base:
    {{knowledge}}
    
    Conversation history:
    {{history}}
    
    User query: {{user_query}}
    
    Provide a helpful response in Vietnamese."
    */
    
    -- Variables definition
    variables           JSONB NOT NULL DEFAULT '[]',
    
    -- Model config
    model               VARCHAR(100) NOT NULL DEFAULT 'gpt-4o-mini',
    temperature         DECIMAL(2, 1) NOT NULL DEFAULT 0.7,
    max_tokens          INTEGER NOT NULL DEFAULT 500,
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    -- Version control
    version             INTEGER NOT NULL DEFAULT 1,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_templates_type ON prompt_templates (template_type, is_active);
```

#### Table: `training_feedback`
```sql
-- Collect feedback for model improvement
CREATE TABLE training_feedback (
    feedback_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Source
    conversation_id     UUID NOT NULL REFERENCES conversations(conversation_id),
    turn_id             UUID NOT NULL REFERENCES conversation_turns(turn_id),
    
    -- Feedback type
    feedback_type       VARCHAR(30) NOT NULL CHECK (
        feedback_type IN ('THUMBS_UP', 'THUMBS_DOWN', 'CORRECTION', 'ESCALATION')
    ),
    
    -- Rating
    rating              SMALLINT,  -- 1-5
    
    -- Details
    user_query          TEXT NOT NULL,
    bot_response        TEXT NOT NULL,
    correct_response    TEXT,  -- If correction provided
    
    -- Intent correction
    detected_intent     VARCHAR(100),
    correct_intent      VARCHAR(100),
    
    -- Feedback notes
    notes               TEXT,
    
    -- Processing
    is_processed        BOOLEAN NOT NULL DEFAULT false,
    processed_at        TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID  -- User or agent who provided feedback
);

CREATE INDEX idx_feedback_conversation ON training_feedback (conversation_id);
CREATE INDEX idx_feedback_type ON training_feedback (feedback_type, is_processed);
CREATE INDEX idx_feedback_intent ON training_feedback (detected_intent, correct_intent) 
    WHERE correct_intent IS NOT NULL;
```

#### Table: `model_versions`
```sql
-- Track model versions and deployments
CREATE TABLE model_versions (
    version_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Model info
    model_type          VARCHAR(50) NOT NULL,  -- INTENT, EMBEDDING, GENERATION
    model_name          VARCHAR(200) NOT NULL,
    version             VARCHAR(50) NOT NULL,
    
    -- Artifacts
    artifact_path       TEXT,  -- S3 path or model registry URL
    
    -- Configuration
    config              JSONB,
    
    -- Metrics
    metrics             JSONB,
    /*
    {
        "accuracy": 0.95,
        "f1_score": 0.93,
        "latency_p50_ms": 50,
        "latency_p99_ms": 200
    }
    */
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'TRAINING' CHECK (
        status IN ('TRAINING', 'VALIDATING', 'READY', 'DEPLOYED', 'DEPRECATED')
    ),
    
    -- Deployment
    is_production       BOOLEAN NOT NULL DEFAULT false,
    deployed_at         TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID
);

CREATE INDEX idx_models_type ON model_versions (model_type, status);
CREATE INDEX idx_models_production ON model_versions (model_type, is_production) 
    WHERE is_production = true;
```

### 2.3 Vector Search Examples

```sql
-- Find similar knowledge chunks
SELECT 
    chunk_id,
    content,
    title_context,
    1 - (embedding <=> $1) AS similarity
FROM knowledge_chunks
WHERE kb_id = $2
ORDER BY embedding <=> $1
LIMIT 5;

-- Hybrid search (vector + keyword)
WITH vector_results AS (
    SELECT chunk_id, content, 1 - (embedding <=> $1) AS vector_score
    FROM knowledge_chunks
    WHERE kb_id = $2
    ORDER BY embedding <=> $1
    LIMIT 20
),
keyword_results AS (
    SELECT chunk_id, content,
           ts_rank(to_tsvector('vietnamese', content), plainto_tsquery('vietnamese', $3)) AS keyword_score
    FROM knowledge_chunks
    WHERE kb_id = $2 
      AND to_tsvector('vietnamese', content) @@ plainto_tsquery('vietnamese', $3)
    LIMIT 20
)
SELECT 
    COALESCE(v.chunk_id, k.chunk_id) AS chunk_id,
    COALESCE(v.content, k.content) AS content,
    COALESCE(v.vector_score, 0) AS vector_score,
    COALESCE(k.keyword_score, 0) AS keyword_score,
    (COALESCE(v.vector_score, 0) * 0.7 + COALESCE(k.keyword_score, 0) * 0.3) AS combined_score
FROM vector_results v
FULL OUTER JOIN keyword_results k ON v.chunk_id = k.chunk_id
ORDER BY combined_score DESC
LIMIT 5;
```

### 2.4 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class AIAgentEvent extends BaseEvent {
    protected String conversationId;
}

@DomainEvent(aggregateType = "Conversation")
public class ConversationStartedEvent extends AIAgentEvent {
    private String userId;
    private String sessionId;
    private String channel;
    private String contextType;
    private String contextId;
}

@DomainEvent(aggregateType = "Conversation")
public class UserMessageReceivedEvent extends AIAgentEvent {
    private String turnId;
    private String content;
    private String detectedIntent;
    private BigDecimal intentConfidence;
    private Map<String, Object> extractedEntities;
}

@DomainEvent(aggregateType = "Conversation")
public class KnowledgeRetrievedEvent extends AIAgentEvent {
    private String turnId;
    private List<String> chunkIds;
    private List<BigDecimal> scores;
    private int totalTokens;
}

@DomainEvent(aggregateType = "Conversation")
public class BotResponseGeneratedEvent extends AIAgentEvent {
    private String turnId;
    private String content;
    private String modelUsed;
    private int promptTokens;
    private int completionTokens;
    private int latencyMs;
}

@DomainEvent(aggregateType = "Conversation")
public class ConversationEscalatedEvent extends AIAgentEvent {
    private String fromHandler;
    private String toHandler;
    private String agentId;
    private String reason;
}

@DomainEvent(aggregateType = "Conversation")
public class ConversationResolvedEvent extends AIAgentEvent {
    private String resolutionType;
    private Integer csatScore;
    private String feedback;
}

@DomainEvent(aggregateType = "KnowledgeBase")
public class DocumentIndexedEvent extends BaseEvent {
    private String kbId;
    private String documentId;
    private int chunkCount;
    private int totalTokens;
}

@DomainEvent(aggregateType = "Training")
public class FeedbackReceivedEvent extends BaseEvent {
    private String conversationId;
    private String feedbackType;
    private String userQuery;
    private String botResponse;
    private String correctResponse;
}
```

---

## 3. Cross-Service Integration

### 3.1 Event Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    Intelligence Services Event Flow                      │
└──────────────────────────────────────────────────────────────────────────┘

All Services ──────────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── *Event (Order, Payment, Shipping, etc.) ─────────────────────────▶│
    │    (Behavior tracking, metrics)                       Analytics Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

Analytics Service ─────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── FraudAlertCreatedEvent ──────────────────────────────────────────▶│
    │    (Block/restrict user)                                 User Service
    │                                                                      │
    │─── FraudAlertCreatedEvent ──────────────────────────────────────────▶│
    │    (Hold payment)                                     Payment Service
    │                                                                      │
    │─── UserRiskUpdatedEvent ────────────────────────────────────────────▶│
    │    (Update user restrictions)                            User Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

AI Agent Service ──────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── ConversationStartedEvent ────────────────────────────────────────▶│
    │    (Track bot interactions)                           Analytics Service
    │                                                                      │
    │─── ConversationEscalatedEvent ──────────────────────────────────────▶│
    │    (Notify support, create ticket)                Notification Service
    │                                                                      │
    │─── ConversationResolvedEvent ───────────────────────────────────────▶│
    │    (Update metrics)                                  Analytics Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

Chat Service ──────────────────────────────────────────────────────────────┐
    │                                                                      │
    │─── MessageSentEvent ────────────────────────────────────────────────▶│
    │    (Process with AI if bot active)                   AI Agent Service
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘
```

### 3.2 RAG Pipeline

```
┌────────────────────────────────────────────────────────────────────────┐
│                         RAG Pipeline                                   │
└────────────────────────────────────────────────────────────────────────┘

User Query                            AI Agent Service
    │                                        │
    │─── "Tôi muốn kiểm tra đơn hàng" ──────▶│
    │                                        │
    │                              ┌─────────┴─────────┐
    │                              │ 1. Intent         │
    │                              │    Classification │
    │                              └─────────┬─────────┘
    │                                        │
    │                              Intent: order_status
    │                              Entities: none
    │                                        │
    │                              ┌─────────┴─────────┐
    │                              │ 2. Entity         │
    │                              │    Extraction     │
    │                              └─────────┬─────────┘
    │                                        │
    │                              Need: order_id
    │                                        │
    │◀─── "Vui lòng cho tôi biết..." ────────│
    │                                        │
    │─── "ORD-123456" ──────────────────────▶│
    │                                        │
    │                              ┌─────────┴─────────┐
    │                              │ 3. Knowledge      │
    │                              │    Retrieval      │
    │                              └─────────┬─────────┘
    │                                        │
    │                              ┌─────────┴─────────┐
    │                              │    pgvector       │
    │                              │    Search         │
    │                              └─────────┬─────────┘
    │                                        │
    │                              ┌─────────┴─────────┐
    │                              │ 4. Context        │
    │                              │    Assembly       │
    │                              └─────────┬─────────┘
    │                                        │
    │                              - Knowledge chunks
    │                              - Order data (API call)
    │                              - Conversation history
    │                                        │
    │                              ┌─────────┴─────────┐
    │                              │ 5. Response       │
    │                              │    Generation     │
    │                              └─────────┬─────────┘
    │                                        │
    │◀─── "Đơn hàng ORD-123456 của..." ──────│
```

---

## 4. Kafka Topics & Redis Caching

### 4.1 Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `behavior-events` | All Services | Analytics Service | BehaviorEvent |
| `analytics-metrics` | Analytics Service | Dashboard, Alerts | MetricsUpdate |
| `fraud-alerts` | Analytics Service | User, Payment, Notification | FraudAlertEvent |
| `ai-conversations` | AI Agent Service | Analytics | ConversationEvent |
| `knowledge-updates` | Admin/CMS | AI Agent Service | KnowledgeUpdateEvent |

### 4.2 Redis Caching

| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `session:{session_id}` | 30 min | Session state |
| `metrics:realtime:{metric}` | 1 min | Real-time counters |
| `funnel:{funnel_id}:today` | Until midnight | Today's funnel metrics |
| `risk:user:{user_id}` | 5 min | User risk profile cache |
| `risk:seller:{seller_id}` | 5 min | Seller risk profile cache |
| `ai:context:{conversation_id}` | 1 hour | Conversation context |
| `ai:kb:{kb_id}:stats` | 10 min | Knowledge base stats |
| `ai:intent:cache:{hash}` | 30 min | Intent classification cache |
| `ai:embedding:cache:{hash}` | 1 hour | Embedding cache |

### 4.3 Redis Data Structures

```
# Real-time session tracking (Hash)
HSET session:abc-123
    "user_id" "user-1"
    "started_at" "2025-02-01T12:00:00Z"
    "page_views" "5"
    "cart_value" "500000"
EXPIRE session:abc-123 1800

# Real-time metrics (Sorted Set for time-series)
ZADD metrics:realtime:orders {timestamp} {order_id}
ZREMRANGEBYSCORE metrics:realtime:orders 0 {timestamp - 3600}

# User risk cache (String)
SET risk:user:user-1 '{"score": 15, "level": "LOW", "restricted": false}'
EXPIRE risk:user:user-1 300

# AI conversation context (Hash)
HSET ai:context:conv-123
    "intent" "order_status"
    "entities" '{"order_id": "ORD-123"}'
    "turns" "3"
    "handler" "BOT"
EXPIRE ai:context:conv-123 3600

# Intent classification cache (String)
SET ai:intent:cache:{query_hash} '{"intent": "order_status", "confidence": 0.95}'
EXPIRE ai:intent:cache:{query_hash} 1800

# Knowledge base query cache (String)
SET ai:kb:query:{kb_id}:{query_hash} '[{"chunk_id": "...", "score": 0.92}, ...]'
EXPIRE ai:kb:query:{kb_id}:{query_hash} 3600
```

---

## Summary

### Tables Created

| Service | Database | Tables | Purpose |
|---------|----------|--------|---------|
| **Analytics** | TimescaleDB | behavior_events, user_sessions, hourly_metrics*, daily_conversions*, funnel_definitions, funnel_metrics_daily, search_analytics, product_performance_daily, seller_performance_daily, fraud_alerts, fraud_rules, user_risk_profiles, seller_risk_profiles | Behavior tracking & fraud detection |
| **AI Agent** | PostgreSQL + pgvector | knowledge_bases, knowledge_documents, knowledge_chunks, intent_definitions, conversations, conversation_turns, prompt_templates, training_feedback, model_versions | RAG-based AI assistant |

\* = Continuous Aggregates (materialized views)

### Key Design Decisions

1. **TimescaleDB** for time-series analytics with automatic partitioning
2. **Continuous Aggregates** for pre-computed metrics (hourly, daily)
3. **Compression policies** for cost-effective storage (7-day window)
4. **Retention policies** for automatic data lifecycle (90-day raw data)
5. **pgvector** for semantic search in knowledge base
6. **IVFFlat index** for scalable vector similarity search
7. **Hybrid search** combining vector + keyword for better relevance
8. **Feedback loop** for continuous model improvement
9. **Risk profiles** with real-time signal updates
10. **Fraud rules engine** with configurable conditions
