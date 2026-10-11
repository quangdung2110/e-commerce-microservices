package kafka

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/infrastructure/cloudevents"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// MessageHandler processes one Kafka message durably. A nil return means the
// message may be acknowledged. Errors are classified by FailureAction: an
// invalid envelope or an errors.Permanent goes to the DLQ at once, anything
// else is retried in place up to consume_max_attempts.
type MessageHandler func(ctx context.Context, msg *sarama.ConsumerMessage) error

// Action is what the consumer does after a failed attempt.
type Action int

const (
	// ActionRetry waits the backoff and calls the handler again.
	ActionRetry Action = iota
	// ActionDeadLetter parks the message on the DLQ and acknowledges it.
	ActionDeadLetter
	// ActionAbort leaves the message unacknowledged: the session is ending
	// and Kafka will redeliver it.
	ActionAbort
)

// Reasons written to x-dlq-reason.
const (
	ReasonInvalidEnvelope = "invalid_envelope"
	ReasonMaxAttempts     = "max_attempts_exceeded"
)

// FailureAction is the pure retry/DLQ decision (02-pipeline.md §4, brief:
// "unparseable envelope / persistent DB failure after consume_max_attempts →
// DLQ"). attempt is 1-based.
func FailureAction(err error, attempt, maxAttempts int, ctxErr error) (Action, string) {
	if ctxErr != nil {
		return ActionAbort, ""
	}
	if cloudevents.IsInvalid(err) {
		return ActionDeadLetter, ReasonInvalidEnvelope
	}
	if code, ok := apperr.PermanentCode(err); ok {
		return ActionDeadLetter, code
	}
	if maxAttempts < 1 {
		maxAttempts = 1
	}
	if attempt >= maxAttempts {
		return ActionDeadLetter, ReasonMaxAttempts
	}
	return ActionRetry, ""
}

// Backoff returns the exponential delay before the next attempt (1-based).
func Backoff(attempt int, min, max time.Duration) time.Duration {
	if min <= 0 {
		min = 200 * time.Millisecond
	}
	d := min
	for i := 1; i < attempt; i++ {
		d *= 2
		if max > 0 && d >= max {
			return max
		}
	}
	if max > 0 && d > max {
		return max
	}
	return d
}

// Consumer runs one consumer group over one source topic.
type Consumer struct {
	group   sarama.ConsumerGroup
	src     config.KafkaSourceConfig
	handler *groupHandler
	logger  *zap.Logger
}

// NewConsumer opens the consumer group for src. lane is the metric label
// (critical | transactional | bulk | auth | review | cart | preferences |
// user_lifecycle).
func NewConsumer(cfg config.KafkaConfig, src config.KafkaSourceConfig, lane string, handler MessageHandler, dlq port.DeadLetterPublisher, metrics *telemetry.Metrics, logger *zap.Logger) (*Consumer, error) {
	if src.Topic == "" || src.Group == "" {
		return nil, fmt.Errorf("kafka: source %s: topic and group are required", lane)
	}
	saramaCfg, err := baseConfig(cfg)
	if err != nil {
		return nil, err
	}
	saramaCfg.Consumer.Return.Errors = true
	// Offsets are marked only after the handler's transaction committed;
	// the auto-commit ticker then flushes marked offsets (at-least-once).
	saramaCfg.Consumer.Offsets.AutoCommit.Enable = true
	saramaCfg.Consumer.Offsets.AutoCommit.Interval = time.Second
	saramaCfg.Consumer.Offsets.Initial = sarama.OffsetOldest
	saramaCfg.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{sarama.NewBalanceStrategyRange()}
	if cfg.SessionTimeout > 0 {
		saramaCfg.Consumer.Group.Session.Timeout = cfg.SessionTimeout
	}
	if cfg.HeartbeatInterval > 0 {
		saramaCfg.Consumer.Group.Heartbeat.Interval = cfg.HeartbeatInterval
	}
	if cfg.MaxProcessingTime > 0 {
		saramaCfg.Consumer.MaxProcessingTime = cfg.MaxProcessingTime
	}

	group, err := sarama.NewConsumerGroup(cfg.Brokers, src.Group, saramaCfg)
	if err != nil {
		return nil, fmt.Errorf("kafka: create consumer group %s: %w", src.Group, err)
	}
	return NewConsumerWith(group, cfg, src, lane, handler, dlq, metrics, logger), nil
}

// NewConsumerWith wraps an existing consumer group (tests).
func NewConsumerWith(group sarama.ConsumerGroup, cfg config.KafkaConfig, src config.KafkaSourceConfig, lane string, handler MessageHandler, dlq port.DeadLetterPublisher, metrics *telemetry.Metrics, logger *zap.Logger) *Consumer {
	if logger == nil {
		logger = zap.NewNop()
	}
	logger = logger.With(zap.String("topic", src.Topic), zap.String("group", src.Group))
	return &Consumer{
		group: group,
		src:   src,
		handler: &groupHandler{
			lane:        lane,
			src:         src,
			handle:      handler,
			dlq:         dlq,
			maxAttempts: cfg.ConsumeMaxAttempts,
			minBackoff:  cfg.ConsumeMinBackoff,
			maxBackoff:  cfg.ConsumeMaxBackoff,
			metrics:     metrics,
			logger:      logger,
		},
		logger: logger,
	}
}

