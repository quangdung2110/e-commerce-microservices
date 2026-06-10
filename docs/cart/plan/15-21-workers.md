# Cart Service — Module 15-21: Background Workers

**Document:** 15-21 | **Module:** All 6 Background Workers  
**Status:** `[x]` Đã hoàn thành | `[ ]` Chưa hoàn thành  
**Phụ thuộc:** Module 02, 03, 06, 07, 08, 09, 10, 11

---

## 1. Mục tiêu Module

Implement tất cả 6 background workers. Mỗi worker được mô tả chi tiết trong sub-sections dưới đây.

---

## 2. Worker #1: Outbox Relay (`internal/workers/outbox_relay/`)

### 2.1. Overview

- **Loại:** Long-running goroutine (daemon)
- **Sứ mệnh:** Forward events từ Redis Global Stream Shards sang Kafka
- **Ref:** `cart_workers.md` §1, `cart_outbox_pattern.md`

### 2.2. Implementation

```go
// internal/workers/outbox_relay/worker.go
package outbox_relay

import (
    "context"
    "encoding/json"
    "fmt"
    "sync"
    "time"

    "github.com/IBM/sarama"
    "github.com/redis/go-redis/v9"
    "go.uber.org/zap"

    "cart-service/internal/common/keybuilder"
    "cart-service/internal/domain/event"
    "cart-service/internal/domain/port"
    "cart-service/internal/infrastructure/config"
)

// Worker represents the Outbox Relay worker for one shard.
type Worker struct {
    shardID      int
    streamKey    string
    redisClient *redis.Client
    kafkaProducer port.KafkaPublisher
    consumerGroup string
    workerID    string
    blockTimeout time.Duration
    batchSize   int
    logger      *zap.Logger
    stopCh      chan struct{}
    wg          sync.WaitGroup
}

// NewWorker creates a new Outbox Relay worker for a shard.
func NewWorker(
    shardID int,
    redisClient *redis.Client,
    kafkaProducer port.KafkaPublisher,
    cfg config.OutboxRelayConfig,
    logger *zap.Logger,
) *Worker {
    return &Worker{
        shardID:       shardID,
        streamKey:     keybuilder.StreamShardKey(shardID),
        redisClient:  redisClient,
        kafkaProducer: kafkaProducer,
        consumerGroup: fmt.Sprintf("cart_relay_group:%d", shardID),
        workerID:     fmt.Sprintf("worker-%d-%d", shardID, time.Now().UnixNano()),
        blockTimeout: cfg.BlockTimeout,
        batchSize:   cfg.BatchSize,
        logger:      logger.With(zap.Int("shard", shardID)),
        stopCh:      make(chan struct{}),
    }
}

// Start begins the relay loop.
func (w *Worker) Start(ctx context.Context) error {
    w.wg.Add(1)
    go w.run(ctx)
    w.logger.Info("outbox relay worker started")
    return nil
}

// Stop gracefully stops the worker.
func (w *Worker) Stop() {
    close(w.stopCh)
    w.wg.Wait()
    w.logger.Info("outbox relay worker stopped")
}

func (w *Worker) run(ctx context.Context) {
    defer w.wg.Done()

    // Ensure consumer group exists
    if err := w.ensureConsumerGroup(ctx); err != nil {
        w.logger.Error("failed to create consumer group", zap.Error(err))
        return
    }

    for {
        select {
        case <-ctx.Done():
            return
        case <-w.stopCh:
            return
        default:
            w.readAndForward(ctx)
        }
    }
}

func (w *Worker) ensureConsumerGroup(ctx context.Context) error {
    // MKSTREAM creates the stream if it doesn't exist
    err := w.redisClient.XGroupCreateMkStream(
        ctx, w.streamKey, w.consumerGroup, "0",
    ).Err()
    if err != nil && err.Error() != "BUSYGROUP Consumer Group name already exists" {
        return err
    }
    return nil
}

func (w *Worker) readAndForward(ctx context.Context) {
    // XREADGROUP BLOCK: read new messages only (">")
    result, err := w.redisClient.XReadGroup(ctx, &redis.XReadGroupArgs{
        Group:    w.consumerGroup,
        Consumer: w.workerID,
        Streams:  []string{w.streamKey, ">"},
        Count:    int64(w.batchSize),
        Block:    w.blockTimeout,
    }).Result()

    if err != nil {
        if err == redis.Nil {
            return // No messages
        }
        w.logger.Warn("XREADGROUP error", zap.Error(err))
        time.Sleep(time.Second) // Backoff
        return
    }

    for _, stream := range result {
        for _, msg := range stream.Messages {
            w.processMessage(ctx, &msg)
        }
    }
}

func (w *Worker) processMessage(ctx context.Context, msg *redis.XMessage) {
    // Extract fields from Redis Stream message
    eventType, hasType := msg.Values["event_type"].(string)
    payloadJSON, hasPayload := msg.Values["payload"].(string)
    if !hasType || !hasPayload {
        w.logger.Warn("malformed stream message", zap.String("id", msg.ID))
        // ACK and skip malformed messages
        w.ack(msg.ID)
        return
    }

    // Parse stream ID components
    streamTs, streamSeq := event.ParseStreamID(msg.ID)

    // Build Kafka envelope header
    envelope := &event.CartEventEnvelope{
        EventID:           deterministicEventID(msg.ID, ""), // filled by builder
        EventType:         eventType,
        AggregateType:     event.AggregateTypeCart,
        AggregateID:       extractCartIDFromPayload(payloadJSON),
        CorrelationID:     extractCorrelationID(payloadJSON),
        Timestamp:         time.Now().UTC().Format(time.RFC3339),
        Source:           "cart-service",
        StreamID:         msg.ID,
        StreamTimestampMs: streamTs,
        EventSequence:     streamSeq,
        Payload:          extractPayloadMap(payloadJSON),
    }

    // Generate deterministic event_id from stream_id + cart_id
    envelope.EventID = deterministicEventID(msg.ID, envelope.AggregateID)

    // Publish to Kafka
    kafkaMsg := &port.CartEventMessage{
        EventID:           envelope.EventID,
        EventType:         envelope.EventType,
        AggregateType:     envelope.AggregateType,
        AggregateID:       envelope.AggregateID,
        CorrelationID:     envelope.CorrelationID,
        Timestamp:         envelope.Timestamp,
        Source:            envelope.Source,
        StreamID:         envelope.StreamID,
        StreamTimestampMs: envelope.StreamTimestampMs,
        EventSequence:     envelope.EventSequence,
        Payload:           envelope.Payload,
    }

    if err := w.kafkaProducer.Publish(ctx, kafkaMsg); err != nil {
        w.logger.Error("Kafka publish failed",
            zap.String("stream_id", msg.ID),
            zap.String("event_type", eventType),
            zap.Error(err),
        )
        // Do NOT ACK — message will be redelivered
        return
    }

    // Kafka success — ACK the message
    w.ack(msg.ID)
}

func (w *Worker) ack(messageID string) {
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()
    if err := w.redisClient.XAck(ctx, w.streamKey, w.consumerGroup, messageID).Err(); err != nil {
        w.logger.Warn("XACK failed", zap.String("id", messageID), zap.Error(err))
    }
}
```

