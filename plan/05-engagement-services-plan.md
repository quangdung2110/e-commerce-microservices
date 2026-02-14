# Implementation Plan: Engagement Services Group

> **Services**: Review Service, Notification Service, Chat Service  
> **Priority**: ⭐⭐⭐⭐ HIGH (Customer engagement & retention)  
> **Estimated Time**: 4-5 weeks  
> **Dependencies**: Order Service, Identity Services, Common module

---

## 📋 Overview

This group handles customer engagement through product reviews, multi-channel notifications, and real-time chat support.

### Key Challenges
1. **Review Verification**: Only verified buyers can review (order linkage)
2. **Review Moderation**: AI-based content filtering for spam/offensive content
3. **Multi-Channel Notifications**: Email, SMS, Push, In-app routing
4. **Template Management**: Dynamic templates with placeholders
5. **Real-Time Chat**: ScyllaDB for high-throughput message storage
6. **Chat History**: Efficient pagination for conversation history

---

## Architecture Decisions

### 1. Review Service - Verified Purchase Pattern

```
Order Delivered → Review Eligibility Window (30 days)
    ↓
Customer writes review
    ↓
AI Moderation (spam detection, sentiment analysis)
    ↓
Published & Seller notified
```

### 2. Notification Service - Channel Router

```
Event → Notification Router → Channel Strategy
                                  ├─ Email (High priority)
                                  ├─ SMS (OTP, urgent)
                                  ├─ Push (Mobile app)
                                  └─ In-app (Real-time)
```

### 3. Chat Service - ScyllaDB for Scale

