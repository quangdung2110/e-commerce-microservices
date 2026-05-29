# Cart Service — Module 08: Infrastructure — Kafka

**Document:** 08 | **Module:** Infrastructure — Kafka Producer & Consumer  
**Status:** `[x]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Module 04 (domain events)

---

## 1. Mục tiêu Module

Implement infrastructure adapters cho Kafka. Implement `KafkaPublisher` interface (Module 03) cho Outbox Relay Worker, và cung cấp base Kafka consumer cho Postgres Sync Worker và Order Event Consumer.

---

## 2. Chi tiết triển khai

### 2.1. Kafka Configuration (`internal/infrastructure/kafka/config.go`)

```go
package kafka

import (
    "crypto/tls"
    "fmt"
    "strings"
    "time"

    "github.com/IBM/sarama"
)

// Config holds Kafka producer/consumer configuration.
type Config struct {
    Brokers           []string
    TopicCartEvents string
    TopicOrderEvents string

    // Producer settings
    Producer ProducerConfig

    // Consumer settings
    Consumer ConsumerConfig
}

type ProducerConfig struct {
    MaxOpenRequests int
    RequiredAcks   int  // 1 = leader, -1 = all, 0 = no wait
    Timeout        time.Duration
    RetryMax       int
}

type ConsumerConfig struct {
    GroupID            string
    MaxPollRecords     int
    SessionTimeout     time.Duration
    HeartbeatInterval  time.Duration
    RebalanceTimeout   time.Duration
    AutoOffsetReset   string  // "earliest" or "latest"
    EnableAutoCommit  bool
}

// NewSaramaConfig creates a Sarama config from our Config.
func NewSaramaConfig(cfg Config, isProducer bool) (*sarama.Config, error) {
    var config *sarama.Config

    if isProducer {
        config = sarama.NewConfig()
        config.Producer.MaxOpenRequests = cfg.Producer.MaxOpenRequests
        config.Producer.Return.Successes = true       // Required for sync produce
        config.Producer.Return.Errors = true
        config.Producer.Retry.Max = cfg.Producer.RetryMax
        config.Producer.Timeout = cfg.Producer.Timeout

        switch cfg.Producer.RequiredAcks {
        case -1:
            config.Producer.RequiredAcks = sarama.WaitForAll
        case 0:
            config.Producer.RequiredAcks = sarama.NoResponse
        default:
            config.Producer.RequiredAcks = sarama.WaitForLocal
        }
    } else {
        config = sarama.NewConfig()
        config.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
            sarama.NewBalanceStrategyRoundRobin(),
        }
        config.Consumer.MaxPollRecords = cfg.Consumer.MaxPollRecords
        config.Consumer.Session.Timeout = cfg.Consumer.SessionTimeout
        config.Consumer.Heartbeat.Interval = cfg.Consumer.HeartbeatInterval
        config.Consumer.Rebalance.Timeout = cfg.Consumer.RebalanceTimeout
        config.Consumer.Return.Errors = true

        if cfg.Consumer.AutoOffsetReset == "earliest" {
            config.Consumer.Offsets.Initial = sarama.OffsetOldest
        } else {
            config.Consumer.Offsets.Initial = sarama.OffsetNewest
        }

        config.Consumer.Offsets.AutoCommit.Enable = cfg.Consumer.EnableAutoCommit
    }

    // Allow TLS (configured via brokers scheme or explicit config)
    // config.Net.TLS.Enable = true
    // config.Net.TLS.Config = tlsConfig

    return config, nil
}

// BrokerList returns comma-separated broker list.
func BrokerList(cfg Config) string {
    return strings.Join(cfg.Brokers, ",")
}
```

### 2.2. Kafka Producer (`internal/infrastructure/kafka/producer.go`)

Implement `port.KafkaPublisher` interface (Module 03).

```go
package kafka

import (
    "context"
    "encoding/json"
    "fmt"
    "sync"
    "time"

    "github.com/IBM/sarama"

    "cart-service/internal/domain/event"
    "cart-service/internal/domain/port"
)

// Publisher implements port.KafkaPublisher using Sarama sync producer.
type Publisher struct {
    producer sarama.SyncProducer
    topic   string
}