---

## 3. Worker #2: Postgres Sync (`internal/workers/postgres_sync/`)

### 3.1. Overview

- **Loại:** Kafka Consumer
- **Sứ mệnh:** Ghi events từ Kafka xuống PostgreSQL Cold Store
- **Ref:** `cart_workers.md` §2, `cart_idempotency.md`

### 3.2. Implementation

```go
// internal/workers/postgres_sync/worker.go
package postgres_sync

import (
    "context"
    "encoding/json"
    "fmt"
    "sync"
    "time"

    "github.com/IBM/sarama"
    "go.uber.org/zap"

    "cart-service/internal/domain/entity"
    "cart-service/internal/domain/event"
    "cart-service/internal/domain/port"
    "cart-service/internal/infrastructure/config"
    "cart-service/internal/infrastructure/kafka"
)

// Worker represents the Postgres Sync consumer worker.
type Worker struct {
    consumer   *kafka.Consumer
    cartRepo   port.CartRepository
    cfg        config.PostgresSyncConfig
    logger     *zap.Logger
    wg         sync.WaitGroup
    stopCh     chan struct{}
}

// NewWorker creates a new Postgres Sync worker.
func NewWorker(
    kafkaConsumer *kafka.Consumer,
    cartRepo port.CartRepository,
    cfg config.PostgresSyncConfig,
    logger *zap.Logger,
) *Worker {
    return &Worker{
        consumer: kafkaConsumer,
        cartRepo: cartRepo,
        cfg:       cfg,
        logger:    logger,
        stopCh:   make(chan struct{}),
    }
}

// Start begins consuming Kafka messages.
func (w *Worker) Start(ctx context.Context) error {
    w.wg.Add(1)
    go w.consumeLoop(ctx)
    w.logger.Info("postgres sync worker started")
    return nil
}

func (w *Worker) Stop() {
    close(w.stopCh)
    w.wg.Wait()
    w.logger.Info("postgres sync worker stopped")
}

func (w *Worker) consumeLoop(ctx context.Context) {
    defer w.wg.Done()

    for {
        select {
        case <-ctx.Done():
            return
        case <-w.stopCh:
            return
        default:
            w.processBatch(ctx)
        }
    }
}

func (w *Worker) processBatch(ctx context.Context) {
    // Poll batch from Kafka
    batch, err := w.consumer.PollBatch(ctx, w.cfg.BatchSize, 5*time.Second)
    if err != nil {
        if err == context.DeadlineExceeded {
            return // No messages
        }
        w.logger.Warn("poll error", zap.Error(err))
        return
    }

    // Group by cart_id for per-cart transactions
    cartBatches := groupByCartID(batch)

    // Process each cart in its own transaction
    for cartID, events := range cartBatches {
        w.processCartBatch(ctx, cartID, events)
    }

    // Commit offsets after all batches processed
    batch.Commit()
}

// groupByCartID groups Kafka messages by cart_id.
func groupByCartID(batch *kafka.MessageBatch) map[string][]*event.CartEventEnvelope {
    result := make(map[string][]*event.CartEventEnvelope)
    for _, msg := range batch.Messages {
        env, err := kafka.ParseCartEvent(msg.Message)
        if err != nil {
            continue
        }
        cartID := env.AggregateID // cart_id from stream message
        result[cartID] = append(result[cartID], env)
    }
    return result
}

// processCartBatch processes all events for one cart in a single transaction.
func (w *Worker) processCartBatch(ctx context.Context, cartID string, events []*event.CartEventEnvelope) {
    for _, env := range events {
        if err := w.processEvent(ctx, env); err != nil {
            w.logger.Error("failed to process event",
                zap.String("cart_id", cartID),
                zap.String("event_type", env.EventType),
                zap.String("stream_id", env.StreamID),
                zap.Error(err),
            )
            // Continue processing other events — don't fail entire batch
            continue
        }
    }
}

// processEvent processes a single cart event.
func (w *Worker) processEvent(ctx context.Context, env *event.CartEventEnvelope) error {
    switch env.EventType {
    case event.EventTypeCartItemAdded, event.EventTypeCartItemUpdated:
        return w.handleItemUpsert(ctx, env)
    case event.EventTypeCartItemDeleted:
        return w.handleItemDelete(ctx, env)
    case event.EventTypeCartCleared:
        return w.handleCartCleared(ctx, env)
    case event.EventTypeCartCheckedOut:
        return w.handleCartCheckedOut(ctx, env)
    default:
        return nil // Unknown event type — skip
    }
}

func (w *Worker) handleItemUpsert(ctx context.Context, env *event.CartEventEnvelope) error {
    payload, err := event.ParseItemPayload(fmt.Sprintf("%v", env.Payload))
    if err != nil {
        return fmt.Errorf("parse item payload: %w", err)
    }

    // Build CartItem entity
    item := &entity.CartItem{}
    item = entity.RestoreCartItem(
        payload.CartID+":"+payload.SkuID,
        payload.CartID,
        payload.ProductID,
        payload.SkuID,
        payload.Quantity,
        payload.UnitPriceAtAdd,
        entity.ProductSnapshot{
            ProductID:   payload.ProductID,
            ProductName: payload.ProductName,
            VariantInfo: payload.VariantInfo,
            ImageURL:    payload.ImageURL,
            IsAvailable: payload.IsAvailable,
            AddedAt:     payload.AddedAt,
            UpdatedAt:   payload.UpdatedAt,
        },
        env.StreamID,
        time.Now(), time.Now(),
    )

    // Upsert to PostgreSQL with stream_id guard
    if err := w.cartRepo.UpsertCartItem(ctx, item, env.StreamID); err != nil {
        return err
    }

    // Upsert cart master with ACTIVE status
    cart := &entity.Cart{}
    cart = entity.RestoreCart(
        payload.CartID, payload.UserID,
        entity.CartStatusActive, env.StreamID,
        time.Now(), time.Now(),
    )
    return w.cartRepo.UpsertCartMaster(ctx, cart, env.StreamID)
}

func (w *Worker) handleItemDelete(ctx context.Context, env *event.CartEventEnvelope) error {
    payloadJSON := fmt.Sprintf("%v", env.Payload)
    payload, err := event.ParseDeletedPayload(payloadJSON)
    if err != nil {
        return fmt.Errorf("parse delete payload: %w", err)
    }

    itemID := payload.CartID + ":" + payload.SkuID
    _, err = w.cartRepo.DeleteCartItem(ctx, itemID, env.StreamID)
    return err
}

func (w *Worker) handleCartCleared(ctx context.Context, env *event.CartEventEnvelope) error {
    payloadJSON := fmt.Sprintf("%v", env.Payload)
    payload, err := event.ParseItemPayload(payloadJSON)
    if err != nil {
        return fmt.Errorf("parse cleared payload: %w", err)
    }
    return w.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusDeleted, env.StreamID)
}

func (w *Worker) handleCartCheckedOut(ctx context.Context, env *event.CartEventEnvelope) error {
    payloadJSON := fmt.Sprintf("%v", env.Payload)
    payload, err := event.ParseItemPayload(payloadJSON)
    if err != nil {
        return fmt.Errorf("parse checked_out payload: %w", err)
    }
    return w.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusCheckedOut, env.StreamID)
}
```