```sql
-- ScyllaDB Table
CREATE TABLE messages (
    conversation_id UUID,
    message_id TIMEUUID,
    sender_id UUID,
    sender_type TEXT,  -- CUSTOMER, SELLER, ADMIN
    content TEXT,
    attachments LIST<TEXT>,
    is_read BOOLEAN,
    created_at TIMESTAMP,
    PRIMARY KEY ((conversation_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

---

## Phase 1: Review Service (Week 1-2)

### 1.1 Database Schema (Agent Task: DB-REVIEW-001)

**Tables**: `reviews`, `review_medias`, `review_votes`, `review_reports`

**Schema**:
```sql
CREATE TABLE reviews (
    review_id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    sku_id UUID,
    user_id UUID NOT NULL,
    order_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title VARCHAR(200),
    content TEXT NOT NULL,
    pros TEXT,
    cons TEXT,
    is_verified_purchase BOOLEAN NOT NULL DEFAULT false,
    purchase_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, PUBLISHED, REJECTED, HIDDEN
    moderation_status VARCHAR(30),  -- APPROVED, FLAGGED_SPAM, FLAGGED_OFFENSIVE
    moderation_reason TEXT,
    helpful_count INTEGER NOT NULL DEFAULT 0,
    unhelpful_count INTEGER NOT NULL DEFAULT 0,
    seller_response TEXT,
    seller_responded_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_reviews_product ON reviews(product_id, status, rating);
CREATE INDEX idx_reviews_user ON reviews(user_id);
CREATE INDEX idx_reviews_order ON reviews(order_id);

CREATE TABLE review_medias (
    media_id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(review_id) ON DELETE CASCADE,
    media_type VARCHAR(20) NOT NULL,  -- IMAGE, VIDEO
    url TEXT NOT NULL,
    thumbnail_url TEXT,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE review_votes (
    vote_id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(review_id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    vote_type VARCHAR(10) NOT NULL CHECK (vote_type IN ('HELPFUL', 'UNHELPFUL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(review_id, user_id)
);

CREATE TABLE review_reports (
    report_id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(review_id),
    reported_by UUID NOT NULL,
    reason VARCHAR(50) NOT NULL,  -- SPAM, OFFENSIVE, FAKE, OTHER
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolved_at TIMESTAMPTZ,
    resolved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 1.2 Review Aggregate (Agent Task: REVIEW-AGG-001)

**Domain Events**:
```java
@DomainEvent
public class ReviewCreatedEvent extends BaseEvent {
    UUID reviewId;
    UUID productId;
    UUID userId;
    UUID orderId;
    int rating;
    String content;
}

@DomainEvent
public class ReviewPublishedEvent extends BaseEvent {
    UUID reviewId;
    UUID productId;
    UUID sellerId;
    int rating;
}

@DomainEvent
public class SellerRespondedToReviewEvent extends BaseEvent {
    UUID reviewId;
    UUID sellerId;
    String response;
}

@DomainEvent
public class ReviewVotedEvent extends BaseEvent {
    UUID reviewId;
    UUID userId;
    String voteType;  // HELPFUL, UNHELPFUL
}
```

**Aggregate Logic**:
```java
@Entity
@Data
@SuperBuilder
public class Review {
    @Id
    private UUID reviewId;
    
    @Version
    private Integer version;
    
    private UUID productId;
    private UUID userId;
    private UUID orderId;
    private UUID orderItemId;
    private int rating;
    private String title;
    private String content;
    private ReviewStatus status;
    private int helpfulCount;
    private int unhelpfulCount;
    
    public void publish() {
        if (status != ReviewStatus.PENDING) {
            throw new IllegalStateException("Only pending reviews can be published");
        }
        
        this.status = ReviewStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        
        apply(ReviewPublishedEvent.builder()
            .reviewId(reviewId.toString())
            .productId(productId.toString())
            .rating(rating)
            .build());
    }
    
    public void addSellerResponse(UUID sellerId, String response) {
        if (status != ReviewStatus.PUBLISHED) {
            throw new IllegalStateException("Can only respond to published reviews");
        }
        
        this.sellerResponse = response;
        this.sellerRespondedAt = Instant.now();
        
        apply(SellerRespondedToReviewEvent.builder()
            .reviewId(reviewId.toString())
            .sellerId(sellerId.toString())
            .response(response)
            .build());
    }
    
    public void vote(UUID userId, VoteType voteType) {
        apply(ReviewVotedEvent.builder()
            .reviewId(reviewId.toString())
            .userId(userId.toString())
            .voteType(voteType.name())
            .build());
        
        if (voteType == VoteType.HELPFUL) {
            this.helpfulCount++;
        } else {
            this.unhelpfulCount++;
        }
    }
}
```

### 1.3 Review Service (Agent Task: REVIEW-SERVICE-001)

**Core Methods**:
```java
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ReviewModerationService moderationService;
    private final ReviewEventPublisher eventPublisher;
    
    public CreateReviewResponse createReview(CreateReviewRequest request, UUID userId) {
        // 1. Validate order eligibility
        Order order = orderRepository.findById(request.getOrderId())
            .orElseThrow(() -> new OrderNotFoundException());
        
        if (!order.getUserId().equals(userId)) {
            throw new UnauthorizedException("Order does not belong to user");
        }
        
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new ReviewNotAllowedException("Can only review delivered orders");
        }
        
        // 2. Check review window (30 days after delivery)
        Instant deliveredAt = order.getDeliveredAt();
        Instant reviewDeadline = deliveredAt.plus(30, ChronoUnit.DAYS);
        
        if (Instant.now().isAfter(reviewDeadline)) {
            throw new ReviewWindowExpiredException("Review window expired");
        }
        
        // 3. Check if already reviewed
        boolean alreadyReviewed = reviewRepository.existsByOrderItemId(request.getOrderItemId());
        if (alreadyReviewed) {
            throw new DuplicateReviewException("Item already reviewed");
        }
        
        // 4. Create review
        Review review = Review.builder()
            .reviewId(UUID.randomUUID())
            .productId(request.getProductId())
            .skuId(request.getSkuId())
            .userId(userId)
            .orderId(order.getOrderId())
            .orderItemId(request.getOrderItemId())
            .rating(request.getRating())
            .title(request.getTitle())
            .content(request.getContent())
            .pros(request.getPros())
            .cons(request.getCons())
            .isVerifiedPurchase(true)
            .purchaseDate(order.getCreatedAt().toLocalDate())
            .status(ReviewStatus.PENDING)
            .createdAt(Instant.now())
            .build();
        
        reviewRepository.save(review);
        
        // 5. AI Moderation
        ModerationResult moderation = moderationService.moderate(review);
        
        if (moderation.isApproved()) {
            review.publish();
            reviewRepository.save(review);
        } else {
            review.setModerationStatus(moderation.getStatus());
            review.setModerationReason(moderation.getReason());
            reviewRepository.save(review);
        }
        
        // 6. Save media attachments
        if (request.getMediaUrls() != null && !request.getMediaUrls().isEmpty()) {
            saveMediaAttachments(review.getReviewId(), request.getMediaUrls());
        }
        
        // 7. Publish event
        eventPublisher.publish(ReviewCreatedEvent.builder()
            .reviewId(review.getReviewId().toString())
            .productId(review.getProductId().toString())
            .userId(userId.toString())
            .orderId(order.getOrderId().toString())
            .rating(review.getRating())
            .content(review.getContent())
            .build());
        
        return CreateReviewResponse.builder()
            .reviewId(review.getReviewId())
            .status(review.getStatus())
            .moderationStatus(review.getModerationStatus())
            .build();
    }
    
    public void voteReview(UUID reviewId, UUID userId, VoteType voteType) {
        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new ReviewNotFoundException());
        
        // Check if already voted
        boolean alreadyVoted = reviewVoteRepository.existsByReviewIdAndUserId(reviewId, userId);
        if (alreadyVoted) {
            throw new DuplicateVoteException("Already voted on this review");
        }
        
        // Record vote
        ReviewVote vote = ReviewVote.builder()
            .voteId(UUID.randomUUID())
            .reviewId(reviewId)
            .userId(userId)
            .voteType(voteType)
            .createdAt(Instant.now())
            .build();
        
        reviewVoteRepository.save(vote);
        
        // Update review counts
        review.vote(userId, voteType);
        reviewRepository.save(review);
    }
    
    public Page<ReviewDto> getProductReviews(UUID productId, Pageable pageable) {
        Page<Review> reviews = reviewRepository.findByProductIdAndStatus(
            productId, 
            ReviewStatus.PUBLISHED, 
            pageable
        );
        
        return reviews.map(ReviewDto::from);
    }
    
    public ProductReviewSummary getReviewSummary(UUID productId) {
        List<Object[]> ratingDistribution = reviewRepository
            .getRatingDistribution(productId);
        
        return ProductReviewSummary.builder()
            .productId(productId)
            .totalReviews(getTotalReviewCount(productId))
            .averageRating(getAverageRating(productId))
            .ratingDistribution(mapRatingDistribution(ratingDistribution))
            .verifiedPurchasePercentage(getVerifiedPurchasePercentage(productId))
            .build();
    }
}
```

### 1.4 AI Moderation Service (Agent Task: REVIEW-MOD-001)

**Content Filtering**:
```java
@Service
@RequiredArgsConstructor
public class ReviewModerationService {
    private final SpamDetector spamDetector;
    private final ProfanityFilter profanityFilter;
    private final SentimentAnalyzer sentimentAnalyzer;
    
    public ModerationResult moderate(Review review) {
        // 1. Spam detection
        if (spamDetector.isSpam(review.getContent())) {
            return ModerationResult.flagged(
                ModerationStatus.FLAGGED_SPAM,
                "Content flagged as spam"
            );
        }
        
        // 2. Profanity check
        if (profanityFilter.containsProfanity(review.getContent())) {
            return ModerationResult.flagged(
                ModerationStatus.FLAGGED_OFFENSIVE,
                "Content contains offensive language"
            );
        }
        
        // 3. Sentiment analysis (optional)
        SentimentScore sentiment = sentimentAnalyzer.analyze(review.getContent());
        
        // 4. Length validation
        if (review.getContent().length() < 20) {
            return ModerationResult.flagged(
                ModerationStatus.FLAGGED_LOW_QUALITY,
                "Content too short"
            );
        }
        
        return ModerationResult.approved();
    }
}
```

### 1.5 Review API (Agent Task: REVIEW-API-001)

**Endpoints**:
```java
POST   /api/reviews                     - Create review
GET    /api/reviews/{id}                - Get review details
PUT    /api/reviews/{id}                - Update review
DELETE /api/reviews/{id}                - Delete review
POST   /api/reviews/{id}/vote           - Vote helpful/unhelpful
GET    /api/products/{id}/reviews       - List product reviews
GET    /api/products/{id}/review-summary - Review statistics

// Seller endpoints
POST   /api/seller/reviews/{id}/respond - Respond to review

// Admin endpoints
PUT    /api/admin/reviews/{id}/moderate - Moderate review
GET    /api/admin/reviews/pending       - Pending moderation
POST   /api/admin/reviews/{id}/hide     - Hide review
```

---

## Phase 2: Notification Service (Week 3)

### 2.1 Database Schema (Agent Task: DB-NOTIF-001)

**Tables**: `notification_templates`, `notifications`, `user_notification_preferences`

**Schema**:
```sql
CREATE TABLE notification_templates (
    template_id UUID PRIMARY KEY,
    template_code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    channel VARCHAR(20) NOT NULL,  -- EMAIL, SMS, PUSH, IN_APP
    subject_template TEXT,
    body_template TEXT NOT NULL,
    placeholders JSONB,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    template_code VARCHAR(50) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    subject TEXT,
    body TEXT NOT NULL,
    data JSONB,
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',  -- HIGH, NORMAL, LOW
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, SENT, FAILED, READ
    sent_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_status ON notifications(status) WHERE status = 'PENDING';

CREATE TABLE user_notification_preferences (
    user_id UUID PRIMARY KEY,
    email_enabled BOOLEAN NOT NULL DEFAULT true,
    sms_enabled BOOLEAN NOT NULL DEFAULT false,
    push_enabled BOOLEAN NOT NULL DEFAULT true,
    in_app_enabled BOOLEAN NOT NULL DEFAULT true,
    order_updates_enabled BOOLEAN NOT NULL DEFAULT true,
    promotion_enabled BOOLEAN NOT NULL DEFAULT true,
    review_reminders_enabled BOOLEAN NOT NULL DEFAULT true,
    newsletter_enabled BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 2.2 Notification Service (Agent Task: NOTIF-SERVICE-001)

**Template Engine**:
```java
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationTemplateRepository templateRepository;
    private final NotificationRepository notificationRepository;
    private final Map<String, NotificationChannel> channels;  // Email, SMS, Push, InApp
    private final TemplateEngine templateEngine;
    private final RedisService redisService;
    
    public void sendNotification(SendNotificationRequest request) {
        // 1. Load template
        NotificationTemplate template = templateRepository
            .findByTemplateCode(request.getTemplateCode())
            .orElseThrow(() -> new TemplateNotFoundException());
        
        // 2. Check user preferences
        UserNotificationPreferences prefs = getUserPreferences(request.getUserId());
        if (!shouldSendNotification(template, prefs)) {
            log.info("Notification skipped due to user preferences");
            return;
        }
        
        // 3. Render template
        String subject = templateEngine.process(template.getSubjectTemplate(), request.getData());
        String body = templateEngine.process(template.getBodyTemplate(), request.getData());
        
        // 4. Create notification record
        Notification notification = Notification.builder()
            .notificationId(UUID.randomUUID())
            .userId(request.getUserId())
            .templateCode(request.getTemplateCode())
            .channel(template.getChannel())
            .subject(subject)
            .body(body)
            .data(toJson(request.getData()))
            .priority(request.getPriority())
            .status(NotificationStatus.PENDING)
            .createdAt(Instant.now())
            .build();
        
        notificationRepository.save(notification);
        
        // 5. Send via channel
        NotificationChannel channel = channels.get(template.getChannel().toLowerCase());
        
        try {
            channel.send(SendRequest.builder()
                .recipientId(request.getUserId())
                .subject(subject)
                .body(body)
                .data(request.getData())
                .build());
            
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(Instant.now());
            
        } catch (Exception e) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            notification.setRetryCount(notification.getRetryCount() + 1);
            
            log.error("Failed to send notification", e);
        }
        
        notificationRepository.save(notification);
        
        // 6. Cache for in-app retrieval
        if (template.getChannel() == NotificationChannel.IN_APP) {
            cacheInAppNotification(notification);
        }
    }
    
    @Async
    @Scheduled(fixedRate = 300000)  // Every 5 minutes
    public void retryFailedNotifications() {
        List<Notification> failed = notificationRepository
            .findByStatusAndRetryCountLessThan(NotificationStatus.FAILED, 3);
        
        for (Notification notification : failed) {
            sendNotification(SendNotificationRequest.from(notification));
        }
    }
    
    public Page<NotificationDto> getInAppNotifications(UUID userId, Pageable pageable) {
        Page<Notification> notifications = notificationRepository
            .findByUserIdAndChannel(userId, NotificationChannel.IN_APP, pageable);
        
        return notifications.map(NotificationDto::from);
    }
    
    public void markAsRead(UUID notificationId, UUID userId) {
        Notification notification = notificationRepository
            .findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> new NotificationNotFoundException());
        
        notification.setStatus(NotificationStatus.READ);
        notification.setReadAt(Instant.now());
        notificationRepository.save(notification);
        
        invalidateCache(userId);
    }
}
```

### 2.3 Notification Channels (Agent Task: NOTIF-CHANNELS-001)

**Email Channel**:
```java
@Component
@RequiredArgsConstructor
public class EmailNotificationChannel implements NotificationChannel {
    private final JavaMailSender mailSender;
    
    @Override
    public void send(SendRequest request) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        
        User user = getUserById(request.getRecipientId());
        
        helper.setTo(user.getEmail());
        helper.setSubject(request.getSubject());
        helper.setText(request.getBody(), true);  // HTML
        
        mailSender.send(message);
    }
}
```

**SMS Channel**:
```java
@Component
@RequiredArgsConstructor
public class SMSNotificationChannel implements NotificationChannel {
    private final RestTemplate restTemplate;
    
    @Value("${sms.provider.url}")
    private String smsProviderUrl;
    
    @Value("${sms.provider.api-key}")
    private String apiKey;
    
    @Override
    public void send(SendRequest request) {
        User user = getUserById(request.getRecipientId());
        
        SMSRequest smsRequest = SMSRequest.builder()
            .to(user.getPhoneNumber())
            .message(request.getBody())
            .apiKey(apiKey)
            .build();
        
        HttpEntity<SMSRequest> entity = new HttpEntity<>(smsRequest);
        
        ResponseEntity<SMSResponse> response = restTemplate.postForEntity(
            smsProviderUrl + "/send",
            entity,
            SMSResponse.class
        );
        
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new NotificationException("Failed to send SMS");
        }
    }
}
```

### 2.4 Event-Driven Notifications (Agent Task: NOTIF-EVENTS-001)

**Kafka Listeners**:
```java
@Component
@RequiredArgsConstructor
public class NotificationEventHandler {
    private final NotificationService notificationService;
    
    @KafkaListener(topics = "order-events", groupId = "notification-service-group")
    public void handleOrderEvent(@Payload BaseEvent event) {
        if (event instanceof OrderCreatedEvent) {
            sendOrderConfirmation((OrderCreatedEvent) event);
        } else if (event instanceof OrderShippedEvent) {
            sendShippingNotification((OrderShippedEvent) event);
        } else if (event instanceof OrderDeliveredEvent) {
            sendDeliveryNotification((OrderDeliveredEvent) event);
            scheduleReviewReminder((OrderDeliveredEvent) event);
        }
    }
    
    private void sendOrderConfirmation(OrderCreatedEvent event) {
        notificationService.sendNotification(SendNotificationRequest.builder()
            .userId(UUID.fromString(event.getUserId()))
            .templateCode("ORDER_CONFIRMATION")
            .priority(NotificationPriority.HIGH)
            .data(Map.of(
                "orderNumber", event.getOrderNumber(),
                "totalAmount", event.getTotalAmount()
            ))
            .build());
    }
    
    private void scheduleReviewReminder(OrderDeliveredEvent event) {
        // Schedule reminder 3 days after delivery
        Instant reminderTime = event.getDeliveredAt().plus(3, ChronoUnit.DAYS);
        
        // Use scheduled task or Temporal workflow
    }
}
```

### 2.5 Notification API (Agent Task: NOTIF-API-001)

**Endpoints**:
```java
GET    /api/notifications                - List in-app notifications
GET    /api/notifications/unread-count   - Get unread count
PUT    /api/notifications/{id}/read      - Mark as read
PUT    /api/notifications/read-all       - Mark all as read
DELETE /api/notifications/{id}           - Delete notification

GET    /api/notifications/preferences    - Get preferences
PUT    /api/notifications/preferences    - Update preferences
```

---

## Phase 3: Chat Service (Week 4-5)

### 3.1 ScyllaDB Schema (Agent Task: DB-CHAT-001)

**ScyllaDB Tables**:
```cql
CREATE KEYSPACE chat 
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3};

