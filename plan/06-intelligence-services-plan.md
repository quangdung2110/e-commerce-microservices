# Implementation Plan: Intelligence Services Group

> **Services**: Analytics Service, AI Agent Service  
> **Priority**: ⭐⭐⭐ MEDIUM (Data insights & AI features)  
> **Estimated Time**: 4-5 weeks  
> **Dependencies**: All other services (data consumers), Common module

---

## 📋 Overview

This group provides intelligence layer: TimescaleDB-based analytics for behavioral tracking and fraud detection, plus AI-powered features using pgvector for semantic search and recommendations.

### Key Challenges
1. **Time-Series Data**: High-volume event ingestion with continuous aggregates
2. **Fraud Detection**: Real-time scoring based on behavioral patterns
3. **Funnel Analytics**: Track conversion across order journey
4. **Vector Embeddings**: Store product/user embeddings for similarity search
5. **AI Agent Context**: Maintain conversation history with context retrieval
6. **Performance**: Handle 10K+ events/sec with low latency queries

---

## Architecture Decisions

### 1. Analytics Service - TimescaleDB Hypertables

```sql
-- Automatic partitioning by time
CREATE TABLE behavior_events (
    event_id UUID,
    user_id UUID,
    session_id UUID,
    event_type TEXT,
    event_data JSONB,
    created_at TIMESTAMPTZ NOT NULL
);

-- Convert to hypertable (auto-partition by created_at)
SELECT create_hypertable('behavior_events', 'created_at');

-- Continuous aggregates for real-time metrics
CREATE MATERIALIZED VIEW daily_user_activity
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 day', created_at) AS day,
    user_id,
    COUNT(*) AS event_count,
    COUNT(DISTINCT session_id) AS session_count
FROM behavior_events
GROUP BY day, user_id;
```

### 2. AI Agent Service - pgvector + RAG Pattern

```
User Query → Embedding → Vector Search → Top-K Context → LLM Prompt
                                                              ↓
                                                          Response
```

**Similarity Search**:
```sql
-- Install pgvector extension
CREATE EXTENSION vector;

-- Store product embeddings
CREATE TABLE product_embeddings (
    product_id UUID PRIMARY KEY,
    embedding VECTOR(1536),  -- OpenAI ada-002 dimensions
    updated_at TIMESTAMPTZ
);

-- Nearest neighbor search
SELECT product_id, embedding <-> '[...]' AS distance
FROM product_embeddings
ORDER BY embedding <-> '[...]'
LIMIT 10;
```

---

## Phase 1: Analytics Service - Event Ingestion (Week 1)

### 1.1 TimescaleDB Setup (Agent Task: DB-ANALYTICS-001)

**Schema**:
```sql
-- Behavior events hypertable
CREATE TABLE behavior_events (
    event_id UUID NOT NULL,
    user_id UUID,
    session_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    page_url TEXT,
    referrer_url TEXT,
    product_id UUID,
    category_id UUID,
    search_query TEXT,
    event_data JSONB,
    device_type VARCHAR(20),
    browser VARCHAR(50),
    os VARCHAR(50),
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Convert to hypertable
SELECT create_hypertable('behavior_events', 'created_at', 
    chunk_time_interval => INTERVAL '1 day');

-- Indexes for common queries
CREATE INDEX idx_behavior_user ON behavior_events(user_id, created_at DESC);
CREATE INDEX idx_behavior_session ON behavior_events(session_id, created_at DESC);
CREATE INDEX idx_behavior_type ON behavior_events(event_type, created_at DESC);
CREATE INDEX idx_behavior_product ON behavior_events(product_id, created_at DESC) 
WHERE product_id IS NOT NULL;

-- Compression policy (compress data older than 7 days)
SELECT add_compression_policy('behavior_events', INTERVAL '7 days');

-- Retention policy (drop data older than 1 year)
SELECT add_retention_policy('behavior_events', INTERVAL '1 year');

-- Order funnel events
CREATE TABLE funnel_events (
    event_id UUID NOT NULL,
    user_id UUID NOT NULL,
    session_id UUID NOT NULL,
    funnel_stage VARCHAR(30) NOT NULL,  -- BROWSE, ADD_TO_CART, CHECKOUT, PAYMENT, COMPLETE
    order_id UUID,
    cart_id UUID,
    product_ids UUID[],
    total_amount DECIMAL(15, 2),
    event_data JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

SELECT create_hypertable('funnel_events', 'created_at',
    chunk_time_interval => INTERVAL '1 day');

CREATE INDEX idx_funnel_user ON funnel_events(user_id, created_at DESC);
CREATE INDEX idx_funnel_session ON funnel_events(session_id, created_at DESC);
CREATE INDEX idx_funnel_stage ON funnel_events(funnel_stage, created_at DESC);
```