---

## 4. Worker #3: Abandoned Cart Marker (`internal/workers/abandoned_marker/`)

### 4.1. Overview

- **Loại:** CronJob (robfig/cron)
- **Sứ mệnh:** Đánh dấu carts ACTIVE quá 30 ngày thành ABANDONED
- **Ref:** `cart_workers.md` §3

```go
// internal/workers/abandoned_marker/job.go
package abandoned_marker

import (
    "context"
    "encoding/json"
    "fmt"
    "time"

    "go.uber.org/zap"

    "cart-service/internal/common/keybuilder"
    "cart-service/internal/domain/event"
    "cart-service/internal/domain/port"
    "cart-service/internal/infrastructure/config"
)

type Job struct {
    cartRepo     port.CartRepository
    outboxWriter port.OutboxWriter
    cfg          config.AbandonedMarkerConfig
    numShards    int
    logger       *zap.Logger
}

func NewJob(
    cartRepo port.CartRepository,
    outboxWriter port.OutboxWriter,
    cfg config.AbandonedMarkerConfig,
    numShards int,
    logger *zap.Logger,
) *Job {
    return &Job{
        cartRepo:    cartRepo,
        outboxWriter: outboxWriter,
        cfg:          cfg,
        numShards:   numShards,
        logger:      logger,
    }
}

// Run executes the abandoned cart marking job.
func (j *Job) Run(ctx context.Context) error {
    j.logger.Info("abandoned cart marker job started")

    for {
        carts, err := j.cartRepo.ListCartsForAbandonedMark(
            ctx, j.cfg.InactiveThresholdDays, j.cfg.BatchSize,
        )
        if err != nil {
            return fmt.Errorf("list carts: %w", err)
        }
        if len(carts) == 0 {
            break // No more carts to process
        }

        for _, cart := range carts {
            j.markCartAbandoned(ctx, cart)
        }
    }

    j.logger.Info("abandoned cart marker job completed")
    return nil
}

func (j *Job) markCartAbandoned(ctx context.Context, cart *entity.Cart) {
    // 1. Update DB: set status = ABANDONED
    if _, err := j.cartRepo.UpdateCartStatus(
        ctx, cart.CartID(), entity.CartStatusAbandoned, "",
    ); err != nil {
        j.logger.Error("failed to update cart status",
            zap.String("cart_id", cart.CartID()), zap.Error(err))
        return
    }

    // 2. Publish CART_ABANDONED event to Redis Stream Outbox
    shardID := keybuilder.ComputeShardID(cart.UserID(), j.numShards)
    nowMs := fmt.Sprintf("%d", time.Now().UTC().UnixMilli())
    payload := map[string]interface{}{
        "cart_id":      cart.CartID(),
        "user_id":      cart.UserID(),
        "abandoned_at": nowMs,
    }
    payloadJSON, _ := json.Marshal(payload)

    streamID, err := j.outboxWriter.WriteEvent(
        ctx, shardID, cart.UserID(),
        event.EventTypeCartAbandoned, string(payloadJSON),
    )
    if err != nil {
        j.logger.Error("XADD CART_ABANDONED failed",
            zap.String("cart_id", cart.CartID()), zap.Error(err))
        return
    }

    // 3. Mark event as published in DB
    if err := j.cartRepo.MarkAbandonedEventPublished(ctx, cart.CartID()); err != nil {
        j.logger.Error("failed to mark event published",
            zap.String("cart_id", cart.CartID()), zap.Error(err))
        return
    }

    j.logger.Info("cart marked abandoned",
        zap.String("cart_id", cart.CartID()),
        zap.String("stream_id", streamID))
}
```