USE chat;

CREATE TABLE conversations (
    conversation_id UUID,
    participant_ids SET<UUID>,
    conversation_type TEXT,  -- CUSTOMER_SUPPORT, SELLER_CUSTOMER
    subject TEXT,
    status TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    last_message_at TIMESTAMP,
    PRIMARY KEY (conversation_id)
);

CREATE TABLE messages (
    conversation_id UUID,
    message_id TIMEUUID,
    sender_id UUID,
    sender_type TEXT,  -- CUSTOMER, SELLER, ADMIN
    content TEXT,
    message_type TEXT,  -- TEXT, IMAGE, FILE
    attachments LIST<TEXT>,
    is_read BOOLEAN,
    read_at TIMESTAMP,
    created_at TIMESTAMP,
    PRIMARY KEY ((conversation_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);

CREATE TABLE user_conversations (
    user_id UUID,
    conversation_id UUID,
    last_read_message_id TIMEUUID,
    unread_count INT,
    updated_at TIMESTAMP,
    PRIMARY KEY ((user_id), conversation_id)
) WITH CLUSTERING ORDER BY (conversation_id DESC);
```

**PostgreSQL Metadata**:
```sql
CREATE TABLE conversation_metadata (
    conversation_id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    seller_id UUID,
    order_id UUID,
    subject VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assigned_agent_id UUID,
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMPTZ
);
```

### 3.2 Chat Service (Agent Task: CHAT-SERVICE-001)

**Core Logic**:
```java
@Service
@RequiredArgsConstructor
public class ChatService {
    private final CqlSession scyllaSession;
    private final ConversationMetadataRepository metadataRepository;
    private final WebSocketMessagingTemplate messagingTemplate;
    private final RedisService redisService;
    
    public SendMessageResponse sendMessage(SendMessageRequest request, UUID senderId) {
        UUID conversationId = request.getConversationId();
        
        // 1. Insert message into ScyllaDB
        UUID messageId = Uuids.timeBased();
        
        PreparedStatement stmt = scyllaSession.prepare(
            "INSERT INTO messages (conversation_id, message_id, sender_id, sender_type, " +
            "content, message_type, is_read, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        );
        
        BoundStatement bound = stmt.bind(
            conversationId,
            messageId,
            senderId,
            request.getSenderType(),
            request.getContent(),
            request.getMessageType(),
            false,
            Instant.now()
        );
        
        scyllaSession.execute(bound);
        
        // 2. Update conversation last_message_at
        updateConversationTimestamp(conversationId);
        
        // 3. Increment unread count for recipients
        List<UUID> recipientIds = getConversationParticipants(conversationId);
        recipientIds.stream()
            .filter(id -> !id.equals(senderId))
            .forEach(recipientId -> incrementUnreadCount(recipientId, conversationId));
        
        // 4. Send real-time notification via WebSocket
        MessageDto messageDto = MessageDto.builder()
            .conversationId(conversationId)
            .messageId(messageId)
            .senderId(senderId)
            .content(request.getContent())
            .createdAt(Instant.now())
            .build();
        
        recipientIds.forEach(recipientId -> {
            messagingTemplate.convertAndSendToUser(
                recipientId.toString(),
                "/queue/messages",
                messageDto
            );
        });
        
        return SendMessageResponse.builder()
            .messageId(messageId)
            .conversationId(conversationId)
            .build();
    }
    
    public Page<MessageDto> getMessages(UUID conversationId, UUID userId, Pageable pageable) {
        // Verify user is participant
        if (!isParticipant(conversationId, userId)) {
            throw new UnauthorizedException("Not a participant");
        }
        
        // Query ScyllaDB with pagination
        PreparedStatement stmt = scyllaSession.prepare(
            "SELECT * FROM messages WHERE conversation_id = ? " +
            "ORDER BY message_id DESC LIMIT ?"
        );
        
        BoundStatement bound = stmt.bind(conversationId, pageable.getPageSize());
        
        ResultSet rs = scyllaSession.execute(bound);
        
        List<MessageDto> messages = new ArrayList<>();
        for (Row row : rs) {
            messages.add(MessageDto.builder()
                .messageId(row.getUuid("message_id"))
                .senderId(row.getUuid("sender_id"))
                .content(row.getString("content"))
                .createdAt(row.getInstant("created_at"))
                .isRead(row.getBoolean("is_read"))
                .build());
        }
        
        return new PageImpl<>(messages, pageable, messages.size());
    }
    
    public void markAsRead(UUID conversationId, UUID userId, UUID lastReadMessageId) {
        // Update last_read_message_id in user_conversations
        PreparedStatement stmt = scyllaSession.prepare(
            "UPDATE user_conversations SET last_read_message_id = ?, unread_count = 0 " +
            "WHERE user_id = ? AND conversation_id = ?"
        );
        
        BoundStatement bound = stmt.bind(lastReadMessageId, userId, conversationId);
        scyllaSession.execute(bound);
        
        // Invalidate cache
        redisService.delete("unread_count:" + userId);
    }
    
    public int getUnreadCount(UUID userId) {
        String cacheKey = "unread_count:" + userId;
        
        return redisService.get(cacheKey, Integer.class)
            .orElseGet(() -> {
                PreparedStatement stmt = scyllaSession.prepare(
                    "SELECT SUM(unread_count) as total FROM user_conversations WHERE user_id = ?"
                );
                
                BoundStatement bound = stmt.bind(userId);
                ResultSet rs = scyllaSession.execute(bound);
                
                int total = rs.one().getInt("total");
                redisService.set(cacheKey, total, Duration.ofMinutes(5));
                return total;
            });
    }
}
```

### 3.3 WebSocket Configuration (Agent Task: CHAT-WS-001)

**WebSocket Setup**:
```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
            .setAllowedOrigins("*")
            .withSockJS();
    }
}

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {
    private final ChatService chatService;
    
    @MessageMapping("/chat.send")
    public void sendMessage(@Payload SendMessageRequest request, Principal principal) {
        UUID senderId = UUID.fromString(principal.getName());
        chatService.sendMessage(request, senderId);
    }
    
    @MessageMapping("/chat.typing")
    public void sendTypingIndicator(@Payload TypingIndicatorRequest request, Principal principal) {
        messagingTemplate.convertAndSend(
            "/topic/chat." + request.getConversationId() + ".typing",
            request
        );
    }
}
```

### 3.4 Chat API (Agent Task: CHAT-API-001)

**Endpoints**:
```java
POST   /api/chat/conversations           - Create conversation
GET    /api/chat/conversations           - List user conversations
GET    /api/chat/conversations/{id}      - Get conversation details
POST   /api/chat/conversations/{id}/messages - Send message
GET    /api/chat/conversations/{id}/messages - Get message history
PUT    /api/chat/conversations/{id}/read - Mark as read
GET    /api/chat/unread-count            - Get unread count
POST   /api/chat/conversations/{id}/close - Close conversation

// WebSocket
WS     /ws/chat                          - WebSocket connection
```

---

## AI Agent Task Breakdown

### Week 1-2: Review Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-REVIEW-001 | Database schema | 4h | - |
| REVIEW-AGG-001 | Review aggregate | 8h | DB-REVIEW-001 |
| REVIEW-SERVICE-001 | Service layer | 12h | REVIEW-AGG-001 |
| REVIEW-MOD-001 | AI moderation | 10h | REVIEW-SERVICE-001 |
| REVIEW-API-001 | REST API | 6h | REVIEW-SERVICE-001 |

### Week 3: Notification Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-NOTIF-001 | Database schema | 4h | - |
| NOTIF-SERVICE-001 | Service layer | 10h | DB-NOTIF-001 |
| NOTIF-CHANNELS-001 | Channel implementations | 12h | NOTIF-SERVICE-001 |
| NOTIF-EVENTS-001 | Event handlers | 8h | NOTIF-SERVICE-001 |
| NOTIF-API-001 | REST API | 6h | NOTIF-SERVICE-001 |

### Week 4-5: Chat Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-CHAT-001 | ScyllaDB schema | 6h | - |
| CHAT-SERVICE-001 | Service layer | 14h | DB-CHAT-001 |
| CHAT-WS-001 | WebSocket setup | 10h | CHAT-SERVICE-001 |
| CHAT-API-001 | REST API | 8h | CHAT-SERVICE-001 |

---

## Validation Checklist

### Review Service
- [ ] Only delivered orders can be reviewed
- [ ] Review window enforced (30 days)
- [ ] Duplicate review prevention
- [ ] AI moderation works
- [ ] Seller responses functional
- [ ] Vote system accurate

### Notification Service
- [ ] All channels working (Email, SMS, Push, In-app)
- [ ] Template rendering correct
- [ ] User preferences respected
- [ ] Failed notifications retry
- [ ] Event-driven triggers work

### Chat Service
- [ ] Real-time messages via WebSocket
- [ ] Message history paginated correctly
- [ ] Unread count accurate
- [ ] Typing indicators work
- [ ] ScyllaDB queries optimized

**End of Engagement Services Implementation Plan**
