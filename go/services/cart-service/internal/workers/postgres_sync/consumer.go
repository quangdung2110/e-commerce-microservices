package postgres_sync

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
)

// KafkaConsumer represents a Kafka consumer for cart events
type KafkaConsumer struct {
	client       sarama.ConsumerGroup
	topic        string
	groupID      string
	ready        chan bool
	logger       *zap.Logger
	brokers      []string
	config       config.KafkaConsumerConfig
	handlers     ConsumerHandlers
	stopCh       chan struct{}
	wg           sync.WaitGroup
}

// ConsumerHandlers defines the handlers for consuming messages
type ConsumerHandlers interface {
	// HandleBatch processes a batch of messages
	HandleBatch(ctx context.Context, messages []*ConsumedMessage) error
}

// ConsumedMessage represents a consumed Kafka message
type ConsumedMessage struct {
	TopicPartition  string
	Topic           string
	Partition       int32
	Offset          int64
	Key             []byte
	Value           []byte
	Headers         map[string]string
	Timestamp       time.Time
	originalMessage *sarama.ConsumerMessage
}

// NewKafkaConsumer creates a new Kafka consumer
func NewKafkaConsumer(
	brokers []string,
	topic string,
	groupID string,
	cfg config.KafkaConsumerConfig,
	logger *zap.Logger,
) (*KafkaConsumer, error) {
	saramaConfig := sarama.NewConfig()

	// Consumer settings
	saramaConfig.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
		sarama.NewBalanceStrategyRoundRobin(),
	}

	// Session settings
	sessionTimeout := 30 * time.Second
	if cfg.SessionTimeout != "" {
		if d, err := time.ParseDuration(cfg.SessionTimeout); err == nil {
			sessionTimeout = d
		}
	}
	saramaConfig.Consumer.Group.Session.Timeout = sessionTimeout

	// Heartbeat settings
	heartbeatInterval := 3 * time.Second
	if cfg.HeartbeatInterval != "" {
		if d, err := time.ParseDuration(cfg.HeartbeatInterval); err == nil {
			heartbeatInterval = d
		}
	}
	saramaConfig.Consumer.Group.Heartbeat.Interval = heartbeatInterval

	// Fetch settings
	saramaConfig.Consumer.Fetch.Min = 1
	saramaConfig.Consumer.Fetch.Default = 1024 * 1024 // 1MB

	// Offset settings
	saramaConfig.Consumer.Offsets.Initial = sarama.OffsetNewest
	saramaConfig.Consumer.Offsets.AutoCommit.Enable = false // Manual commit

	client, err := sarama.NewConsumerGroup(brokers, groupID, saramaConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create consumer group: %w", err)
	}

	return &KafkaConsumer{
		client:   client,
		topic:    topic,
		groupID:  groupID,
		brokers:  brokers,
		logger:   logger,
		config:   cfg,
		ready:    make(chan bool),
		stopCh:   make(chan struct{}),
	}, nil
}

// SetHandlers sets the consumer handlers
func (c *KafkaConsumer) SetHandlers(handlers ConsumerHandlers) {
	c.handlers = handlers
}

// Start starts consuming messages
func (c *KafkaConsumer) Start(ctx context.Context) error {
	if c.handlers == nil {
		return fmt.Errorf("handlers not set")
	}

	c.logger.Info("Starting Kafka consumer",
		zap.String("topic", c.topic),
		zap.String("group_id", c.groupID),
		zap.Strings("brokers", c.brokers),
	)

	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		for {
			select {
			case <-ctx.Done():
				c.logger.Info("Kafka consumer stopped due to context")
				return
			case <-c.stopCh:
				c.logger.Info("Kafka consumer stopped")
				return
			default:
				if err := c.client.Consume(ctx, []string{c.topic}, c); err != nil {
					c.logger.Error("Consumer error", zap.Error(err))
				}
				// Reset ready channel for rebalance
				if ctx.Err() == nil {
					c.ready = make(chan bool)
				}
			}
		}
	}()

	// Wait for consumer to be ready
	<-c.ready
	c.logger.Info("Kafka consumer is ready")

	return nil
}