---

## 5. Worker #4: Data Archival Cleaner (`internal/workers/archival_cleaner/`)

### 5.1. Overview

- **Loại:** CronJob
- **Sứ mệnh:** Hard delete carts theo archival policy (DELETED/CHECKED_OUT: 90 days, ABANDONED: 180 days)
- **Ref:** `cart_workers.md` §4

```go
// internal/workers/archival_cleaner/job.go
package archival_cleaner

import (
    "context"
    "time"

    "go.uber.org/zap"

    "cart-service/internal/domain/entity"
    "cart-service/internal/domain/port"
    "cart-service/internal/infrastructure/config"
)

type Job struct {
    cartRepo port.CartRepository
    cfg      config.ArchivalCleanerConfig
    logger   *zap.Logger
}

func NewJob(cartRepo port.CartRepository, cfg config.ArchivalCleanerConfig, logger *zap.Logger) *Job {
    return &Job{cartRepo: cartRepo, cfg: cfg, logger: logger}
}

// Run executes the archival cleaner job.
func (j *Job) Run(ctx context.Context) error {
    j.logger.Info("archival cleaner job started")

    // Step 1: Delete DELETED and CHECKED_OUT carts older than 90 days
    deletedOlderThan := time.Now().UTC().AddDate(0, 0, -j.cfg.DeletedTTLDays)
    j.cleanupCarts(ctx, deletedOlderThan, []entity.CartStatus{
        entity.CartStatusDeleted, entity.CartStatusCheckedOut,
    }, "deleted/checked_out")

    // Step 2: Delete ABANDONED carts older than 180 days
    abandonedOlderThan := time.Now().UTC().AddDate(0, 0, -j.cfg.AbandonedTTLDays)
    j.cleanupCarts(ctx, abandonedOlderThan, []entity.CartStatus{
        entity.CartStatusAbandoned,
    }, "abandoned")

    j.logger.Info("archival cleaner job completed")
    return nil
}

func (j *Job) cleanupCarts(ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, label string) {
    for {
        cartIDs, err := j.cartRepo.ListCartsForCleanup(ctx, olderThan, statuses, 500)
        if err != nil {
            j.logger.Error("list carts for cleanup failed", zap.Error(err))
            return
        }
        if len(cartIDs) == 0 {
            break
        }

        for _, cartID := range cartIDs {
            if err := j.cartRepo.HardDeleteCart(ctx, cartID); err != nil {
                j.logger.Error("hard delete failed",
                    zap.String("cart_id", cartID),
                    zap.String("type", label),
                    zap.Error(err))
                continue
            }
            j.logger.Debug("hard deleted cart",
                zap.String("cart_id", cartID),
                zap.String("type", label))
        }
    }
}
```