### 1.2 Continuous Aggregates (Agent Task: ANALYTICS-AGG-001)

**Pre-computed Metrics**:
```sql
-- Daily user activity
CREATE MATERIALIZED VIEW daily_user_activity
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 day', created_at) AS day,
    user_id,
    COUNT(*) AS event_count,
    COUNT(DISTINCT session_id) AS session_count,
    COUNT(DISTINCT CASE WHEN event_type = 'PRODUCT_VIEW' THEN product_id END) AS products_viewed,
    COUNT(DISTINCT CASE WHEN event_type = 'ADD_TO_CART' THEN product_id END) AS products_added_to_cart,
    COUNT(CASE WHEN event_type = 'SEARCH' THEN 1 END) AS search_count
FROM behavior_events
GROUP BY day, user_id;

-- Refresh policy (every hour)
SELECT add_continuous_aggregate_policy('daily_user_activity',
    start_offset => INTERVAL '1 month',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');

-- Product popularity (hourly)
CREATE MATERIALIZED VIEW hourly_product_metrics
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 hour', created_at) AS hour,
    product_id,
    COUNT(DISTINCT user_id) AS unique_viewers,
    COUNT(CASE WHEN event_type = 'PRODUCT_VIEW' THEN 1 END) AS view_count,
    COUNT(CASE WHEN event_type = 'ADD_TO_CART' THEN 1 END) AS add_to_cart_count,
    COUNT(CASE WHEN event_type = 'PURCHASE' THEN 1 END) AS purchase_count,
    AVG((event_data->>'time_spent_seconds')::INTEGER) AS avg_time_spent
FROM behavior_events
WHERE product_id IS NOT NULL
GROUP BY hour, product_id;

-- Funnel conversion rates (daily)
CREATE MATERIALIZED VIEW daily_funnel_metrics
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 day', created_at) AS day,
    COUNT(DISTINCT CASE WHEN funnel_stage = 'BROWSE' THEN session_id END) AS browse_count,
    COUNT(DISTINCT CASE WHEN funnel_stage = 'ADD_TO_CART' THEN session_id END) AS cart_count,
    COUNT(DISTINCT CASE WHEN funnel_stage = 'CHECKOUT' THEN session_id END) AS checkout_count,
    COUNT(DISTINCT CASE WHEN funnel_stage = 'PAYMENT' THEN session_id END) AS payment_count,
    COUNT(DISTINCT CASE WHEN funnel_stage = 'COMPLETE' THEN session_id END) AS complete_count
FROM funnel_events
GROUP BY day;
```

### 1.3 Event Ingestion Service (Agent Task: ANALYTICS-INGEST-001)

**Kafka Consumer**:
```java
@Component
@RequiredArgsConstructor
public class AnalyticsEventConsumer {
    private final JdbcTemplate jdbcTemplate;
    private final BatchEventProcessor batchProcessor;
    
    @KafkaListener(topics = {
        "order-events", 
        "product-events", 
        "cart-events",
        "user-events"
    }, groupId = "analytics-service-group")
    public void handleEvent(@Payload BaseEvent event) {
        BehaviorEvent behaviorEvent = mapToBehaviorEvent(event);
        batchProcessor.add(behaviorEvent);
    }
    
    @Component
    public static class BatchEventProcessor {
        private final List<BehaviorEvent> batch = new CopyOnWriteArrayList<>();
        private static final int BATCH_SIZE = 1000;
        
        public void add(BehaviorEvent event) {
            batch.add(event);
            
            if (batch.size() >= BATCH_SIZE) {
                flush();
            }
        }
        
        @Scheduled(fixedRate = 5000)  // Every 5 seconds
        public void flush() {
            if (batch.isEmpty()) return;
            
            List<BehaviorEvent> toInsert = new ArrayList<>(batch);
            batch.clear();
            
            String sql = "INSERT INTO behavior_events " +
                "(event_id, user_id, session_id, event_type, product_id, event_data, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)";
            
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    BehaviorEvent event = toInsert.get(i);
                    ps.setObject(1, event.getEventId());
                    ps.setObject(2, event.getUserId());
                    ps.setObject(3, event.getSessionId());
                    ps.setString(4, event.getEventType());
                    ps.setObject(5, event.getProductId());
                    ps.setString(6, event.getEventData());
                    ps.setTimestamp(7, Timestamp.from(event.getCreatedAt()));
                }
                
                @Override
                public int getBatchSize() {
                    return toInsert.size();
                }
            });
            
            log.info("Inserted {} behavior events", toInsert.size());
        }
    }
}
```

