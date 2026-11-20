// Package relay carries CART_ABANDONED entries from the Redis outbox streams
// to Kafka (WF-CART-006).
package relay

import (
	"context"
	"encoding/json"
	"math/rand"
	"strconv"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/observability"
)

// Config tunes the relay loop.
type Config struct {
	// IdleInterval is the pause after a pass that moved nothing.
	IdleInterval time.Duration
	// BatchSize caps how many entries one bucket yields per pass.
	BatchSize int
	// ClaimMinIdle is how long an entry must sit unacked before another relay
	// consumer may claim it.
	ClaimMinIdle time.Duration
	// MaxPublishAttempts bounds the retry burst for one entry within a pass.
	MaxPublishAttempts int
	// PublishBackoff is the base delay of the exponential backoff.
	PublishBackoff time.Duration
	// OperationTimeout bounds a single Redis or Kafka call.
	OperationTimeout time.Duration
	// DataSchema is the dataschema URI stamped on every CloudEvent.
	DataSchema string
}

// Worker relays one bucket stream at a time, round-robin.
//
// A single XREADGROUP cannot span the 64 streams because they sit in different
// Redis Cluster slots, and each stream must have exactly one active reader so
// that two consumers in the same group cannot publish adjacent entries to
// Kafka out of order. Scaling therefore splits bucket ownership across
// deployments rather than adding readers to a stream.
type Worker struct {
	outbox    port.OutboxStore
	publisher port.EventPublisher
	cfg       Config
	metrics   *observability.Metrics
	logger    *zap.Logger
}

// New builds the relay worker.
func New(outbox port.OutboxStore, publisher port.EventPublisher, cfg Config, metrics *observability.Metrics, logger *zap.Logger) *Worker {
	if cfg.IdleInterval <= 0 {
		cfg.IdleInterval = 2 * time.Second
	}
	if cfg.BatchSize <= 0 {
		cfg.BatchSize = 64
	}
	if cfg.ClaimMinIdle <= 0 {
		cfg.ClaimMinIdle = 30 * time.Second
	}
	if cfg.MaxPublishAttempts <= 0 {
		cfg.MaxPublishAttempts = 3
	}
	if cfg.PublishBackoff <= 0 {
		cfg.PublishBackoff = 200 * time.Millisecond
	}
	if cfg.OperationTimeout <= 0 {
		cfg.OperationTimeout = 10 * time.Second
	}
	return &Worker{outbox: outbox, publisher: publisher, cfg: cfg, metrics: metrics, logger: logger}
}

// Run relays until the context is cancelled.
func (w *Worker) Run(ctx context.Context) error {
	w.logger.Info("outbox relay started",
		zap.Int("buckets", domain.BucketCount),
		zap.Int("batch_size", w.cfg.BatchSize),
	)
	defer w.logger.Info("outbox relay stopped")

	for {
		moved := w.pass(ctx)
		if ctx.Err() != nil {
			return nil
		}
		if moved > 0 {
			// Keep draining while there is work rather than sleeping between
			// bursts of abandoned carts.
			continue
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(w.cfg.IdleInterval):
		}
	}
}

func (w *Worker) pass(ctx context.Context) int {
	relayed := 0
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		if ctx.Err() != nil {
			return relayed
		}
		relayed += w.drainBucket(ctx, bucket)
	}
	return relayed
}

func (w *Worker) drainBucket(ctx context.Context, bucket int) int {
	opCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	exists, err := w.outbox.StreamExists(opCtx, bucket)
	cancel()
	if err != nil {
		w.metrics.RelayError(bucket, "stream_exists")
		w.logger.Warn("failed to probe outbox stream", zap.Int("bucket", bucket), zap.Error(err))
		return 0
	}
	if !exists {
		// Streams are created lazily by the first XADD. Nothing to relay and
		// no group to create yet.
		return 0
	}

	opCtx, cancel = context.WithTimeout(ctx, w.cfg.OperationTimeout)
	err = w.outbox.EnsureGroup(opCtx, bucket)
	cancel()
	if err != nil {
		w.metrics.RelayError(bucket, "ensure_group")
		w.logger.Warn("failed to create relay group", zap.Int("bucket", bucket), zap.Error(err))
		return 0
	}

	// Pending entries come first: an entry a crashed relay read but never
	// acked is older than anything XREADGROUP would hand back, and relaying it
	// late would reorder the stream.
	relayed := w.relay(ctx, bucket, w.claim)

	// Claim only returns entries idle for ClaimMinIdle, so an entry that just
	// failed is still pending and invisible to it. Reading now would publish
	// newer entries ahead of it. Wait until nothing is pending in the bucket.
	opCtx, cancel = context.WithTimeout(ctx, w.cfg.OperationTimeout)
	pending, err := w.outbox.PendingCount(opCtx, bucket)
	cancel()
	if err != nil {
		w.metrics.RelayError(bucket, "pending_count")
		w.logger.Warn("failed to count pending outbox entries", zap.Int("bucket", bucket), zap.Error(err))
		return relayed
	}
	if pending > 0 {
		return relayed
	}

	relayed += w.relay(ctx, bucket, w.read)
	return relayed
}

type fetchFunc func(ctx context.Context, bucket int) ([]domain.OutboxRecord, error)

func (w *Worker) relay(ctx context.Context, bucket int, fetch fetchFunc) int {
	opCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	records, err := fetch(opCtx, bucket)
	cancel()
	if err != nil {
		w.metrics.RelayError(bucket, "fetch")
		w.logger.Warn("failed to fetch outbox entries", zap.Int("bucket", bucket), zap.Error(err))
		return 0
	}

	relayed := 0
	for _, record := range records {
		if ctx.Err() != nil {
			return relayed
		}
		if w.relayOne(ctx, record) {
			relayed++
			continue
		}
		// Stop at the first entry that could not be relayed. Skipping ahead
		// would publish a later abandonment before an earlier one for the same
		// bucket.
		break
	}
	return relayed
}