---

## 6. Worker #5: Stream Trimmer (`internal/workers/stream_trimmer/`)

### 6.1. Overview

- **Loại:** Periodic goroutine
- **Sứ mệnh:** Trim Redis Stream messages đã ACK để giải phóng RAM
- **Ref:** `cart_workers.md` §5, `cart_outbox_pattern.md` §2.3

```go
// internal/workers/stream_trimmer/job.go
package stream_trimmer

import (
    "context"
    "fmt"
    "time"

    "github.com/redis/go-redis/v9"
    "go.uber.org/zap"

    "cart-service/internal/common/keybuilder"
    "cart-service/internal/infrastructure/config"
)

type Job struct {
    redisClient   *redis.Client
    numShards     int
    cfg           config.StreamTrimmerConfig
    logger        *zap.Logger
}

func NewJob(client *redis.Client, numShards int, cfg config.StreamTrimmerConfig, logger *zap.Logger) *Job {
    return &Job{
        redisClient: client,
        numShards:   numShards,
        cfg:         cfg,
        logger:      logger,
    }
}

func (j *Job) RunPeriodic(ctx context.Context) {
    ticker := time.NewTicker(j.cfg.Interval)
    defer ticker.Stop()

    for {
        select {
        case <-ctx.Done():
            return
        case <-ticker.C:
            j.trimAllShards(ctx)
        }
    }
}

func (j *Job) trimAllShards(ctx context.Context) {
    for shardID := 0; shardID < j.numShards; shardID++ {
        j.trimShard(ctx, shardID)
    }
}

func (j *Job) trimShard(ctx context.Context, shardID int) {
    streamKey := keybuilder.StreamShardKey(shardID)

    // Step 1: Check stream state
    len, err := j.redisClient.XLen(ctx, streamKey).Result()
    if err != nil {
        if err == redis.Nil {
            return
        }
        j.logger.Warn("XLEN failed", zap.Int("shard", shardID), zap.Error(err))
        return
    }
    if len == 0 {
        return // Empty stream
    }

    // Step 2: Check consumer group
    groups, err := j.redisClient.XInfoGroups(ctx, streamKey).Result()
    if err != nil {
        if err == redis.Nil {
            // No consumer group yet — skip this stream
            j.logger.Debug("no consumer group, skipping trim",
                zap.String("stream", streamKey))
            return
        }
        j.logger.Warn("XINFO GROUPS failed", zap.String("stream", streamKey), zap.Error(err))
        return
    }

    if len(groups) == 0 {
        return // No groups
    }

    // Step 3: Find oldest pending message
    group := groups[0]
    pendingCount := group.Pending

    var safeID string
    if pendingCount == 0 {
        safeID = group.LastDeliveredID
    } else {
        // Get oldest pending ID
        pending, err := j.redisClient.XPending(ctx, streamKey, group.Name).Result()
        if err != nil {
            j.logger.Warn("XPENDING failed", zap.String("stream", streamKey), zap.Error(err))
            return
        }
        // Choose the minimum of oldest pending and last delivered
        if pending.OldestPendingID != "" && pending.OldestPendingID < group.LastDeliveredID {
            safeID = pending.OldestPendingID
        } else {
            safeID = group.LastDeliveredID
        }
    }

    // Step 4: Trim to safe ID (MINID ~ keeps messages > safeID)
    if safeID == "" || safeID == "0" {
        return
    }

    trimmed, err := j.redisClient.XTrim(ctx, streamKey, "MINID", "~", safeID).Result()
    if err != nil {
        j.logger.Error("XTRIM failed",
            zap.String("stream", streamKey),
            zap.String("safe_id", safeID),
            zap.Error(err))
        return
    }

    if trimmed > 0 {
        j.logger.Info("stream trimmed",
            zap.String("stream", streamKey),
            zap.Int64("messages_trimmed", trimmed),
            zap.String("safe_id", safeID))
    }
}
```

