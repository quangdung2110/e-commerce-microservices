package port

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// ContactResolver talks to the Auth internal API (02-pipeline.md §2.3,
// 10-interfaces.md §4). Implementations own the per-lane bulkhead, client rate
// limit, breaker and the 15-minute contact cache. Errors:
//   - errors.ErrNoRecipient (404) wrapped as Permanent(no_recipient)
//   - errors.ErrContentExpired (410) wrapped as Permanent(content_expired)
//   - errors.ErrBreakerOpen wrapped as Retryable
//   - other transport/5xx errors wrapped as Retryable.
type ContactResolver interface {
	// Contact resolves email + email_verified + locale by user id (cached).
	Contact(ctx context.Context, lane entity.Lane, userID uuid.UUID) (*entity.Contact, error)
	// Content resolves an Auth delivery (never cached: contains a secret).
	Content(ctx context.Context, lane entity.Lane, authDeliveryID uuid.UUID) (*entity.AuthContent, error)
	// ForgetContact drops the cache entry (user.deleted).
	ForgetContact(ctx context.Context, userID uuid.UUID) error
}

// EmailSender is one provider adapter (D-09: smtp for Mailpit, ses). Send
// returns errors classified with errors.NewRetryable / errors.NewPermanent.
type EmailSender interface {
	Provider() entity.Provider
	Send(ctx context.Context, msg *entity.EmailMessage) (*entity.SendResult, error)
}

// Renderer turns template_data into email or in-app content
// (12-render.md). No I/O; errors are permanent (render_failed).
type Renderer interface {
	RenderEmail(templateID, locale string, data json.RawMessage, opts RenderOptions) (*entity.EmailContent, error)
	RenderInApp(templateID, locale string, data json.RawMessage, opts RenderOptions) (*entity.InAppContent, error)
	// Version is the 12-char content hash of the template directory.
	Version(templateID string) string
}

// RenderOptions carries per-delivery values the layout needs.
type RenderOptions struct {
	DeliveryID uuid.UUID
	Lane       entity.Lane
	Timezone   string
	// UnsubscribeURL is set for bulk (List-Unsubscribe).
	UnsubscribeURL string
}

// RateLimiter is the distributed token bucket per lane×channel
// (06-backpressure.md §4). Acquire blocks up to wait and returns
// errors.ErrRateLimitWait when no token arrived; it fails closed to a local
// safe rate when Redis is unavailable.
type RateLimiter interface {
	Acquire(ctx context.Context, lane entity.Lane, channel entity.Channel, wait time.Duration) error
	// Available reports tokens left (metric); best effort.
	Available(ctx context.Context, lane entity.Lane, channel entity.Channel) (float64, error)
}

// DailyQuota is the account-level 24 h send counter (06 §4.1).
type DailyQuota interface {
	// Increment adds one send for provider on the given UTC date and returns
	// the new count.
	Increment(ctx context.Context, provider entity.Provider, day time.Time) (int64, error)
	Get(ctx context.Context, provider entity.Provider, day time.Time) (int64, error)
}

// FrequencyCap counts bulk sends per user per local day at Decide
// (08-decide.md §6). Increment returns the count after increment; on Redis
// failure it returns ok=false so Decide fails open and raises the
// degraded metric.
type FrequencyCap interface {
	Increment(ctx context.Context, userID uuid.UUID, localDate string) (count int64, ok bool, err error)
	Forget(ctx context.Context, userID uuid.UUID) error
}

// UnreadCounter is the Redis inbox:unread:{user_id} counter; Get returns
// errors.ErrCacheMiss when the key is absent so the caller recomputes.
type UnreadCounter interface {
	Get(ctx context.Context, userID uuid.UUID) (int64, error)
	Set(ctx context.Context, userID uuid.UUID, count int64) error
	Add(ctx context.Context, userID uuid.UUID, delta int64) error
	Delete(ctx context.Context, userID uuid.UUID) error
}

// RetryBudget tracks retry vs first-attempt sends per lane over a 1-minute
// window (06 §6.1).
type RetryBudget interface {
	Record(ctx context.Context, lane entity.Lane, isRetry bool) error
	// Ratio returns retries / total in the current window (0 when unknown).
	Ratio(ctx context.Context, lane entity.Lane) (float64, error)
}

// CircuitBreaker guards one dependency (06 §5). Actions per lane are decided
// by the caller; the breaker itself is per provider.
type CircuitBreaker interface {
	// Allow reports whether a call may proceed (closed, or half-open probe).
	Allow() bool
	Success()
	Failure()
	// State is 0 closed, 1 half-open, 2 open (metric circuit_breaker_state).
	State() int
}

// LaneControl exposes the pause switches and replay mode to workers and
// consumers; implementations poll ops_control.
type LaneControl interface {
	LaneState(ctx context.Context, lane entity.Lane) (entity.LaneState, error)
	SetLaneState(ctx context.Context, state entity.LaneState) error
	ReplayMode(ctx context.Context) (entity.ReplayMode, error)
	SetReplayMode(ctx context.Context, mode entity.ReplayMode, updatedBy string) error
}

// DeadLetterPublisher parks a Kafka message that cannot be processed.
type DeadLetterPublisher interface {
	PublishDeadLetter(ctx context.Context, topic string, key, value []byte, headers map[string]string, reason string) error
}

// EventPublisher publishes outbox rows when outbox.publisher = poller.
type EventPublisher interface {
	Publish(ctx context.Context, topic string, key string, ev *entity.OutboxEvent) error
}

// Wake lets the consumer nudge deliver workers of a lane (LISTEN/NOTIFY).
type Wake interface {
	// Wait blocks until a notification for lane arrives, timeout elapses or
	// ctx is cancelled; returns true when woken by a notification.
	Wait(ctx context.Context, lane entity.Lane, timeout time.Duration) bool
}