### 1.4 Analytics Query Service (Agent Task: ANALYTICS-QUERY-001)

**Common Queries**:
```java
@Service
@RequiredArgsConstructor
public class AnalyticsQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final RedisService redisService;
    
    public UserActivitySummary getUserActivity(UUID userId, Instant from, Instant to) {
        String sql = """
            SELECT 
                COUNT(*) AS total_events,
                COUNT(DISTINCT session_id) AS session_count,
                COUNT(DISTINCT product_id) FILTER (WHERE event_type = 'PRODUCT_VIEW') AS products_viewed,
                COUNT(*) FILTER (WHERE event_type = 'ADD_TO_CART') AS items_added_to_cart,
                COUNT(*) FILTER (WHERE event_type = 'SEARCH') AS search_count
            FROM behavior_events
            WHERE user_id = ? AND created_at BETWEEN ? AND ?
        """;
        
        return jdbcTemplate.queryForObject(sql, 
            (rs, rowNum) -> UserActivitySummary.builder()
                .totalEvents(rs.getInt("total_events"))
                .sessionCount(rs.getInt("session_count"))
                .productsViewed(rs.getInt("products_viewed"))
                .itemsAddedToCart(rs.getInt("items_added_to_cart"))
                .searchCount(rs.getInt("search_count"))
                .build(),
            userId, from, to
        );
    }
    
    public List<ProductMetrics> getTopProducts(Instant from, Instant to, int limit) {
        String cacheKey = "top_products:" + from + ":" + to;
        
        return redisService.get(cacheKey, new TypeReference<List<ProductMetrics>>() {})
            .orElseGet(() -> {
                String sql = """
                    SELECT 
                        product_id,
                        COUNT(DISTINCT user_id) AS unique_viewers,
                        COUNT(*) FILTER (WHERE event_type = 'PRODUCT_VIEW') AS view_count,
                        COUNT(*) FILTER (WHERE event_type = 'ADD_TO_CART') AS cart_count
                    FROM behavior_events
                    WHERE product_id IS NOT NULL AND created_at BETWEEN ? AND ?
                    GROUP BY product_id
                    ORDER BY view_count DESC
                    LIMIT ?
                """;
                
                List<ProductMetrics> metrics = jdbcTemplate.query(sql,
                    (rs, rowNum) -> ProductMetrics.builder()
                        .productId(rs.getObject("product_id", UUID.class))
                        .uniqueViewers(rs.getInt("unique_viewers"))
                        .viewCount(rs.getInt("view_count"))
                        .cartCount(rs.getInt("cart_count"))
                        .build(),
                    from, to, limit
                );
                
                redisService.set(cacheKey, metrics, Duration.ofMinutes(15));
                return metrics;
            });
    }
    
    public FunnelConversionMetrics getFunnelMetrics(Instant from, Instant to) {
        String sql = """
            SELECT 
                COUNT(DISTINCT CASE WHEN funnel_stage = 'BROWSE' THEN session_id END) AS browse,
                COUNT(DISTINCT CASE WHEN funnel_stage = 'ADD_TO_CART' THEN session_id END) AS cart,
                COUNT(DISTINCT CASE WHEN funnel_stage = 'CHECKOUT' THEN session_id END) AS checkout,
                COUNT(DISTINCT CASE WHEN funnel_stage = 'PAYMENT' THEN session_id END) AS payment,
                COUNT(DISTINCT CASE WHEN funnel_stage = 'COMPLETE' THEN session_id END) AS complete
            FROM funnel_events
            WHERE created_at BETWEEN ? AND ?
        """;
        
        return jdbcTemplate.queryForObject(sql,
            (rs, rowNum) -> {
                int browse = rs.getInt("browse");
                int cart = rs.getInt("cart");
                int checkout = rs.getInt("checkout");
                int payment = rs.getInt("payment");
                int complete = rs.getInt("complete");
                
                return FunnelConversionMetrics.builder()
                    .browseCount(browse)
                    .cartCount(cart)
                    .checkoutCount(checkout)
                    .paymentCount(payment)
                    .completeCount(complete)
                    .browseToCartRate(calculateRate(browse, cart))
                    .cartToCheckoutRate(calculateRate(cart, checkout))
                    .checkoutToPaymentRate(calculateRate(checkout, payment))
                    .paymentToCompleteRate(calculateRate(payment, complete))
                    .overallConversionRate(calculateRate(browse, complete))
                    .build();
            },
            from, to
        );
    }
    
    private double calculateRate(int from, int to) {
        return from == 0 ? 0.0 : (double) to / from * 100;
    }
}
```

