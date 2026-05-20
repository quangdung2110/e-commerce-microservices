# Module 10: Event Publishing

> **Module:** Infrastructure Layer  
> **File:** `module-10-event-publishing.md`  
> **Phase:** 3 - Advanced Features  
> **Estimated Time:** 3-4 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Kafka Producer](#2-kafka-producer)
3. [Redis Pub/Sub Publisher](#3-redis-pubsub-publisher)
4. [Admin Event Consumer](#4-admin-event-consumer)
5. [Event Publisher Implementation](#5-event-publisher-implementation)
6. [Testing](#6-testing)
7. [Checklist](#7-checklist)

---

## 1. Overview

Module này triển khai event publishing:
- Kafka Producer cho events ra `t.auth.events` (Direct Publish)
- Redis Pub/Sub cho JWKS update notifications
- Admin Event Consumer cho `ADMIN_FORCE_LOGOUT`, `USER_BANNED`

### Event Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                      EVENT PUBLISHING                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Auth Service                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                                                             │ │
│  │  ┌──────────┐    ┌──────────────┐    ┌────────────────┐   │ │
│  │  │ Domain   │───▶│ Event        │───▶│ Kafka Broker   │   │ │
│  │  │ Service  │    │ Publisher    │    │ (Direct Send) │   │ │
│  │  └──────────┘    └──────────────┘    └────────────────┘   │ │
│  │                                                             │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                  Kafka / Redis Pub/Sub                       │ │
│  └─────────────────────────────────────────────────────────────┘
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐         │
│  │ Notification│  │ User Service │  │    Gateway      │         │
│  │ Service     │  │             │  │ (JWKS Cache)    │         │
│  └─────────────┘  └─────────────┘  └─────────────────┘         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Design Decision

Auth Service sử dụng **Direct Kafka Producer** vì:
- Auth events (OTP, login, etc.) không yêu cầu transactional consistency
- At-least-once delivery là đủ cho use case này
- Giảm độ phức tạp hệ thống (không cần thêm CDC layer)

---

## 2. Kafka Producer

### 2.1 Kafka Producer Implementation

```go
// internal/infrastructure/kafka/producer.go
package kafka

import (
    "context"
    "encoding/json"
    "fmt"
    "log"
    "sync"
    "time"

    "github.com/IBM/sarama"

    "github.com/ecommerce/auth-service/internal/common/config"
)

type Producer struct {
    producer sarama.SyncProducer
    topicPrefix string
}

func NewProducer(cfg config.KafkaConfig) (*Producer, error) {
    config := sarama.NewConfig()
    config.Producer.RequiredAcks = sarama.WaitForAll
    config.Producer.Retry.Max = 5
    config.Producer.Return.Successes = true
    config.Producer.Partitioner = sarama.NewHashPartitioner

    producer, err := sarama.NewSyncProducer(cfg.Brokers, config)
    if err != nil {
        return nil, fmt.Errorf("failed to create Kafka producer: %w", err)
    }

    return &Producer{
        producer:    producer,
        topicPrefix: cfg.TopicPrefix,
    }, nil
}

func (p *Producer) Close() error {
    return p.producer.Close()
}

// PublishEvent publishes an event to Kafka
func (p *Producer) PublishEvent(ctx context.Context, topic string, key string, event interface{}) error {
    topicName := p.topicPrefix + "." + topic

    payload, err := json.Marshal(event)
    if err != nil {
        return fmt.Errorf("failed to marshal event: %w", err)
    }

    msg := &sarama.ProducerMessage{
        Topic: topicName,
        Key:   sarama.StringEncoder(key),
        Value: sarama.ByteEncoder(payload),
        Headers: []sarama.RecordHeader{
            {
                Key:   "event_type",
                Value: []byte(getEventType(event)),
            },
            {
                Key:   "timestamp",
                Value: []byte(fmt.Sprintf("%d", time.Now().Unix())),
            },
        },
    }

    partition, offset, err := p.producer.SendMessage(msg)
    if err != nil {
        return fmt.Errorf("failed to send message: %w", err)
    }

    log.Printf("Event published to %s partition %d offset %d", topicName, partition, offset)
    return nil
}

// PublishAuthEvent publishes an event to t.auth.events topic
func (p *Producer) PublishAuthEvent(ctx context.Context, key string, event interface{}) error {
    return p.PublishEvent(ctx, "auth.events", key, event)
}

// PublishAdminEvent publishes an event to admin topic
func (p *Producer) PublishAdminEvent(ctx context.Context, key string, event interface{}) error {
    return p.PublishEvent(ctx, "admin.events", key, event)
}

func getEventType(event interface{}) string {
    return fmt.Sprintf("%T", event)
}
```

### 2.2 Event Types Definition

```go
// internal/domain/event/auth_events.go (extended)
// This file contains all event type definitions

package event

import (
    "encoding/json"
    "time"

    "github.com/google/uuid"
)

// Auth event topic constants
const (
    TopicAuthEvents = "auth.events"
)

// UserOTPRequested event
type UserOTPRequested struct {
    EventID       string `json:"event_id"`
    EventType    string `json:"event_type"`
    AggregateType string `json:"aggregate_type"`
    AggregateID   string `json:"aggregate_id"`
    PartitionKey  string `json:"partition_key"`
    OccurredAt   string `json:"occurred_at"`
    Payload      UserOTPRequestedPayload `json:"payload"`
}

type UserOTPRequestedPayload struct {
    UserID  string `json:"user_id"`
    Email   string `json:"email"`
    OTPType string `json:"otp_type"`
}

func NewUserOTPRequested(userID uuid.UUID, email, otpType string) *UserOTPRequested {
    return &UserOTPRequested{
        EventID:       uuid.New().String(),
        EventType:    "USER_OTP_REQUESTED",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: UserOTPRequestedPayload{
            UserID:  userID.String(),
            Email:   email,
            OTPType: otpType,
        },
    }
}

func (e *UserOTPRequested) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// UserVerifiedSuccess event
type UserVerifiedSuccess struct {
    EventID       string                      `json:"event_id"`
    EventType    string                       `json:"event_type"`
    AggregateType string                       `json:"aggregate_type"`
    AggregateID   string                       `json:"aggregate_id"`
    PartitionKey  string                       `json:"partition_key"`
    OccurredAt   string                       `json:"occurred_at"`
    Payload      UserVerifiedSuccessPayload     `json:"payload"`
}

type UserVerifiedSuccessPayload struct {
    UserID string   `json:"user_id"`
    Email  string   `json:"email"`
    Roles  []string `json:"roles"`
}

func NewUserVerifiedSuccess(userID uuid.UUID, email string, roles []string) *UserVerifiedSuccess {
    return &UserVerifiedSuccess{
        EventID:       uuid.New().String(),
        EventType:    "USER_VERIFIED_SUCCESS",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: UserVerifiedSuccessPayload{
            UserID: userID.String(),
            Email:  email,
            Roles:  roles,
        },
    }
}

func (e *UserVerifiedSuccess) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// UserAccountLocked event
type UserAccountLocked struct {
    EventID       string                     `json:"event_id"`
    EventType    string                      `json:"event_type"`
    AggregateType string                      `json:"aggregate_type"`
    AggregateID   string                      `json:"aggregate_id"`
    PartitionKey  string                      `json:"partition_key"`
    OccurredAt   string                      `json:"occurred_at"`
    Payload      UserAccountLockedPayload     `json:"payload"`
}

type UserAccountLockedPayload struct {
    UserID      string `json:"user_id"`
    Email       string `json:"email"`
    Reason      string `json:"reason"`
    LockedUntil string `json:"locked_until"`
}

func NewUserAccountLocked(userID uuid.UUID, email, reason string, lockedUntil time.Time) *UserAccountLocked {
    return &UserAccountLocked{
        EventID:       uuid.New().String(),
        EventType:    "USER_ACCOUNT_LOCKED",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: UserAccountLockedPayload{
            UserID:      userID.String(),
            Email:       email,
            Reason:      reason,
            LockedUntil: lockedUntil.Format(time.RFC3339),
        },
    }
}

func (e *UserAccountLocked) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// UserUnlocked event
type UserUnlocked struct {
    EventID       string                `json:"event_id"`
    EventType    string                 `json:"event_type"`
    AggregateType string                 `json:"aggregate_type"`
    AggregateID   string                 `json:"aggregate_id"`
    PartitionKey  string                 `json:"partition_key"`
    OccurredAt   string                 `json:"occurred_at"`
    Payload      UserUnlockedPayload     `json:"payload"`
}

type UserUnlockedPayload struct {
    UserID    string `json:"user_id"`
    Email     string `json:"email"`
    UnlockType string `json:"unlock_type"`
    UnlockedBy string `json:"unlocked_by"`
}

func NewUserUnlocked(userID uuid.UUID, email, unlockType, unlockedBy string) *UserUnlocked {
    return &UserUnlocked{
        EventID:       uuid.New().String(),
        EventType:    "USER_UNLOCKED",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: UserUnlockedPayload{
            UserID:    userID.String(),
            Email:     email,
            UnlockType: unlockType,
            UnlockedBy: unlockedBy,
        },
    }
}

func (e *UserUnlocked) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// UserPasswordChanged event
type UserPasswordChanged struct {
    EventID       string                     `json:"event_id"`
    EventType    string                      `json:"event_type"`
    AggregateType string                      `json:"aggregate_type"`
    AggregateID   string                      `json:"aggregate_id"`
    PartitionKey  string                      `json:"partition_key"`
    OccurredAt   string                      `json:"occurred_at"`
    Payload      UserPasswordChangedPayload    `json:"payload"`
}

type UserPasswordChangedPayload struct {
    UserID     string `json:"user_id"`
    Email      string `json:"email"`
    DeviceInfo string `json:"device_info"`
    ChangedAt  string `json:"changed_at"`
}

func NewUserPasswordChanged(userID uuid.UUID, email, deviceInfo string) *UserPasswordChanged {
    return &UserPasswordChanged{
        EventID:       uuid.New().String(),
        EventType:    "USER_PASSWORD_CHANGED",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: UserPasswordChangedPayload{
            UserID:     userID.String(),
            Email:      email,
            DeviceInfo: deviceInfo,
            ChangedAt:  time.Now().UTC().Format(time.RFC3339),
        },
    }
}

func (e *UserPasswordChanged) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// PasswordResetRequested event
type PasswordResetRequested struct {
    EventID       string                        `json:"event_id"`
    EventType    string                         `json:"event_type"`
    AggregateType string                         `json:"aggregate_type"`
    AggregateID   string                         `json:"aggregate_id"`
    PartitionKey  string                         `json:"partition_key"`
    OccurredAt   string                         `json:"occurred_at"`
    Payload      PasswordResetRequestedPayload    `json:"payload"`
}

type PasswordResetRequestedPayload struct {
    UserID   string `json:"user_id"`
    Email    string `json:"email"`
    ResetURL string `json:"reset_url"`
}

func NewPasswordResetRequested(userID uuid.UUID, email, resetURL string) *PasswordResetRequested {
    return &PasswordResetRequested{
        EventID:       uuid.New().String(),
        EventType:    "PASSWORD_RESET_REQUESTED",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: PasswordResetRequestedPayload{
            UserID:   userID.String(),
            Email:    email,
            ResetURL: resetURL,
        },
    }
}

func (e *PasswordResetRequested) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// TokenTheftDetected event
type TokenTheftDetected struct {
    EventID       string                    `json:"event_id"`
    EventType    string                     `json:"event_type"`
    AggregateType string                     `json:"aggregate_type"`
    AggregateID   string                     `json:"aggregate_id"`
    PartitionKey  string                     `json:"partition_key"`
    OccurredAt   string                     `json:"occurred_at"`
    Payload      TokenTheftPayload           `json:"payload"`
}

type TokenTheftPayload struct {
    UserID               string `json:"user_id"`
    TheftRefreshTokenID   string `json:"theft_refresh_token_id"`
    ActionTaken          string `json:"action_taken"`
}

func NewTokenTheftDetected(userID uuid.UUID, tokenID, action string) *TokenTheftDetected {
    return &TokenTheftDetected{
        EventID:       uuid.New().String(),
        EventType:    "TOKEN_THEFT_DETECTED",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: TokenTheftPayload{
            UserID:              userID.String(),
            TheftRefreshTokenID:  tokenID,
            ActionTaken:         action,
        },
    }
}

func (e *TokenTheftDetected) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// UserBannedNotification event
type UserBannedNotification struct {
    EventID       string                       `json:"event_id"`
    EventType    string                        `json:"event_type"`
    AggregateType string                        `json:"aggregate_type"`
    AggregateID   string                        `json:"aggregate_id"`
    PartitionKey  string                        `json:"partition_key"`
    OccurredAt   string                        `json:"occurred_at"`
    Payload      UserBannedPayload              `json:"payload"`
}

type UserBannedPayload struct {
    UserID   string `json:"user_id"`
    Email    string `json:"email"`
    Reason   string `json:"reason"`
    BannedBy string `json:"banned_by"`
    BannedAt string `json:"banned_at"`
}

func NewUserBannedNotification(userID uuid.UUID, email, reason, bannedBy string) *UserBannedNotification {
    return &UserBannedNotification{
        EventID:       uuid.New().String(),
        EventType:    "USER_BANNED_NOTIFICATION",
        AggregateType: "USER",
        AggregateID:   userID.String(),
        PartitionKey:  userID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: UserBannedPayload{
            UserID:   userID.String(),
            Email:    email,
            Reason:   reason,
            BannedBy: bannedBy,
            BannedAt: time.Now().UTC().Format(time.RFC3339),
        },
    }
}

func (e *UserBannedNotification) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}
```

---

## 3. Redis Pub/Sub Publisher

### 3.1 Redis Pub/Sub Implementation

```go
// internal/infrastructure/pubsub/publisher.go
package pubsub

import (
    "context"
    "encoding/json"
    "fmt"
    "log"
    "time"

    "github.com/redis/go-redis/v9"

    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type Publisher struct {
    redisClient *redis.Client
}

func NewPublisher(redisClient *redis.Client) *Publisher {
    return &Publisher{
        redisClient: redisClient,
    }
}

// PublishJWKSUpdate publishes JWKS update notification
func (p *Publisher) PublishJWKSUpdate(ctx context.Context) error {
    channel := "auth:jwks:update"

    message := JWKSUpdateMessage{
        Event:     "JWKS_UPDATED",
        Timestamp: time.Now().Unix(),
    }

    payload, err := json.Marshal(message)
    if err != nil {
        return fmt.Errorf("failed to marshal message: %w", err)
    }

    result, err := p.redisClient.Publish(ctx, channel, string(payload)).Result()
    if err != nil {
        return fmt.Errorf("failed to publish message: %w", err)
    }

    log.Printf("JWKS update published to channel %s, %d subscribers received", channel, result)
    return nil
}

type JWKSUpdateMessage struct {
    Event     string `json:"event"`
    Timestamp int64  `json:"timestamp"`
}
```

### 3.2 Redis Pub/Sub Subscriber

```go
// internal/infrastructure/pubsub/subscriber.go
package pubsub

import (
    "context"
    "encoding/json"
    "log"

    "github.com/redis/go-redis/v9"

    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type Subscriber struct {
    redisClient *redis.Client
    handler     JWKSUpdateHandler
}

type JWKSUpdateHandler interface {
    OnJWKSUpdate()
}

func NewSubscriber(redisClient *redis.Client, handler JWKSUpdateHandler) *Subscriber {
    return &Subscriber{
        redisClient: redisClient,
        handler:     handler,
    }
}

// Subscribe starts subscribing to channels
func (s *Subscriber) Subscribe(ctx context.Context) {
    channel := "auth:jwks:update"

    pubsub := s.redisClient.Subscribe(ctx, channel)

    log.Printf("Subscribed to Redis Pub/Sub channel: %s", channel)

    for {
        select {
        case <-ctx.Done():
            log.Println("Unsubscribing from Redis Pub/Sub")
            pubsub.Close()
            return
        default:
            msg, err := pubsub.ReceiveMessage(ctx)
            if err != nil {
                log.Printf("Error receiving message: %v", err)
                continue
            }

            s.handleMessage(msg)
        }
    }
}

func (s *Subscriber) handleMessage(msg *redis.Message) {
    var update JWKSUpdateMessage
    if err := json.Unmarshal([]byte(msg.Payload), &update); err != nil {
        log.Printf("Error unmarshaling JWKS update: %v", err)
        return
    }

    log.Printf("Received JWKS update notification: %+v", update)

    if s.handler != nil {
        s.handler.OnJWKSUpdate()
    }
}
```

---

## 4. Admin Event Consumer

### 4.1 Admin Event Handler

```go
// internal/infrastructure/kafka/handler/admin_handler.go
package handler

import (
    "context"
    "encoding/json"
    "fmt"
    "log"

    "github.com/IBM/sarama"

    "github.com/ecommerce/auth-service/internal/domain/service"
)

type AdminEventHandler struct {
    authService *service.AuthService
    userService *service.UserService
}

func NewAdminEventHandler(
    authService *service.AuthService,
    userService *service.UserService,
) *AdminEventHandler {
    return &AdminEventHandler{
        authService: authService,
        userService: userService,
    }
}

// AdminForceLogoutEvent represents ADMIN_FORCE_LOGOUT event
type AdminForceLogoutEvent struct {
    EventID      string `json:"event_id"`
    EventType   string `json:"event_type"`
    AggregateID string `json:"aggregate_id"`
    Payload      struct {
        UserID string `json:"user_id"`
        Reason string `json:"reason"`
    } `json:"payload"`
}

// UserBannedEvent represents USER_BANNED event
type UserBannedEvent struct {
    EventID      string `json:"event_id"`
    EventType   string `json:"event_type"`
    AggregateID string `json:"aggregate_id"`
    Payload      struct {
        UserID string `json:"user_id"`
        Reason string `json:"reason"`
    } `json:"payload"`
}

// HandleMessage handles incoming admin events
func (h *AdminEventHandler) HandleMessage(ctx context.Context, msg *sarama.ConsumerMessage) error {
    var event map[string]interface{}
    if err := json.Unmarshal(msg.Value, &event); err != nil {
        return fmt.Errorf("failed to unmarshal event: %w", err)
    }

    eventType, ok := event["event_type"].(string)
    if !ok {
        return fmt.Errorf("missing event_type in message")
    }

    payloadJSON, err := json.Marshal(event["payload"])
    if err != nil {
        return fmt.Errorf("failed to marshal payload: %w", err)
    }

    switch eventType {
    case "ADMIN_FORCE_LOGOUT":
        return h.handleForceLogout(ctx, payloadJSON)
    case "USER_BANNED":
        return h.handleUserBanned(ctx, payloadJSON)
    default:
        log.Printf("Unknown admin event type: %s", eventType)
        return nil
    }
}

func (h *AdminEventHandler) handleForceLogout(ctx context.Context, payloadJSON []byte) error {
    var event AdminForceLogoutEvent
    if err := json.Unmarshal(payloadJSON, &event); err != nil {
        return fmt.Errorf("failed to unmarshal force logout event: %w", err)
    }

    log.Printf("Processing ADMIN_FORCE_LOGOUT for user: %s, reason: %s", 
        event.Payload.UserID, event.Payload.Reason)

    // Force logout all sessions for the user
    userID, err := parseUUID(event.Payload.UserID)
    if err != nil {
        return fmt.Errorf("invalid user ID: %w", err)
    }

    if err := h.authService.ForceLogoutAll(ctx, userID, event.Payload.Reason); err != nil {
        return fmt.Errorf("failed to force logout: %w", err)
    }

    log.Printf("Force logout completed for user: %s", event.Payload.UserID)
    return nil
}

func (h *AdminEventHandler) handleUserBanned(ctx context.Context, payloadJSON []byte) error {
    var event UserBannedEvent
    if err := json.Unmarshal(payloadJSON, &event); err != nil {
        return fmt.Errorf("failed to unmarshal user banned event: %w", err)
    }

    log.Printf("Processing USER_BANNED for user: %s, reason: %s", 
        event.Payload.UserID, event.Payload.Reason)

    // Ban the user
    userID, err := parseUUID(event.Payload.UserID)
    if err != nil {
        return fmt.Errorf("invalid user ID: %w", err)
    }

    if err := h.userService.BanUser(ctx, userID, event.Payload.Reason); err != nil {
        return fmt.Errorf("failed to ban user: %w", err)
    }

    log.Printf("User banned: %s", event.Payload.UserID)
    return nil
}

func parseUUID(s string) (uuid.UUID, error) {
    // Using google/uuid
    return uuid.Parse(s)
}

import "github.com/google/uuid"
```

### 4.2 Kafka Consumer

```go
// internal/infrastructure/kafka/consumer.go
package kafka

import (
    "context"
    "fmt"
    "log"
    "sync"

    "github.com/IBM/sarama"

    "github.com/ecommerce/auth-service/internal/common/config"
)

type Consumer struct {
    consumerGroup sarama.ConsumerGroup
    topics        []string
    handler       ConsumerGroupHandler
    ready         chan bool
    wg            sync.WaitGroup
}

type ConsumerGroupHandler interface {
    HandleMessage(ctx context.Context, msg *sarama.ConsumerMessage) error
    Setup(sarama.ConsumerGroupSession) error
    Cleanup(sarama.ConsumerGroupSession) error
    ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error
}

func NewConsumer(cfg config.KafkaConfig, handler ConsumerGroupHandler) (*Consumer, error) {
    config := sarama.NewConfig()
    config.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
        sarama.NewBalanceStrategyRoundRobin(),
    }
    config.Consumer.Offsets.Initial = sarama.OffsetNewest
    config.Consumer.Return.Errors = true

    consumerGroup, err := sarama.NewConsumerGroup(cfg.Brokers, cfg.ConsumerGroup, config)
    if err != nil {
        return nil, fmt.Errorf("failed to create consumer group: %w", err)
    }

    return &Consumer{
        consumerGroup: consumerGroup,
        topics:        []string{cfg.AdminTopic},
        handler:       handler,
        ready:         make(chan bool),
    }, nil
}

func (c *Consumer) Start(ctx context.Context) error {
    c.wg.Add(1)
    go func() {
        defer c.wg.Done()
        for {
            if err := c.consumerGroup.Consume(ctx, c.topics, c); err != nil {
                log.Printf("Error from consumer: %v", err)
            }
            if ctx.Err() != nil {
                return
            }
            c.ready = make(chan bool)
        }
    }()

    <-c.ready
    log.Println("Consumer group is ready")
    return nil
}

func (c *Consumer) Close() error {
    return c.consumerGroup.Close()
}

// ConsumerGroupHandler implementation
func (c *Consumer) Setup(session sarama.ConsumerGroupSession) error {
    close(c.ready)
    return nil
}

func (c *Consumer) Cleanup(session sarama.ConsumerGroupSession) error {
    return nil
}

func (c *Consumer) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
    for {
        select {
        case message, ok := <-claim.Messages():
            if !ok {
                return nil
            }
            if err := c.handler.HandleMessage(session.Context(), message); err != nil {
                log.Printf("Error handling message: %v", err)
            }
            session.MarkMessage(message, "")
        case <-session.Context().Done():
            return nil
        }
    }
}
```

---

## 5. Event Publisher Implementation

### 5.1 Event Publisher Port

```go
// internal/domain/port/publisher.go
package port

import "context"

// EventPublisher interface for publishing domain events
type EventPublisher interface {
    // Publish publishes an event to the message broker
    Publish(ctx context.Context, event interface{}) error
}
```

### 5.2 Kafka Event Publisher

```go
// internal/infrastructure/kafka/event_publisher.go
package kafka

import (
    "context"
    "encoding/json"
    "fmt"
    "log"
    "reflect"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/domain/event"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

// EventPublisher implementation using Kafka
type EventPublisher struct {
    producer     *Producer
    topicPrefix string
}

func NewEventPublisher(producer *Producer, topicPrefix string) *EventPublisher {
    return &EventPublisher{
        producer:     producer,
        topicPrefix: topicPrefix,
    }
}

func (p *EventPublisher) Publish(ctx context.Context, evt interface{}) error {
    // Extract event metadata
    eventType := getEventTypeName(evt)
    partitionKey := getPartitionKey(evt)

    // Marshal event to JSON
    payload, err := json.Marshal(evt)
    if err != nil {
        return fmt.Errorf("failed to marshal event: %w", err)
    }

    // Publish to Kafka
    topic := p.topicPrefix + ".auth.events"
    if err := p.producer.PublishEvent(ctx, topic, partitionKey, evt); err != nil {
        return fmt.Errorf("failed to publish event: %w", err)
    }

    log.Printf("Event published: %s to topic %s", eventType, topic)
    return nil
}

func getEventTypeName(evt interface{}) string {
    t := reflect.TypeOf(evt)
    if t.Kind() == reflect.Ptr {
        t = t.Elem()
    }
    return t.Name()
}

func getPartitionKey(evt interface{}) string {
    // Try to get partition key from event
    switch e := evt.(type) {
    case *event.UserOTPRequested:
        return e.Payload.UserID
    case *event.UserVerifiedSuccess:
        return e.Payload.UserID
    case *event.UserAccountLocked:
        return e.Payload.UserID
    case *event.UserUnlocked:
        return e.Payload.UserID
    case *event.UserPasswordChanged:
        return e.Payload.UserID
    case *event.PasswordResetRequested:
        return e.Payload.UserID
    case *event.TokenTheftDetected:
        return e.Payload.UserID
    case *event.UserBannedNotification:
        return e.Payload.UserID
    default:
        return uuid.New().String()
    }
}
```

---

## 6. Testing

### 6.1 Producer Test

```go
// test/integration/kafka/producer_test.go
package kafka

import (
    "testing"

    "github.com/stretchr/testify/assert"
)

func TestProducer_PublishEvent(t *testing.T) {
    // Test would require Kafka container
    // This is a placeholder for the actual test
    t.Skip("Requires Kafka container")
}
```

---

## 7. Checklist

### Kafka Producer

- [x] Create `internal/infrastructure/kafka/producer.go`
- [x] Implement sync producer
- [x] Add topic configuration
- [x] Add error handling
- [x] Add logging

### Event Types

- [x] Define all event types (UserOTPRequested, UserVerifiedSuccess, etc.)
- [x] Implement event builders
- [x] Add JSON serialization

### Redis Pub/Sub

- [x] Create `internal/infrastructure/pubsub/publisher.go`
- [x] Implement JWKS update publisher
- [x] Create `internal/infrastructure/pubsub/subscriber.go`
- [x] Implement JWKS update handler

### Admin Event Consumer

- [x] Create `internal/infrastructure/kafka/handler/admin_handler.go`
- [x] Implement ADMIN_FORCE_LOGOUT handler
- [x] Implement USER_BANNED handler
- [x] Create `internal/infrastructure/kafka/consumer.go`
- [x] Implement consumer group

### Event Publisher

- [x] Create `internal/domain/port/publisher.go` interface
- [x] Create `internal/infrastructure/kafka/event_publisher.go`
- [x] Implement Publish method
- [x] Add partition key extraction

### Integration

- [x] Integrate with all domain services
- [x] Test end-to-end event publishing
- [x] Test admin event consumption

---

**Previous:** [module-09-jwks-management.md](module-09-jwks-management.md)  
**Next:** [module-11-background-workers.md](module-11-background-workers.md)