// Stop stops the consumer
func (c *KafkaConsumer) Stop() error {
	close(c.stopCh)
	c.wg.Wait()
	return c.client.Close()
}

// Setup is run at the beginning of a new session
func (c *KafkaConsumer) Setup(sarama.ConsumerGroupSession) error {
	close(c.ready)
	return nil
}

// Cleanup is run at the end of a session
func (c *KafkaConsumer) Cleanup(sarama.ConsumerGroupSession) error {
	return nil
}

// ConsumeClaim processes messages from a partition
func (c *KafkaConsumer) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	// Collect messages into batches
	batch := make([]*ConsumedMessage, 0, c.config.MaxPollRecords)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-session.Context().Done():
			// Process remaining batch before exit
			if len(batch) > 0 {
				c.processBatch(session.Context(), session, batch)
			}
			return nil

		case message, ok := <-claim.Messages():
			if !ok {
				// Channel closed, process remaining batch
				if len(batch) > 0 {
					c.processBatch(session.Context(), session, batch)
				}
				return nil
			}

			// Parse message
			consumedMsg := &ConsumedMessage{
				Topic:           message.Topic,
				TopicPartition:  fmt.Sprintf("%s-%d", message.Topic, message.Partition),
				Partition:       message.Partition,
				Offset:          message.Offset,
				Key:             message.Key,
				Value:           message.Value,
				Headers:         make(map[string]string),
				Timestamp:       message.Timestamp,
				originalMessage: message,
			}

			// Parse headers
			for _, h := range message.Headers {
				if h != nil {
					consumedMsg.Headers[string(h.Key)] = string(h.Value)
				}
			}

			batch = append(batch, consumedMsg)

			// Process batch when full or on ticker
			if len(batch) >= c.config.MaxPollRecords {
				c.processBatch(session.Context(), session, batch)
				batch = make([]*ConsumedMessage, 0, c.config.MaxPollRecords)
			}

		case <-ticker.C:
			// Process batch on ticker (for smaller batches)
			if len(batch) > 0 {
				c.processBatch(session.Context(), session, batch)
				batch = make([]*ConsumedMessage, 0, c.config.MaxPollRecords)
			}
		}
	}
}

// processBatch processes a batch of messages
func (c *KafkaConsumer) processBatch(ctx context.Context, session sarama.ConsumerGroupSession, batch []*ConsumedMessage) {
	if len(batch) == 0 {
		return
	}

	// Parse events from messages
	events := make([]*CartEvent, 0, len(batch))
	for _, msg := range batch {
		event, err := ParseCartEvent(msg.Value)
		if err != nil {
			c.logger.Warn("Failed to parse message",
				zap.Int32("partition", msg.Partition),
				zap.Int64("offset", msg.Offset),
				zap.Error(err),
			)
			continue
		}
		events = append(events, event)
	}

	if len(events) == 0 {
		// Still commit offsets even if no valid events
		c.commitOffsets(session, batch)
		return
	}

	// Process events
	if err := c.handlers.HandleBatch(ctx, batch); err != nil {
		c.logger.Error("Failed to process batch",
			zap.Int("batch_size", len(batch)),
			zap.Error(err),
		)
		// Don't commit offsets on error - messages will be redelivered
		return
	}

	// Commit offsets on success
	c.commitOffsets(session, batch)
}

// commitOffsets commits the offsets for processed messages
func (c *KafkaConsumer) commitOffsets(session sarama.ConsumerGroupSession, batch []*ConsumedMessage) {
	if len(batch) == 0 {
		return
	}

	// Mark all messages as processed
	for _, msg := range batch {
		if msg.originalMessage != nil {
			session.MarkMessage(msg.originalMessage, "")
		}
	}

	// Commit offsets
	session.Commit()
}

// GetTopic returns the consumer topic
func (c *KafkaConsumer) GetTopic() string {
	return c.topic
}

// GetGroupID returns the consumer group ID
func (c *KafkaConsumer) GetGroupID() string {
	return c.groupID
}