---

## Phase 2: Fraud Detection (Week 2)

### 2.1 Fraud Rules Schema (Agent Task: DB-FRAUD-001)

**PostgreSQL Tables**:
```sql
CREATE TABLE fraud_rules (
    rule_id UUID PRIMARY KEY,
    rule_name VARCHAR(100) NOT NULL,
    rule_type VARCHAR(30) NOT NULL,  -- VELOCITY, PATTERN, SCORE_THRESHOLD
    condition_expr JSONB NOT NULL,
    action VARCHAR(30) NOT NULL,  -- BLOCK, FLAG, REVIEW
    severity VARCHAR(20) NOT NULL,  -- HIGH, MEDIUM, LOW
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_risk_profiles (
    user_id UUID PRIMARY KEY,
    risk_score INTEGER NOT NULL DEFAULT 0,  -- 0-100
    risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW',  -- LOW, MEDIUM, HIGH, CRITICAL
    fraud_indicators JSONB,
    flagged_events JSONB[],
    last_evaluated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE fraud_alerts (
    alert_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    rule_id UUID NOT NULL REFERENCES fraud_rules(rule_id),
    order_id UUID,
    alert_type VARCHAR(30) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    description TEXT,
    evidence JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 2.2 Fraud Detection Service (Agent Task: FRAUD-SERVICE-001)

**Scoring Engine**:
```java
@Service
@RequiredArgsConstructor
public class FraudDetectionService {
    private final UserRiskProfileRepository riskProfileRepository;
    private final FraudRuleRepository ruleRepository;
    private final AnalyticsQueryService analyticsService;
    private final FraudAlertRepository alertRepository;
    
    public FraudCheckResult checkOrder(Order order) {
        UUID userId = order.getUserId();
        
        // 1. Load user risk profile
        UserRiskProfile profile = riskProfileRepository.findById(userId)
            .orElseGet(() -> createDefaultProfile(userId));
        
        // 2. Load active fraud rules
        List<FraudRule> rules = ruleRepository.findByIsActiveTrue();
        
        // 3. Evaluate each rule
        List<FraudAlert> alerts = new ArrayList<>();
        int totalRiskScore = profile.getRiskScore();
        
        for (FraudRule rule : rules) {
            FraudRuleResult result = evaluateRule(rule, order, profile);
            
            if (result.isTriggered()) {
                totalRiskScore += result.getScoreImpact();
                
                FraudAlert alert = FraudAlert.builder()
                    .alertId(UUID.randomUUID())
                    .userId(userId)
                    .ruleId(rule.getRuleId())
                    .orderId(order.getOrderId())
                    .alertType(rule.getRuleType())
                    .severity(rule.getSeverity())
                    .description(result.getDescription())
                    .evidence(toJson(result.getEvidence()))
                    .status(AlertStatus.OPEN)
                    .createdAt(Instant.now())
                    .build();
                
                alerts.add(alert);
            }
        }
        
        // 4. Update risk profile
        profile.setRiskScore(Math.min(totalRiskScore, 100));
        profile.setRiskLevel(calculateRiskLevel(totalRiskScore));
        profile.setLastEvaluatedAt(Instant.now());
        riskProfileRepository.save(profile);
        
        // 5. Save alerts
        if (!alerts.isEmpty()) {
            alertRepository.saveAll(alerts);
        }
        
        // 6. Determine action
        FraudAction action = determineAction(profile.getRiskLevel(), alerts);
        
        return FraudCheckResult.builder()
            .userId(userId)
            .riskScore(totalRiskScore)
            .riskLevel(profile.getRiskLevel())
            .alerts(alerts)
            .action(action)
            .build();
    }
    