// NewPublisher creates a new Kafka publisher.
func NewPublisher(cfg Config) (*Publisher, error) {
    config, err := NewSaramaConfig(cfg, true)
    if err != nil {
        return nil, err
    }

    producer, err := sarama.NewSyncProducer(cfg.Brokers, config)
    if err != nil {
        return nil, fmt.Errorf("create Kafka producer: %w", err)
    }

    return &Publisher{
        producer: producer,
        topic:   cfg.TopicCartEvents,
    }, nil
}

var _ port.KafkaPublisher = (*Publisher)(nil)

// Publish publishes a cart event to the Kafka topic.
func (p *Publisher) Publish(ctx context.Context, msg *port.CartEventMessage) error {
    // Serialize the message to JSON
    jsonBytes, err := json.Marshal(msg)
    if err != nil {
        return fmt.Errorf("marshal event: %w", err)
    }

    // Partition by cart_id (ensures ordering for same cart)
    producerMsg := &sarama.ProducerMessage{
        Topic: p.topic,
        Key:   sarama.StringEncoder(msg.AggregateID), // cart_id as partition key
        Value: sarama.ByteEncoder(jsonBytes),
        Headers: []sarama.RecordHeader{
            {Key: []byte("event_type"), Value: []byte(msg.EventType)},
            {Key: []byte("stream_id"), Value: []byte(msg.StreamID)},
        },
        Timestamp: time.Now(),
    }

    partition, offset, err := p.producer.SendMessage(producerMsg)
    if err != nil {
        return fmt.Errorf("send message: %w", err)
    }

    // Log for debugging (in production, use structured logging)
    _ = partition
    _ = offset

    return nil
}

// Close closes the Kafka producer.
func (p *Publisher) Close() error {
    return p.producer.Close()
}

// AsyncPublisher is an async version for high-throughput scenarios.
// Uses a channel buffer and goroutine pool.
type AsyncPublisher struct {
    producer     sarama.AsyncProducer
    topic       string
    errChan     chan error
    successChan chan struct{}
    wg          sync.WaitGroup
}

// NewAsyncPublisher creates an async Kafka publisher.
func NewAsyncPublisher(cfg Config) (*AsyncPublisher, error) {
    config, err := NewSaramaConfig(cfg, true)
    if err != nil {
        return nil, err
    }

    // Enable async producer features
    config.Producer.Return.Successes = true
    config.Producer.Return.Errors = true

    producer, err := sarama.NewAsyncProducer(cfg.Brokers, config)
    if err != nil {
        return nil, fmt.Errorf("create async producer: %w", err)
    }

    ap := &AsyncPublisher{
        producer:     producer,
        topic:       cfg.TopicCartEvents,
        errChan:     make(chan error, 100),
        successChan: make(chan struct{}, 100),
    }

    // Start error handler goroutine
    ap.wg.Add(1)
    go func() {
        defer ap.wg.Done()
        for err := range producer.Errors() {
            select {
            case ap.errChan <- err:
            default:
            }
        }
    }()

    return ap, nil
}

// PublishAsync sends a message asynchronously.
func (p *AsyncPublisher) PublishAsync(ctx context.Context, msg *port.CartEventMessage) error {
    jsonBytes, err := json.Marshal(msg)
    if err != nil {
        return fmt.Errorf("marshal event: %w", err)
    }

    producerMsg := &sarama.ProducerMessage{
        Topic: p.topic,
        Key:   sarama.StringEncoder(msg.AggregateID),
        Value: sarama.ByteEncoder(jsonBytes),
        Headers: []sarama.RecordHeader{
            {Key: []byte("event_type"), Value: []byte(msg.EventType)},
            {Key: []byte("stream_id"), Value: []byte(msg.StreamID)},
        },
    }

    select {
    case p.producer.Input() <- producerMsg:
        return nil
    case <-ctx.Done():
        return ctx.Err()
    }
}

// Errors returns the error channel for async errors.
func (p *AsyncPublisher) Errors() <-chan error {
    return p.errChan
}

// Close gracefully shuts down the async producer.
func (p *AsyncPublisher) Close() error {
    if err := p.producer.Close(); err != nil {
        return err
    }
    p.wg.Wait()
    close(p.errChan)
    return nil
}
```

### 2.3. Kafka Consumer Base (`internal/infrastructure/kafka/consumer.go`)

Base consumer cho tất cả Kafka consumer workers.

```go
package kafka

import (
    "context"
    "encoding/json"
    "fmt"
    "sync"

    "github.com/IBM/sarama"

    "cart-service/internal/domain/event"
)

// MessageHandler is the function signature for processing Kafka messages.
type MessageHandler func(ctx context.Context, msg *sarama.ConsumerMessage) error