---

## 7. Worker #6: Order Event Consumer (`internal/workers/order_event_consumer/`)

### 7.1. Overview

- **Loại:** Kafka Consumer
- **Sứ mệnh:** Nhận ORDER_PAID, xóa các SKU đã mua khỏi Redis
- **Ref:** `cart_workers.md` §6

```go
// internal/workers/order_event_consumer/worker.go
package order_event_consumer

import (
    "context"
    "encoding/json"
    "fmt"
    "sync"
    "time"

    "github.com/IBM/sarama"
    "github.com/redis/go-redis/v9"
    "go.uber.org/zap"

    "cart-service/internal/common/keybuilder"
    "cart-service/internal/domain/event"
    "cart-service/internal/domain/port"
    "cart-service/internal/infrastructure/config"
)

type Worker struct {
    consumer    *kafka.Consumer
    redisClient *redis.Client
    luaRepo     port.CartLuaRepository
    cacheRepo   port.CacheRepository
    numShards   int
    logger      *zap.Logger
    wg          sync.WaitGroup
    stopCh      chan struct{}
}

func NewWorker(
    consumer *kafka.Consumer,
    redisClient *redis.Client,
    luaRepo port.CartLuaRepository,
    cacheRepo port.CacheRepository,
    numShards int,
    logger *zap.Logger,
) *Worker {
    return &Worker{
        consumer:    consumer,
        redisClient: redisClient,
        luaRepo:    luaRepo,
        cacheRepo:  cacheRepo,
        numShards:  numShards,
        logger:     logger,
        stopCh:     make(chan struct{}),
    }
}

func (w *Worker) Start(ctx context.Context) error {
    w.wg.Add(1)
    go w.run(ctx)
    w.logger.Info("order event consumer started")
    return nil
}

func (w *Worker) Stop() {
    close(w.stopCh)
    w.wg.Wait()
}

func (w *Worker) run(ctx context.Context) {
    defer w.wg.Done()
    for {
        select {
        case <-ctx.Done():
            return
        case <-w.stopCh:
            return
        default:
            w.consume(ctx)
        }
    }
}

func (w *Worker) consume(ctx context.Context) {
    batch, err := w.consumer.PollBatch(ctx, 50, 5*time.Second)
    if err != nil {
        if err == context.DeadlineExceeded {
            return
        }
        w.logger.Warn("poll error", zap.Error(err))
        return
    }

    for _, msg := range batch.Messages {
        w.processMessage(ctx, msg.Message)
    }

    batch.Commit()
}

func (w *Worker) processMessage(ctx context.Context, msg *sarama.ConsumerMessage) {
    // Parse ORDER_PAID event
    var wrapper struct {
        EventType string                   `json:"event_type"`
        Payload   event.OrderPaidPayload `json:"payload"`
    }
    if err := json.Unmarshal(msg.Value, &wrapper); err != nil {
        w.logger.Warn("unmarshal failed", zap.Error(err))
        return
    }
    if wrapper.EventType != event.EventTypeOrderPaid {
        return // Not an ORDER_PAID event
    }

    payload := wrapper.Payload

    // Validate cart_id format
    if !isValidCartID(payload.CartID) {
        w.logger.Warn("invalid cart_id", zap.String("cart_id", payload.CartID))
        return
    }

    // Idempotency check
    if w.isOrderProcessed(ctx, payload.OrderID) {
        w.logger.Debug("order already processed", zap.Int64("order_id", payload.OrderID))
        return
    }

    // Acquire idempotency lock
    if err := w.acquireIdempotencyLock(ctx, payload.OrderID); err != nil {
        w.logger.Warn("failed to acquire lock", zap.Int64("order_id", payload.OrderID))
        return
    }

    // Extract user_id from cart_id (format: {cart}:{user_id})
    userID := extractUserIDFromCartID(payload.CartID)
    shardID := keybuilder.ComputeShardID(userID, w.numShards)
    streamShard := keybuilder.StreamShardKey(shardID)

    // Execute batch delete
    skuIDsJSON, _ := json.Marshal(payload.SkuIDs)
    timestampMs := time.Now().UTC().UnixMilli()

    _, err = w.luaRepo.BatchDelete(
        ctx,
        payload.CartID, streamShard,
        timestampMs, event.EventTypeCartCheckedOut,
        userID, string(skuIDsJSON),
    )
    if err != nil {
        w.logger.Error("batch delete failed",
            zap.Int64("order_id", payload.OrderID),
            zap.Error(err))
        return
    }

    // Mark as processed
    w.markOrderProcessed(ctx, payload.OrderID)

    w.logger.Info("order processed, cart cleaned",
        zap.Int64("order_id", payload.OrderID),
        zap.String("cart_id", payload.CartID),
        zap.Int("sku_count", len(payload.SkuIDs)))
}

func (w *Worker) isOrderProcessed(ctx context.Context, orderID int64) bool {
    key := keybuilder.IdempotencyKey(orderID)
    val, err := w.redisClient.Get(ctx, key).Result()
    return err == nil && val == "DONE"
}

func (w *Worker) acquireIdempotencyLock(ctx context.Context, orderID int64) error {
    key := keybuilder.IdempotencyKey(orderID)
    return w.redisClient.SetNX(ctx, key, "PROCESSING", 5*time.Minute).Err()
}

func (w *Worker) markOrderProcessed(ctx context.Context, orderID int64) {
    key := keybuilder.IdempotencyKey(orderID)
    w.redisClient.SetEx(ctx, key, "DONE", 24*time.Hour)
}

func isValidCartID(cartID string) bool {
    // Format: {cart}:{user_id}
    return len(cartID) > 7 && cartID[:6] == "{cart}"
}

func extractUserIDFromCartID(cartID string) string {
    // Format: {cart}:user_id — extract user_id part
    for i := 0; i < len(cartID); i++ {
        if cartID[i] == ':' && i > 6 {
            return cartID[i+1:]
        }
    }
    return ""
}
```