    private FraudRuleResult evaluateRule(FraudRule rule, Order order, UserRiskProfile profile) {
        return switch (rule.getRuleType()) {
            case "VELOCITY" -> evaluateVelocityRule(rule, order, profile);
            case "PATTERN" -> evaluatePatternRule(rule, order, profile);
            case "SCORE_THRESHOLD" -> evaluateScoreRule(rule, profile);
            default -> FraudRuleResult.notTriggered();
        };
    }
    
    private FraudRuleResult evaluateVelocityRule(FraudRule rule, Order order, UserRiskProfile profile) {
        // Example: Max 3 orders per hour
        JsonNode condition = rule.getConditionExpr();
        int maxOrders = condition.get("max_orders").asInt();
        int timeWindowMinutes = condition.get("time_window_minutes").asInt();
        
        Instant from = Instant.now().minus(timeWindowMinutes, ChronoUnit.MINUTES);
        int orderCount = countUserOrders(order.getUserId(), from);
        
        if (orderCount > maxOrders) {
            return FraudRuleResult.triggered(
                String.format("User placed %d orders in %d minutes (max %d)", 
                    orderCount, timeWindowMinutes, maxOrders),
                Map.of("order_count", orderCount, "time_window", timeWindowMinutes),
                20  // Score impact
            );
        }
        
        return FraudRuleResult.notTriggered();
    }
    
    private FraudRuleResult evaluatePatternRule(FraudRule rule, Order order, UserRiskProfile profile) {
        // Example: Unusual shipping address
        JsonNode condition = rule.getConditionExpr();
        
        // Check if address differs from previous orders
        List<Address> previousAddresses = getPreviousShippingAddresses(order.getUserId());
        Address currentAddress = order.getShippingAddress();
        
        boolean isNewAddress = previousAddresses.stream()
            .noneMatch(addr -> isSimilarAddress(addr, currentAddress));
        
        if (isNewAddress && order.getTotalAmount().compareTo(BigDecimal.valueOf(5000000)) > 0) {
            return FraudRuleResult.triggered(
                "New shipping address with high-value order",
                Map.of("order_amount", order.getTotalAmount(), "address", currentAddress),
                30
            );
        }
        
        return FraudRuleResult.notTriggered();
    }
    
    private FraudAction determineAction(RiskLevel riskLevel, List<FraudAlert> alerts) {
        return switch (riskLevel) {
            case CRITICAL -> FraudAction.BLOCK;
            case HIGH -> alerts.stream()
                .anyMatch(a -> a.getSeverity() == Severity.HIGH)
                ? FraudAction.REVIEW : FraudAction.FLAG;
            case MEDIUM -> FraudAction.FLAG;
            case LOW -> FraudAction.ALLOW;
        };
    }
}
```

### 2.3 Fraud Event Handler (Agent Task: FRAUD-EVENTS-001)

**Real-time Monitoring**:
```java
@Component
@RequiredArgsConstructor
public class FraudEventHandler {
    private final FraudDetectionService fraudService;
    