func (w *Worker) claim(ctx context.Context, bucket int) ([]domain.OutboxRecord, error) {
	return w.outbox.Claim(ctx, bucket, w.cfg.BatchSize, w.cfg.ClaimMinIdle)
}

func (w *Worker) read(ctx context.Context, bucket int) ([]domain.OutboxRecord, error) {
	return w.outbox.Read(ctx, bucket, w.cfg.BatchSize)
}

// relayOne publishes a single entry and acks it. It reports whether the entry
// left the stream, either published or dead-lettered.
func (w *Worker) relayOne(ctx context.Context, record domain.OutboxRecord) bool {
	outboxKey := w.outbox.OutboxKey(record.Bucket)

	if record.Payload.CartID == "" || record.EventType != domain.EventTypeCartAbandoned {
		// A malformed or unknown entry can never succeed, so retrying it would
		// block its bucket forever. Route it to the DLQ and ack it.
		w.deadLetter(ctx, record, outboxKey)
		return true
	}

	event := record.ToCloudEvent(outboxKey, w.cfg.DataSchema)
	headers := map[string]string{
		"outbox_bucket": strconv.Itoa(record.Bucket),
		"stream_id":     record.StreamID,
		"cart_id":       record.Payload.CartID,
	}

	if !w.publish(ctx, event, headers, record) {
		return false
	}

	// The ack happens only after Kafka acknowledged the produce. A crash in
	// between replays the entry, which Notification deduplicates on the
	// CloudEvent id.
	ackCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	defer cancel()
	if err := w.outbox.Ack(ackCtx, record.Bucket, record.StreamID); err != nil {
		w.metrics.RelayError(record.Bucket, "ack")
		w.logger.Error("published event but failed to ack the outbox entry",
			zap.Int("bucket", record.Bucket),
			zap.String("stream_id", record.StreamID),
			zap.String("event_id", event.ID),
			zap.Error(err),
		)
		return false
	}

	w.metrics.RelayPublished(record.Bucket)
	w.logger.Info("relayed cart abandoned event",
		zap.Int("bucket", record.Bucket),
		zap.String("stream_id", record.StreamID),
		zap.String("event_id", event.ID),
		zap.String("cart_id", record.Payload.CartID),
	)
	return true
}

// publish retries the produce with exponential backoff and jitter. It keeps
// the CloudEvent id fixed across attempts, so a duplicate that reaches Kafka
// is deduplicated downstream rather than delivered twice.
func (w *Worker) publish(ctx context.Context, event domain.CloudEvent, headers map[string]string, record domain.OutboxRecord) bool {
	for attempt := 1; attempt <= w.cfg.MaxPublishAttempts; attempt++ {
		publishCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
		err := w.publisher.Publish(publishCtx, event, headers)
		cancel()
		if err == nil {
			return true
		}

		w.metrics.RelayPublishRetry(record.Bucket)
		w.logger.Warn("failed to publish cart abandoned event",
			zap.Int("bucket", record.Bucket),
			zap.String("stream_id", record.StreamID),
			zap.String("event_id", event.ID),
			zap.Int("attempt", attempt),
			zap.Error(err),
		)

		if attempt == w.cfg.MaxPublishAttempts || ctx.Err() != nil {
			break
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(backoff(w.cfg.PublishBackoff, attempt)):
		}
	}

	// The entry stays pending and unacked. The next pass reclaims it through
	// XAUTOCLAIM, so a Kafka outage delays events but never drops them.
	w.metrics.RelayError(record.Bucket, "publish")
	return false
}

func (w *Worker) deadLetter(ctx context.Context, record domain.OutboxRecord, outboxKey string) {
	payload, _ := json.Marshal(map[string]any{
		"reason":        "outbox entry is malformed or carries an unknown event type",
		"outbox_key":    outboxKey,
		"stream_id":     record.StreamID,
		"outbox_bucket": record.Bucket,
		"event_type":    record.EventType,
	})
	headers := map[string]string{
		"outbox_bucket": strconv.Itoa(record.Bucket),
		"stream_id":     record.StreamID,
	}

	dlqCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	err := w.publisher.PublishDeadLetter(dlqCtx, domain.CloudEventID(outboxKey, record.StreamID), payload, headers)
	cancel()
	if err != nil {
		w.metrics.RelayError(record.Bucket, "dead_letter")
		w.logger.Error("failed to dead-letter a malformed outbox entry",
			zap.Int("bucket", record.Bucket),
			zap.String("stream_id", record.StreamID),
			zap.Error(err),
		)
		return
	}

	ackCtx, cancel := context.WithTimeout(ctx, w.cfg.OperationTimeout)
	defer cancel()
	if err := w.outbox.Ack(ackCtx, record.Bucket, record.StreamID); err != nil {
		w.metrics.RelayError(record.Bucket, "ack")
		w.logger.Error("failed to ack a dead-lettered outbox entry",
			zap.Int("bucket", record.Bucket),
			zap.String("stream_id", record.StreamID),
			zap.Error(err),
		)
		return
	}
	w.metrics.RelayDeadLettered(record.Bucket)
}

// backoff returns an exponentially growing delay with full jitter, so that a
// broker recovering from an outage is not hit by every relay at once.
func backoff(base time.Duration, attempt int) time.Duration {
	delay := base << (attempt - 1)
	if delay <= 0 {
		delay = base
	}
	return time.Duration(rand.Int63n(int64(delay)) + int64(base))
}