---

## 8. File Locations

| Worker | Vị trí | Description |
|--------|---------|-------------|
| Outbox Relay | `internal/workers/outbox_relay/worker.go` | XREADGROUP → Kafka |
| Postgres Sync | `internal/workers/postgres_sync/worker.go` | Kafka → PostgreSQL |
| Abandoned Marker | `internal/workers/cronjobs/abandoned_marker.go` | Cron → XADD |
| Archival Cleaner | `internal/workers/cronjobs/archival_cleaner.go` | Hard delete |
| Stream Trimmer | `internal/workers/stream_trimmer/job.go` | XTRIM periodic |
| Order Consumer | `internal/workers/order_event_consumer/worker.go` | ORDER_PAID → Lua |

---

## 9. Checklist triển khai Module 15-21

```markdown
## 15-21 - Workers
- [x] Tạo `internal/workers/outbox_relay/worker.go` với XREADGROUP loop
- [x] Tạo `internal/workers/outbox_relay/consumer.go` với Kafka publish
- [x] Tạo `internal/workers/outbox_relay/metrics.go` với metrics tracking
- [x] Tạo `internal/workers/outbox_relay/pubsub.go` với PubSub outbox writer
- [x] Tạo `internal/workers/outbox_relay/dlq_handler.go` với DLQ handling
- [x] Tạo `internal/workers/postgres_sync/worker.go` với batch processing
- [x] Tạo `internal/workers/postgres_sync/consumer.go` với consumer wrapper
- [x] Tạo `internal/workers/postgres_sync/batch_processor.go` với per-cart transaction
- [x] Tạo `internal/workers/postgres_sync/event_parser.go` với event parsing
- [x] Tạo `internal/workers/postgres_sync/metrics.go` với metrics
- [x] Tạo `internal/workers/postgres_sync/models.go` với data models
- [x] Tạo `internal/workers/abandoned_marker/job.go` với cronjob logic
- [x] Tạo `internal/workers/archival_cleaner/job.go` với hard delete logic
- [x] Tạo `internal/workers/stream_trimmer/job.go` với XTRIM MINID
- [x] Tạo `internal/workers/order_consumer/worker.go` với ORDER_PAID handling
- [x] Tạo `internal/workers/order_consumer/metrics.go` với metrics
- [x] Tạo `internal/workers/cronjobs/metrics.go` với Scheduler wrapper
- [x] Thêm helper functions vào `keybuilder` (ComputeShardID, IdempotencyKey)
- [x] Thêm PollBatch/Commit methods vào Kafka Consumer
- [x] Viết unit tests: outbox relay message processing
- [x] Viết unit tests: idempotency lock behavior
- [x] Viết unit tests: batch processor grouping
- [x] Viết unit tests: cronjobs job interface
- [x] Verify `go build ./internal/workers/...` compile thành công
```

---

*Tiếp theo: [22-cmd-main.md](22-cmd-main.md) — Application entry point và DI wiring.*