// Run consumes until ctx is cancelled. Consume returns on every rebalance, so
// it is looped.
func (c *Consumer) Run(ctx context.Context) error {
	go func() {
		for err := range c.group.Errors() {
			c.logger.Error("consumer group error", zap.Error(err))
		}
	}()
	for {
		if err := c.group.Consume(ctx, []string{c.src.Topic}, c.handler); err != nil {
			if errors.Is(err, sarama.ErrClosedConsumerGroup) || ctx.Err() != nil {
				return nil
			}
			c.logger.Error("consumer stopped, retrying", zap.Error(err))
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(5 * time.Second):
			}
			continue
		}
		if ctx.Err() != nil {
			return nil
		}
	}
}

// Close leaves the group.
func (c *Consumer) Close() error { return c.group.Close() }

// groupHandler is the sarama ConsumerGroupHandler; one instance serves every
// partition this member owns and keeps no per-partition state.
type groupHandler struct {
	lane        string
	src         config.KafkaSourceConfig
	handle      MessageHandler
	dlq         port.DeadLetterPublisher
	maxAttempts int
	minBackoff  time.Duration
	maxBackoff  time.Duration
	metrics     *telemetry.Metrics
	logger      *zap.Logger
}

func (h *groupHandler) Setup(sarama.ConsumerGroupSession) error   { return nil }
func (h *groupHandler) Cleanup(sarama.ConsumerGroupSession) error { return nil }

// ConsumeClaim processes one partition sequentially. Retrying in place blocks
// the partition on purpose: skipping ahead would reorder the aggregate being
// retried (05-ordering.md).
func (h *groupHandler) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for msg := range claim.Messages() {
		h.observeLag(claim, msg)
		if err := h.processWithRetry(session.Context(), msg); err != nil {
			// Session ending: leave the offset alone, Kafka redelivers.
			return nil
		}
		session.MarkMessage(msg, "")
	}
	return nil
}

func (h *groupHandler) observeLag(claim sarama.ConsumerGroupClaim, msg *sarama.ConsumerMessage) {
	if h.metrics == nil {
		return
	}
	lag := claim.HighWaterMarkOffset() - msg.Offset - 1
	if lag < 0 {
		lag = 0
	}
	h.metrics.KafkaConsumerLag.WithLabelValues(h.lane, msg.Topic, strconv.Itoa(int(msg.Partition))).Set(float64(lag))
}

// processWithRetry returns an error only when the session ended.
func (h *groupHandler) processWithRetry(ctx context.Context, msg *sarama.ConsumerMessage) error {
	for attempt := 1; ; attempt++ {
		err := h.handle(ctx, msg)
		if err == nil {
			return nil
		}
		action, reason := FailureAction(err, attempt, h.maxAttempts, ctx.Err())
		switch action {
		case ActionAbort:
			return ctx.Err()
		case ActionDeadLetter:
			h.deadLetter(ctx, msg, err, attempt, reason)
			return nil
		}
		h.logger.Warn("message handling failed, retrying",
			zap.Int32("partition", msg.Partition), zap.Int64("offset", msg.Offset),
			zap.Int("attempt", attempt), zap.Error(err))
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(Backoff(attempt, h.minBackoff, h.maxBackoff)):
		}
	}
}

// deadLetter parks the message with its headers and x-dlq-reason. The payload
// is not logged: it identifies a user.
func (h *groupHandler) deadLetter(ctx context.Context, msg *sarama.ConsumerMessage, cause error, attempts int, reason string) {
	fields := []zap.Field{
		zap.Int32("partition", msg.Partition), zap.Int64("offset", msg.Offset),
		zap.Int("attempts", attempts), zap.String("reason", reason), zap.Error(cause),
	}
	if h.metrics != nil {
		h.metrics.DeadLetterTotal.WithLabelValues(h.src.Topic, reason).Inc()
	}
	if h.dlq == nil || h.src.DLQ == "" {
		h.logger.Error("message dropped: no dead-letter topic configured", fields...)
		return
	}
	// Detached context: the DLQ write must still happen while shutting down.
	dlqCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()
	headers := CopyHeaders(msg)
	headers[HeaderDLQTopic] = msg.Topic
	headers[HeaderDLQGroup] = h.src.Group
	if err := h.dlq.PublishDeadLetter(dlqCtx, h.src.DLQ, msg.Key, msg.Value, headers, reason); err != nil {
		h.logger.Error("message could not be dead-lettered", append(fields, zap.NamedError("dlq_error", err))...)
		return
	}
	h.logger.Error("message moved to the dead-letter topic", fields...)
}

// CopyHeaders flattens sarama record headers into a map.
func CopyHeaders(msg *sarama.ConsumerMessage) map[string]string {
	out := make(map[string]string, len(msg.Headers)+2)
	for _, hd := range msg.Headers {
		if hd == nil {
			continue
		}
		out[string(hd.Key)] = string(hd.Value)
	}
	return out
}