    @KafkaListener(topics = "order-events", groupId = "fraud-detection-group")
    public void handleOrderEvent(@Payload BaseEvent event) {
        if (event instanceof OrderCreatedEvent) {
            OrderCreatedEvent orderEvent = (OrderCreatedEvent) event;
            
            // Load order details
            Order order = orderRepository.findById(UUID.fromString(orderEvent.getOrderId()))
                .orElseThrow();
            
            // Run fraud check
            FraudCheckResult result = fraudService.checkOrder(order);
            
            // Take action based on result
            if (result.getAction() == FraudAction.BLOCK) {
                orderService.cancelOrder(order.getOrderId(), "Blocked by fraud detection");
                notificationService.notifyFraudTeam(result);
            } else if (result.getAction() == FraudAction.REVIEW) {
                orderService.holdForReview(order.getOrderId());
                notificationService.notifyFraudTeam(result);
            } else if (result.getAction() == FraudAction.FLAG) {
                fraudService.flagForMonitoring(order.getUserId());
            }
        }
    }
}
```

---

## Phase 3: AI Agent Service (Week 3-4)

### 3.1 pgvector Setup (Agent Task: DB-VECTOR-001)

**Schema**:
```sql
-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Product embeddings
CREATE TABLE product_embeddings (
    product_id UUID PRIMARY KEY,
    name_embedding VECTOR(1536),
    description_embedding VECTOR(1536),
    combined_embedding VECTOR(1536),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_product_embeddings_combined 
ON product_embeddings 
USING ivfflat (combined_embedding vector_cosine_ops)
WITH (lists = 100);

-- User embeddings (based on browsing history)
CREATE TABLE user_embeddings (
    user_id UUID PRIMARY KEY,
    preference_embedding VECTOR(1536),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Conversation history
CREATE TABLE ai_conversations (
    conversation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title TEXT,
    context JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ai_messages (
    message_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES ai_conversations(conversation_id),
    role VARCHAR(20) NOT NULL,  -- USER, ASSISTANT, SYSTEM
    content TEXT NOT NULL,
    embedding VECTOR(1536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_messages_conv ON ai_messages(conversation_id, created_at);
```

### 3.2 Embedding Service (Agent Task: AI-EMBED-001)

**OpenAI Integration**:
```java
@Service
@RequiredArgsConstructor
public class EmbeddingService {
    private final RestTemplate restTemplate;
    
    @Value("${openai.api-key}")
    private String apiKey;
    
    @Value("${openai.embedding-model}")
    private String embeddingModel = "text-embedding-3-small";
    
    public float[] generateEmbedding(String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, Object> request = Map.of(
            "input", text,
            "model", embeddingModel
        );
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        ResponseEntity<OpenAIEmbeddingResponse> response = restTemplate.postForEntity(
            "https://api.openai.com/v1/embeddings",
            entity,
            OpenAIEmbeddingResponse.class
        );
        
        return response.getBody().getData().get(0).getEmbedding();
    }
    
    @Async
    public void generateProductEmbeddings(UUID productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow();
        
        String combinedText = product.getName() + " " + product.getDescription();
        float[] embedding = generateEmbedding(combinedText);
        
        jdbcTemplate.update(
            "INSERT INTO product_embeddings (product_id, combined_embedding, updated_at) " +
            "VALUES (?, ?::vector, NOW()) " +
            "ON CONFLICT (product_id) DO UPDATE SET combined_embedding = EXCLUDED.combined_embedding",
            productId,
            Arrays.toString(embedding)
        );
    }
}
```

### 3.3 AI Agent Service (Agent Task: AI-AGENT-001)

**RAG Implementation**:
```java
@Service
@RequiredArgsConstructor
public class AIAgentService {
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final OpenAIService openAIService;
    
    public AIResponse chat(UUID userId, String userMessage, UUID conversationId) {
        // 1. Generate embedding for user message
        float[] messageEmbedding = embeddingService.generateEmbedding(userMessage);
        
        // 2. Retrieve conversation history
        List<AIMessage> history = getConversationHistory(conversationId, 10);
        
        // 3. Semantic search for relevant products
        List<Product> relevantProducts = searchSimilarProducts(messageEmbedding, 5);
        
        // 4. Build context
        String context = buildContext(relevantProducts, history);
        
        // 5. Generate response with GPT-4
        String systemPrompt = """
            You are a helpful e-commerce assistant. Use the provided context to answer questions
            about products. If you don't know the answer, say so. Be concise and friendly.
            
            Context:
            %s
        """.formatted(context);
        
        String assistantResponse = openAIService.complete(
            systemPrompt,
            userMessage,
            history
        );
        
        // 6. Save messages
        saveMessage(conversationId, "USER", userMessage, messageEmbedding);
        float[] responseEmbedding = embeddingService.generateEmbedding(assistantResponse);
        saveMessage(conversationId, "ASSISTANT", assistantResponse, responseEmbedding);
        
        return AIResponse.builder()
            .message(assistantResponse)
            .relevantProducts(relevantProducts.stream()
                .map(ProductDto::from)
                .toList())
            .build();
    }
    
    private List<Product> searchSimilarProducts(float[] embedding, int limit) {
        String sql = """
            SELECT p.*, pe.combined_embedding <-> ?::vector AS distance
            FROM products p
            JOIN product_embeddings pe ON p.product_id = pe.product_id
            ORDER BY pe.combined_embedding <-> ?::vector
            LIMIT ?
        """;
        
        String embeddingStr = Arrays.toString(embedding);
        
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> Product.builder()
                .productId(rs.getObject("product_id", UUID.class))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .price(rs.getBigDecimal("price"))
                .build(),
            embeddingStr, embeddingStr, limit
        );
    }
    
    public List<ProductRecommendation> getPersonalizedRecommendations(UUID userId, int limit) {
        // 1. Get user's browsing history
        List<UUID> viewedProducts = analyticsService.getRecentlyViewedProducts(userId, 20);
        
        // 2. Build user preference embedding (average of viewed products)
        float[] userEmbedding = calculateUserEmbedding(viewedProducts);
        
        // 3. Find similar products (excluding already viewed)
        String sql = """
            SELECT p.*, pe.combined_embedding <-> ?::vector AS similarity_score
            FROM products p
            JOIN product_embeddings pe ON p.product_id = pe.product_id
            WHERE p.product_id != ALL(?)
            AND p.is_active = true
            ORDER BY pe.combined_embedding <-> ?::vector
            LIMIT ?
        """;
        
        String embeddingStr = Arrays.toString(userEmbedding);
        UUID[] viewedArray = viewedProducts.toArray(new UUID[0]);
        
        return jdbcTemplate.query(sql,
            (rs, rowNum) -> ProductRecommendation.builder()
                .productId(rs.getObject("product_id", UUID.class))
                .name(rs.getString("name"))
                .price(rs.getBigDecimal("price"))
                .similarityScore(rs.getDouble("similarity_score"))
                .reason("Based on your browsing history")
                .build(),
            embeddingStr, viewedArray, embeddingStr, limit
        );
    }
    
    private float[] calculateUserEmbedding(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return new float[1536];  // Zero vector
        }
        
        String sql = """
            SELECT AVG(elem) AS avg_value, idx
            FROM (
                SELECT unnest(combined_embedding::float[]) AS elem,
                       generate_series(1, array_length(combined_embedding::float[], 1)) AS idx
                FROM product_embeddings
                WHERE product_id = ANY(?)
            ) subq
            GROUP BY idx
            ORDER BY idx
        """;
        
        List<Float> avgEmbedding = jdbcTemplate.query(sql,
            (rs, rowNum) -> rs.getFloat("avg_value"),
            productIds.toArray(new UUID[0])
        );
        
        return avgEmbedding.stream()
            .map(Float::floatValue)
            .collect(Collectors.toList())
            .toArray(new Float[0]);
    }
}
```

### 3.4 AI API (Agent Task: AI-API-001)

**Endpoints**:
```java
POST   /api/ai/chat                      - Chat with AI assistant
GET    /api/ai/conversations             - List user conversations
POST   /api/ai/conversations             - Create new conversation
DELETE /api/ai/conversations/{id}        - Delete conversation
GET    /api/ai/recommendations           - Get personalized recommendations
POST   /api/ai/embeddings/products       - Generate product embeddings (admin)
```

---

## AI Agent Task Breakdown

### Week 1: Analytics Foundation
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-ANALYTICS-001 | TimescaleDB setup | 8h | - |
| ANALYTICS-AGG-001 | Continuous aggregates | 6h | DB-ANALYTICS-001 |
| ANALYTICS-INGEST-001 | Event ingestion | 10h | DB-ANALYTICS-001 |
| ANALYTICS-QUERY-001 | Query service | 8h | ANALYTICS-AGG-001 |

### Week 2: Fraud Detection
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-FRAUD-001 | Fraud schema | 4h | - |
| FRAUD-SERVICE-001 | Fraud engine | 14h | DB-FRAUD-001 |
| FRAUD-EVENTS-001 | Event handlers | 8h | FRAUD-SERVICE-001 |

### Week 3-4: AI Agent
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-VECTOR-001 | pgvector setup | 6h | - |
| AI-EMBED-001 | Embedding service | 10h | DB-VECTOR-001 |
| AI-AGENT-001 | AI agent service | 16h | AI-EMBED-001 |
| AI-API-001 | REST API | 8h | AI-AGENT-001 |

---

## Validation Checklist

### Analytics Service
- [ ] TimescaleDB hypertables created
- [ ] Continuous aggregates refresh correctly
- [ ] Event ingestion handles 10K+ events/sec
- [ ] Query performance < 100ms for common queries
- [ ] Compression and retention policies active

### Fraud Detection
- [ ] Real-time fraud checks on orders
- [ ] Velocity rules enforced
- [ ] Pattern detection works
- [ ] Alerts sent to fraud team
- [ ] Risk profiles updated

### AI Agent
- [ ] Product embeddings generated
- [ ] Semantic search returns relevant results
- [ ] Chat maintains conversation context
- [ ] Personalized recommendations accurate
- [ ] Response time < 2s

**End of Intelligence Services Implementation Plan**
