package kafka

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/config"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/common/logging"
	"github.com/ecommerce/user-service/internal/common/reqctx"
	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/service"
)

// LifecycleProcessor is the part of the domain the consumer drives. Both
// commands are idempotent on the user id, which is what makes at-least-once
// delivery safe (WF-USER-001, WF-USER-006).
type LifecycleProcessor interface {
	Bootstrap(ctx context.Context, userID uuid.UUID, verifiedAt time.Time) (service.BootstrapResult, error)
	Anonymize(ctx context.Context, userID uuid.UUID, deletedAt, hardDeleteAfter time.Time, reason string) (service.AnonymizationResult, error)
}

// ComplaintProcessor is the part of the domain that reacts to a spam complaint.
// It is idempotent on the user id, like the lifecycle commands (WF-USER-009).
type ComplaintProcessor interface {
	DisableMarketing(ctx context.Context, userID uuid.UUID) (service.MarketingOptOutResult, error)
}

// DeadLetterSink parks a message the consumer cannot process in the given DLQ
// topic. Nothing is redriven automatically: an operator fixes the cause and
// replays under approval (docs/user/operations.md §5).
type DeadLetterSink interface {
	PublishDeadLetter(ctx context.Context, dlqTopic string, key, value []byte, reason string) error
}

// Consumer runs the consumer group of t.auth.events (bootstrap and deletion) and
// t.notification.outcomes.v1 (spam complaints).
//
// Offsets are committed only after the domain change is durable, so a crash
// mid-transaction replays the message instead of losing it. Retries happen
// inside the partition goroutine, which keeps the per-user ordering both
// contracts promise.
type Consumer struct {
	group   sarama.ConsumerGroup
	topics  []string
	handler *eventHandler
	logger  *zap.Logger
}

// NewConsumer opens the consumer group.
func NewConsumer(
	cfg config.KafkaConfig,
	lifecycle LifecycleProcessor,
	complaints ComplaintProcessor,
	dlq DeadLetterSink,
	logger *zap.Logger,
) (*Consumer, error) {
	saramaCfg := sarama.NewConfig()
	saramaCfg.Version = sarama.V3_5_0_0
	saramaCfg.ClientID = "user-service"
	saramaCfg.Consumer.Return.Errors = true
	// Offsets are committed explicitly after each message is handled.
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
	applySASL(saramaCfg, cfg.SASLUsername, cfg.SASLPassword)

	group, err := sarama.NewConsumerGroup(cfg.Brokers, cfg.ConsumerGroup, saramaCfg)
	if err != nil {
		return nil, fmt.Errorf("kafka: create consumer group: %w", err)
	}

	return &Consumer{
		group:  group,
		topics: []string{cfg.AuthTopic, cfg.NotificationTopic},
		handler: &eventHandler{
			lifecycle:  lifecycle,
			complaints: complaints,
			dlq:        dlq,
			topics: topicRoutes{
				auth:         cfg.AuthTopic,
				authDLQ:      cfg.AuthDLQTopic,
				notification: cfg.NotificationTopic,
				notifDLQ:     cfg.NotificationDLQTopic,
			},
			maxAttempts: cfg.ConsumeMaxAttempts,
			minBackoff:  cfg.ConsumeMinBackoff,
			maxBackoff:  cfg.ConsumeMaxBackoff,
			logger:      logger,
		},
		logger: logger,
	}, nil
}