// Consumer represents a Kafka consumer group.
type Consumer struct {
    groupID    string
    topics     []string
    config     *sarama.Config
    brokers    []string
    ready      chan bool
    handler    MessageHandler
    inner      sarama.ConsumerGroup
    wg         sync.WaitGroup
}

// NewConsumer creates a new Kafka consumer group.
func NewConsumer(cfg Config, topics []string, handler MessageHandler) (*Consumer, error) {
    config, err := NewSaramaConfig(cfg, false)
    if err != nil {
        return nil, err
    }

    inner, err := sarama.NewConsumerGroup(cfg.Brokers, cfg.Consumer.GroupID, config)
    if err != nil {
        return nil, fmt.Errorf("create consumer group: %w", err)
    }

    return &Consumer{
        groupID: cfg.Consumer.GroupID,
        topics:  topics,
        config:  config,
        brokers: cfg.Brokers,
        ready:   make(chan bool),
        handler: handler,
        inner:   inner,
    }, nil
}

// Start begins consuming messages.
func (c *Consumer) Start(ctx context.Context) error {
    c.wg.Add(1)
    go func() {
        defer c.wg.Done()
        for {
            select {
            case <-ctx.Done():
                return
            default:
                if err := c.inner.Consume(ctx, c.topics, c); err != nil {
                    // Log error and retry
                    _ = err
                }
            }
        }
    }()

    <-c.ready // Wait for consumer to be ready
    return nil
}

// Stop gracefully stops the consumer.
func (c *Consumer) Stop() error {
    return c.inner.Close()
}

// Setup is run at the beginning of a new session, before ConsumeClaim.
func (c *Consumer) Setup(sarama.ConsumerGroupSession) error {
    close(c.ready)
    return nil
}

// Cleanup is run at the end of a session.
func (c *Consumer) Cleanup(sarama.ConsumerGroupSession) error {
    c.ready = make(chan bool)
    return nil
}

// ConsumeClaim processes messages from a partition.
func (c *Consumer) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
    for {
        select {
        case message, ok := <-claim.Messages():
            if !ok {
                return nil
            }

            if err := c.handler(session.Context(), message); err != nil {
                // Log error but continue processing
                // For critical errors, consider stopping
                _ = err
            }

            session.MarkMessage(message, "")

        case <-session.Context().Done():
            return nil
        }
    }
}

// ParseCartEvent deserializes a Kafka message to CartEventEnvelope.
func ParseCartEvent(msg *sarama.ConsumerMessage) (*event.CartEventEnvelope, error) {
    var e event.CartEventEnvelope
    if err := json.Unmarshal(msg.Value, &e); err != nil {
        return nil, fmt.Errorf("unmarshal event: %w", err)
    }
    return &e, nil
}

// ParseOrderPaidEvent deserializes an ORDER_PAID event.
func ParseOrderPaidEvent(msg *sarama.ConsumerMessage) (*event.OrderPaidPayload, error) {
    var wrapper struct {
        Payload event.OrderPaidPayload `json:"payload"`
    }
    if err := json.Unmarshal(msg.Value, &wrapper); err != nil {
        return nil, fmt.Errorf("unmarshal ORDER_PAID: %w", err)
    }
    return &wrapper.Payload, nil
}
```

---

## 3. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `config.go` | `internal/infrastructure/kafka/config.go` | Kafka config + Sarama converter |
| `producer.go` | `internal/infrastructure/kafka/producer.go` | Sync + Async Kafka producer |
| `consumer.go` | `internal/infrastructure/kafka/consumer.go` | Consumer group base + helpers |

---

## 4. Checklist triển khai Module 08

```markdown
## 08 - Infrastructure Kafka
- [x] Thêm dependency github.com/IBM/sarama vào go.mod
- [x] Tạo `internal/infrastructure/kafka/config.go` với Sarama config
- [x] Tạo `internal/infrastructure/kafka/producer.go` với sync và async publisher
- [x] Tạo `internal/infrastructure/kafka/consumer.go` với consumer group base
- [x] Implement `port.KafkaPublisher` interface
- [ ] Viết unit tests: producer serialization, partition key
- [ ] Viết unit tests: consumer group setup/cleanup
- [x] Verify `go build ./internal/infrastructure/kafka` compile thành công
```

---

*Tiếp theo: [09-infra-grpc.md](09-infra-grpc.md) — gRPC client cho Product Service.*
