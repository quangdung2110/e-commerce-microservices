// Package inbox is the read side of the in-app channel: cursor listing,
// unread badge, read/unread, archive (09-data-model.md §7, 10-interfaces.md
// §5). It only ever sees the caller's own user id; ownership is enforced by
// the repository queries, and a foreign item is 404, never 403.
package inbox

import (
	"context"
	"encoding/base64"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

const (
	// DefaultLimit and MaxLimit follow the inbox OpenAPI (limit default 20,
	// maximum 50).
	DefaultLimit = 20
	MaxLimit     = 50
)

// Page is one page of a user's inbox with the opaque cursor already encoded.
type Page struct {
	Items      []entity.InboxItem
	NextCursor *string
}

// Unread is the badge value and the instant it was computed.
type Unread struct {
	Count int64
	AsOf  time.Time
}

// Service implements the inbox use cases on the repository and the Redis
// counter.
type Service struct {
	items   port.InboxRepository
	unread  port.UnreadCounter
	clock   clock.Clock
	metrics *telemetry.Metrics
}

// New wires the service. metrics may be nil in tests.
func New(items port.InboxRepository, unread port.UnreadCounter, clk clock.Clock, metrics *telemetry.Metrics) *Service {
	return &Service{items: items, unread: unread, clock: clk, metrics: metrics}
}

// List returns one page ordered by (created_at DESC, id). cursorStr is the
// next_cursor of the previous page or empty; limit 0 means the default.
func (s *Service) List(ctx context.Context, user uuid.UUID, cursorStr string, limit int, unreadOnly bool) (*Page, error) {
	if limit == 0 {
		limit = DefaultLimit
	}
	if limit < 1 || limit > MaxLimit {
		return nil, apperr.New(apperr.CodeValidationFailed, "invalid query parameter").
			WithFields(apperr.Field{Field: "limit", Message: "must be between 1 and 50"})
	}
	var cursor *entity.InboxCursor
	if cursorStr != "" {
		parsed, err := DecodeCursor(cursorStr)
		if err != nil {
			return nil, apperr.New(apperr.CodeValidationFailed, "invalid query parameter").
				WithFields(apperr.Field{Field: "cursor", Message: "is not a valid cursor"})
		}
		cursor = &parsed
	}

	page, err := s.items.List(ctx, user, cursor, limit, unreadOnly)
	if err != nil {
		return nil, err
	}
	out := &Page{Items: page.Items}
	if out.Items == nil {
		out.Items = []entity.InboxItem{}
	}
	if page.NextCursor != nil {
		encoded := EncodeCursor(*page.NextCursor)
		out.NextCursor = &encoded
	}
	return out, nil
}

// UnreadCount reads the Redis counter; on a miss or a Redis error it
// recomputes from the table and rewrites the counter (09-data-model.md §7).
func (s *Service) UnreadCount(ctx context.Context, user uuid.UUID) (Unread, error) {
	now := s.clock.Now()
	count, err := s.unread.Get(ctx, user)
	if err == nil && count >= 0 {
		return Unread{Count: count, AsOf: now}, nil
	}
	if err != nil && !apperr.Is(err, apperr.ErrCacheMiss) {
		logging.FromContext(ctx).Warn("unread counter unavailable, recomputing", zap.Error(err))
	}
	count, err = s.items.CountUnread(ctx, user)
	if err != nil {
		return Unread{}, err
	}
	if s.metrics != nil {
		s.metrics.InboxUnreadRecompute.Inc()
	}
	if err := s.unread.Set(ctx, user, count); err != nil {
		logging.FromContext(ctx).Warn("unread counter not written", zap.Error(err))
	}
	return Unread{Count: count, AsOf: now}, nil
}

// SetRead marks an item read or unread. Idempotent: an unchanged item is
// returned as is and the counter is only adjusted when the state flipped.
func (s *Service) SetRead(ctx context.Context, user, id uuid.UUID, read bool) (*entity.InboxItem, error) {
	item, changed, err := s.items.SetRead(ctx, user, id, read, s.clock.Now())
	if err != nil {
		if apperr.Is(err, apperr.ErrNotFound) {
			return nil, apperr.ErrNotFound
		}
		return nil, err
	}
	if item == nil {
		return nil, apperr.ErrNotFound
	}
	if changed {
		delta := int64(1)
		if read {
			delta = -1
		}
		s.adjust(ctx, user, delta)
	}
	return item, nil
}

// Archive hides an item; a second call is a no-op success. The counter is
// decremented only when the archived item was still unread.
func (s *Service) Archive(ctx context.Context, user, id uuid.UUID) error {
	wasUnread, changed, err := s.items.Archive(ctx, user, id, s.clock.Now())
	if err != nil {
		if apperr.Is(err, apperr.ErrNotFound) {
			return apperr.ErrNotFound
		}
		return err
	}
	if changed && wasUnread {
		s.adjust(ctx, user, -1)
	}
	return nil
}

// MarkAllRead sets read_at on every unread, non-archived item and resets the
// counter to zero (the final state does not depend on how many rows moved).
func (s *Service) MarkAllRead(ctx context.Context, user uuid.UUID) error {
	if _, err := s.items.MarkAllRead(ctx, user, s.clock.Now()); err != nil {
		return err
	}
	if err := s.unread.Set(ctx, user, 0); err != nil {
		logging.FromContext(ctx).Warn("unread counter not reset", zap.Error(err))
	}
	return nil
}

func (s *Service) adjust(ctx context.Context, user uuid.UUID, delta int64) {
	if err := s.unread.Add(ctx, user, delta); err != nil {
		// The badge self-heals on the next cache miss; do not fail the call.
		logging.FromContext(ctx).Warn("unread counter not adjusted", zap.Int64("delta", delta), zap.Error(err))
	}
}

// EncodeCursor renders the opaque cursor: base64url of
// "<RFC3339Nano created_at>|<uuid>".
func EncodeCursor(c entity.InboxCursor) string {
	raw := c.CreatedAt.UTC().Format(time.RFC3339Nano) + "|" + c.ID.String()
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

// DecodeCursor parses a cursor produced by EncodeCursor.
func DecodeCursor(s string) (entity.InboxCursor, error) {
	if len(s) > 256 {
		return entity.InboxCursor{}, apperr.ErrValidation
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
	if err != nil {
		return entity.InboxCursor{}, err
	}
	parts := strings.SplitN(string(raw), "|", 2)
	if len(parts) != 2 {
		return entity.InboxCursor{}, apperr.ErrValidation
	}
	at, err := time.Parse(time.RFC3339Nano, parts[0])
	if err != nil {
		return entity.InboxCursor{}, err
	}
	id, err := uuid.Parse(parts[1])
	if err != nil {
		return entity.InboxCursor{}, err
	}
	return entity.InboxCursor{CreatedAt: at, ID: id}, nil
}