// Run consumes until the context is cancelled. Consume returns on every
// rebalance, so the call is looped rather than made once.
func (c *Consumer) Run(ctx context.Context) error {
	go func() {
		for err := range c.group.Errors() {
			c.logger.Error("event consumer group error", zap.Error(err))
		}
	}()

	for {
		if err := c.group.Consume(ctx, c.topics, c.handler); err != nil {
			if errors.Is(err, sarama.ErrClosedConsumerGroup) || ctx.Err() != nil {
				return nil
			}
			c.logger.Error("event consumer stopped, retrying", zap.Error(err))
			// A broker outage should not turn into a tight reconnect loop.
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

// Close leaves the consumer group.
func (c *Consumer) Close() error {
	return c.group.Close()
}

// topicRoutes names the topics the handler tells apart and the DLQ each one
// parks its failures in.
type topicRoutes struct {
	auth         string
	authDLQ      string
	notification string
	notifDLQ     string
}

// dlqFor returns the dead-letter topic of the topic a message came from.
func (r topicRoutes) dlqFor(topic string) string {
	if topic == r.notification {
		return r.notifDLQ
	}
	return r.authDLQ
}

// eventHandler is the sarama handler. One instance serves every partition this
// member owns; it holds no per-partition state.
type eventHandler struct {
	lifecycle   LifecycleProcessor
	complaints  ComplaintProcessor
	dlq         DeadLetterSink
	topics      topicRoutes
	maxAttempts int
	minBackoff  time.Duration
	maxBackoff  time.Duration
	logger      *zap.Logger
}

func (h *eventHandler) Setup(sarama.ConsumerGroupSession) error   { return nil }
func (h *eventHandler) Cleanup(sarama.ConsumerGroupSession) error { return nil }

// ConsumeClaim processes one partition sequentially. Retrying in place blocks
// the partition on purpose: skipping ahead would reorder the events of the user
// currently being retried.
func (h *eventHandler) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for message := range claim.Messages() {
		if err := h.handleWithRetry(session.Context(), message); err != nil {
			// The session is going away; the message will be redelivered.
			return nil
		}
		session.MarkMessage(message, "")
	}
	return nil
}

// handleWithRetry runs one message through the domain, retrying transient
// failures with exponential backoff and dead-lettering what cannot succeed.
// It returns an error only when the session ended.
func (h *eventHandler) handleWithRetry(ctx context.Context, message *sarama.ConsumerMessage) error {
	attempts := h.maxAttempts
	if attempts < 1 {
		attempts = 1
	}

	backoff := h.minBackoff
	if backoff <= 0 {
		backoff = time.Second
	}

	for attempt := 1; ; attempt++ {
		err := h.handle(ctx, message)
		if err == nil {
			return nil
		}

		permanent := !apperr.Retryable(err)
		if permanent || attempt >= attempts {
			h.deadLetter(ctx, message, err, attempt, permanent)
			return nil
		}

		h.logger.Warn("consumed event handling failed, retrying",
			zap.String("topic", message.Topic),
			zap.Int32("partition", message.Partition),
			zap.Int64("offset", message.Offset),
			zap.Int("attempt", attempt),
			zap.Error(err),
		)

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}

		backoff *= 2
		if h.maxBackoff > 0 && backoff > h.maxBackoff {
			backoff = h.maxBackoff
		}
	}
}

// handle routes one message by the topic it came from. A message that arrives on
// a topic this service does not know is acknowledged: it cannot be a contract
// this consumer owns.
func (h *eventHandler) handle(ctx context.Context, message *sarama.ConsumerMessage) error {
	switch message.Topic {
	case h.topics.notification:
		return h.handleNotification(ctx, message)
	default:
		return h.handleAuth(ctx, message)
	}
}

// handleNotification applies a delivery outcome of Notification. Only
// complaint-received matters here: the user reported spam, so marketing is
// switched off at the source (WF-USER-009). The other outcomes belong to
// Analytics and are acknowledged without being decoded any further.
func (h *eventHandler) handleNotification(ctx context.Context, message *sarama.ConsumerMessage) error {
	envelope, err := event.ParseNotificationEnvelope(message.Value)
	if err != nil {
		return apperr.ErrValidation.WithCause(err)
	}

	ctx = h.contextFor(ctx, envelope)
	logger := logging.FromContext(ctx)

	if envelope.Type != event.TypeNotificationComplaintReceived {
		logger.Debug("ignoring a notification outcome this service does not consume",
			zap.String("event_type", envelope.Type))
		return nil
	}

	payload, err := event.ParseComplaintReceived(envelope.Data)
	if err != nil {
		return apperr.ErrValidation.WithCause(err)
	}

	result, err := h.complaints.DisableMarketing(ctx, payload.UserID)
	if err != nil {
		return err
	}
	logger.Info("processed complaint-received",
		zap.Bool("marketing_disabled", result.Disabled),
		// Nothing changed: a redelivery, a user who already opted out, or one
		// without preferences. All are expected under at-least-once delivery.
		zap.Bool("no_change", !result.Disabled),
	)
	return nil
}

// handleAuth decodes one message of t.auth.events and applies it. An
// undecodable message and an unsupported version are permanent failures:
// retrying would decode exactly the same bytes.
func (h *eventHandler) handleAuth(ctx context.Context, message *sarama.ConsumerMessage) error {
	envelope, err := event.ParseAuthEnvelope(message.Value)
	if err != nil {
		return apperr.ErrValidation.WithCause(err)
	}

	ctx = h.contextFor(ctx, envelope)
	logger := logging.FromContext(ctx)

	switch envelope.Type {
	case event.TypeAuthUserVerifiedSuccess:
		payload, err := event.ParseUserVerifiedSuccess(envelope.Data)
		if err != nil {
			return apperr.ErrValidation.WithCause(err)
		}

		result, err := h.lifecycle.Bootstrap(ctx, payload.UserID, payload.VerifiedAt)
		if err != nil {
			return err
		}
		logger.Info("processed USER_VERIFIED_SUCCESS",
			zap.Bool("profile_created", result.ProfileCreated),
			zap.Bool("preferences_created", result.PreferencesCreated),
			zap.Bool("skipped_deleted", result.SkippedDeleted),
			// A delivery that created nothing is a duplicate, which is expected
			// under at-least-once delivery and worth counting.
			zap.Bool("duplicate", !result.ProfileCreated && !result.SkippedDeleted),
		)
		return nil

	case event.TypeAuthUserDeleted:
		payload, err := event.ParseUserDeleted(envelope.Data)
		if err != nil {
			return apperr.ErrValidation.WithCause(err)
		}

		result, err := h.lifecycle.Anonymize(ctx, payload.UserID, payload.DeletedAt, payload.HardDeleteAfter, payload.Reason)
		if err != nil {
			return err
		}
		logger.Info("processed USER_DELETED",
			zap.Bool("already_processed", result.AlreadyProcessed),
			zap.Bool("profile_anonymized", result.ProfileAnonymized),
			zap.Int64("addresses_deleted", result.AddressesDeleted),
			zap.Bool("preferences_deleted", result.PreferencesDeleted),
		)
		return nil

	default:
		// t.auth.events carries events for several consumers; one this service
		// does not own is acknowledged without action.
		logger.Debug("ignoring an auth event this service does not consume",
			zap.String("event_type", envelope.Type))
		return nil
	}
}

// contextFor attaches the correlation identifiers of the event to the context,
// so every log line of this message can be traced back to the Auth request that
// produced it.
func (h *eventHandler) contextFor(ctx context.Context, envelope *event.IncomingEnvelope) context.Context {
	ctx = reqctx.WithRequestID(ctx, envelope.CorrelationID)
	ctx = reqctx.WithTraceparent(ctx, envelope.TraceParent)

	logger := h.logger.With(
		zap.String("event_id", envelope.ID),
		zap.String("event_type", envelope.Type),
	)
	if envelope.CorrelationID != "" {
		logger = logger.With(zap.String("correlation_id", envelope.CorrelationID))
	}
	return logging.WithContext(ctx, logger)
}

// deadLetter parks the message and records why. The payload itself is not
// logged: it identifies a user.
func (h *eventHandler) deadLetter(ctx context.Context, message *sarama.ConsumerMessage, cause error, attempts int, permanent bool) {
	reason := apperr.From(cause).Code
	dlqTopic := h.topics.dlqFor(message.Topic)

	fields := []zap.Field{
		zap.String("topic", message.Topic),
		zap.String("dlq_topic", dlqTopic),
		zap.Int32("partition", message.Partition),
		zap.Int64("offset", message.Offset),
		zap.Int("attempts", attempts),
		zap.Bool("permanent", permanent),
		zap.String("reason", reason),
		zap.Error(cause),
	}

	// A detached context: the DLQ write must still happen while the session is
	// shutting down.
	dlqCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()

	if err := h.dlq.PublishDeadLetter(dlqCtx, dlqTopic, message.Key, message.Value, reason); err != nil {
		// Nothing else can be done: the message is acknowledged to keep the
		// partition moving, and the alert on this line is what an operator acts
		// on.
		h.logger.Error("consumed event could not be dead-lettered",
			append(fields, zap.NamedError("dlq_error", err))...)
		return
	}
	h.logger.Error("consumed event moved to the dead-letter topic", fields...)
}
