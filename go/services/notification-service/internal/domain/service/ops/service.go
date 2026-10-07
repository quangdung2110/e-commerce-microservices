// Package ops implements the operator switches of 16-operations.md §2: lane
// pause/resume, redrive (one delivery or a DLQ batch), manual suppression and
// replay mode. Every call reaches this package through the audited,
// idempotent internal API; the service itself only validates and applies.
package ops

import (
	"context"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

const (
	// MaxReasonLength mirrors maxLength 512 of every reason field.
	MaxReasonLength = 512
	// redriveBatchSize is how many FAILED rows one RedriveBatch call moves; the
	// background loop repeats until a batch comes back short.
	redriveBatchSize = 1000
	// redriveMaxDuration bounds the background operation so a stuck query
	// cannot keep the goroutine forever.
	redriveMaxDuration = 30 * time.Minute
	// SuppressionSourceOps marks rows written by an operator.
	SuppressionSourceOps = "ops"
)

var (
	reasonCodePattern = regexp.MustCompile(`^[a-z][a-z0-9_]{2,63}$`)
	// RE2 has no lookahead; the empty "P"/"PT" cases are rejected by
	// catalog.ParseISODuration right after the pattern check.
	olderThanPattern = regexp.MustCompile(`^P(\d+D)?(T(\d+H)?(\d+M)?)?$`)
	ttlPattern       = regexp.MustCompile(`^PT\d+H$`)
)

// Service applies the ops switches.
type Service struct {
	deliveries   port.DeliveryRepository
	suppressions port.SuppressionRepository
	lanes        port.LaneControl
	contacts     port.ContactResolver
	clock        clock.Clock
	logger       *zap.Logger
	replayTTL    time.Duration
	// background is the parent context of DLQ redrive goroutines; cancelled
	// at shutdown.
	background context.Context
}

// Config holds the few knobs the service needs.
type Config struct {
	// ReplayModeDefaultTTL is cfg.Extensions.ReplayModeDefaultTTL.
	ReplayModeDefaultTTL time.Duration
}

// New wires the service. background is the process context used for the
// asynchronous DLQ redrive; nil means context.Background().
func New(deliveries port.DeliveryRepository, suppressions port.SuppressionRepository, lanes port.LaneControl,
	contacts port.ContactResolver, clk clock.Clock, logger *zap.Logger, cfg Config, background context.Context) *Service {
	if logger == nil {
		logger = zap.NewNop()
	}
	if background == nil {
		background = context.Background()
	}
	ttl := cfg.ReplayModeDefaultTTL
	if ttl <= 0 {
		ttl = 6 * time.Hour
	}
	return &Service{
		deliveries: deliveries, suppressions: suppressions, lanes: lanes, contacts: contacts,
		clock: clk, logger: logger, replayTTL: ttl, background: background,
	}
}

// ---- lanes -------------------------------------------------------------

// LaneStateInput is the LaneStateUpdate body plus the path lane.
type LaneStateInput struct {
	Lane    string
	State   string
	Channel string // empty = both channels
	Reason  string
}

// SetLaneState pauses or resumes a lane, or one channel of it, and returns
// the resulting state (16-operations.md §2).
func (s *Service) SetLaneState(ctx context.Context, in LaneStateInput, principal string) (entity.LaneState, error) {
	var fields []apperr.Field
	lane, err := entity.ParseLane(in.Lane)
	if err != nil {
		fields = append(fields, apperr.Field{Field: "lane", Message: "must be one of critical, transactional, bulk"})
	}
	var state entity.LaneChannelState
	switch entity.LaneChannelState(in.State) {
	case entity.LaneActive, entity.LanePaused:
		state = entity.LaneChannelState(in.State)
	default:
		fields = append(fields, apperr.Field{Field: "state", Message: "must be active or paused"})
	}
	var channel *entity.Channel
	if in.Channel != "" {
		ch, err := entity.ParseChannel(in.Channel)
		if err != nil {
			fields = append(fields, apperr.Field{Field: "channel", Message: "must be email or in_app"})
		} else {
			channel = &ch
		}
	}
	if len(in.Reason) > MaxReasonLength {
		fields = append(fields, apperr.Field{Field: "reason", Message: "must be at most 512 characters"})
	}
	if len(fields) > 0 {
		return entity.LaneState{}, apperr.New(apperr.CodeValidationFailed, "invalid lane state update").WithFields(fields...)
	}

	now := s.clock.Now()
	current, err := s.lanes.LaneState(ctx, lane)
	if err != nil {
		if !apperr.Is(err, apperr.ErrNotFound) {
			return entity.LaneState{}, err
		}
		current = entity.DefaultLaneState(lane, now)
	}
	if current.Email == "" {
		current.Email = entity.LaneActive
	}
	if current.InApp == "" {
		current.InApp = entity.LaneActive
	}
	next := current
	next.Lane = lane
	switch {
	case channel == nil:
		next.Email, next.InApp = state, state
	case *channel == entity.ChannelEmail:
		next.Email = state
	default:
		next.InApp = state
	}
	next.UpdatedAt = now
	if principal != "" {
		p := principal
		next.UpdatedBy = &p
	} else {
		next.UpdatedBy = nil
	}
	if reason := strings.TrimSpace(in.Reason); reason != "" {
		next.Reason = &reason
	} else {
		next.Reason = nil
	}
	if err := s.lanes.SetLaneState(ctx, next); err != nil {
		return entity.LaneState{}, err
	}
	s.logger.Info("lane state changed",
		zap.String("lane", string(lane)),
		zap.String("email", string(next.Email)),
		zap.String("in_app", string(next.InApp)),
		zap.String("principal", principal),
	)
	return next, nil
}

// GetLaneState returns the current switch state; an unset lane is active.
func (s *Service) GetLaneState(ctx context.Context, laneStr string) (entity.LaneState, error) {
	lane, err := entity.ParseLane(laneStr)
	if err != nil {
		return entity.LaneState{}, apperr.New(apperr.CodeValidationFailed, "invalid lane").
			WithFields(apperr.Field{Field: "lane", Message: "must be one of critical, transactional, bulk"})
	}
	state, err := s.lanes.LaneState(ctx, lane)
	if err != nil {
		if apperr.Is(err, apperr.ErrNotFound) {
			return entity.DefaultLaneState(lane, s.clock.Now()), nil
		}
		return entity.LaneState{}, err
	}
	if state.Lane == "" {
		state.Lane = lane
	}
	if state.Email == "" {
		state.Email = entity.LaneActive
	}
	if state.InApp == "" {
		state.InApp = entity.LaneActive
	}
	return state, nil
}

// ---- redrive -----------------------------------------------------------

// RedriveDelivery moves one FAILED/EXPIRED delivery back to PENDING. The
// repository answers ErrStateConflict for other statuses and ErrValidation
// when the row is past expires_at and no new expiry was given.
func (s *Service) RedriveDelivery(ctx context.Context, id uuid.UUID, expiresAt *time.Time, reason string) (*entity.Delivery, error) {
	if len(reason) > MaxReasonLength {
		return nil, apperr.New(apperr.CodeValidationFailed, "invalid redrive request").
			WithFields(apperr.Field{Field: "reason", Message: "must be at most 512 characters"})
	}
	now := s.clock.Now()
	if expiresAt != nil && !expiresAt.After(now) {
		return nil, apperr.New(apperr.CodeValidationFailed, "invalid redrive request").
			WithFields(apperr.Field{Field: "expires_at", Message: "must be in the future"})
	}
	d, err := s.deliveries.Redrive(ctx, id, expiresAt, now)
	if err != nil {
		switch {
		case apperr.Is(err, apperr.ErrNotFound):
			return nil, apperr.ErrNotFound
		case apperr.Is(err, apperr.ErrStateConflict):
			return nil, apperr.New(apperr.CodeStateConflict, "Only FAILED or EXPIRED deliveries can be redriven.")
		case apperr.Is(err, apperr.ErrValidation):
			return nil, apperr.New(apperr.CodeValidationFailed, "The delivery is past its expires_at; provide a new expires_at.").
				WithFields(apperr.Field{Field: "expires_at", Message: "is required for an expired delivery"})
		}
		return nil, err
	}
	if d == nil {
		return nil, apperr.ErrNotFound
	}
	s.logger.Info("delivery redriven", zap.String("delivery_id", id.String()), zap.String("lane", string(d.Lane)))
	return d, nil
}

// DLQRedriveInput is the DlqRedriveRequest body.
type DLQRedriveInput struct {
	Lane         string
	ReasonCode   string
	FailedAfter  *time.Time
	FailedBefore *time.Time
	ExpiresAt    *time.Time
	Reason       string
}

// DLQRedriveAccepted is the 202 body.
type DLQRedriveAccepted struct {
	OperationID uuid.UUID
	Matched     int64
}

// RedriveDLQ counts the FAILED rows matching the filter, starts the batch
// redrive in the background and returns immediately (contract: 202 +
// operation_id; progress through logs and metrics). Producer-contract
// failures are excluded by the repository itself (R-04).
func (s *Service) RedriveDLQ(ctx context.Context, in DLQRedriveInput) (DLQRedriveAccepted, error) {
	var fields []apperr.Field
	lane, err := entity.ParseLane(in.Lane)
	if err != nil {
		fields = append(fields, apperr.Field{Field: "lane", Message: "must be one of critical, transactional, bulk"})
	}
	if in.ReasonCode != "" {
		switch {
		case !reasonCodePattern.MatchString(in.ReasonCode):
			fields = append(fields, apperr.Field{Field: "reason_code", Message: "must match ^[a-z][a-z0-9_]{2,63}$"})
		case entity.ProducerContractReasons[in.ReasonCode]:
			fields = append(fields, apperr.Field{Field: "reason_code", Message: "producer contract failures cannot be redriven"})
		}
	}
	if in.FailedAfter != nil && in.FailedBefore != nil && !in.FailedBefore.After(*in.FailedAfter) {
		fields = append(fields, apperr.Field{Field: "failed_before", Message: "must be after failed_after"})
	}
	now := s.clock.Now()
	if in.ExpiresAt != nil && !in.ExpiresAt.After(now) {
		fields = append(fields, apperr.Field{Field: "expires_at", Message: "must be in the future"})
	}
	if strings.TrimSpace(in.Reason) == "" {
		fields = append(fields, apperr.Field{Field: "reason", Message: "is required"})
	} else if len(in.Reason) > MaxReasonLength {
		fields = append(fields, apperr.Field{Field: "reason", Message: "must be at most 512 characters"})
	}
	if len(fields) > 0 {
		return DLQRedriveAccepted{}, apperr.New(apperr.CodeValidationFailed, "invalid DLQ redrive request").WithFields(fields...)
	}

	filter := entity.RedriveFilter{
		Lane:         lane,
		ReasonCode:   in.ReasonCode,
		FailedAfter:  in.FailedAfter,
		FailedBefore: in.FailedBefore,
		ExpiresAt:    in.ExpiresAt,
		Limit:        redriveBatchSize,
	}
	matched, err := s.deliveries.CountRedrivable(ctx, filter)
	if err != nil {
		return DLQRedriveAccepted{}, err
	}
	operationID := uuid.New()
	logger := s.logger.With(
		zap.String("operation_id", operationID.String()),
		zap.String("lane", string(lane)),
		zap.String("reason_code", in.ReasonCode),
		zap.String("request_id", requestIDFrom(ctx)),
	)
	logger.Info("dlq redrive started", zap.Int64("matched", matched))
	if matched > 0 {
		go s.runRedrive(logger, filter, matched)
	} else {
		logger.Info("dlq redrive finished", zap.Int64("redriven", 0))
	}
	return DLQRedriveAccepted{OperationID: operationID, Matched: matched}, nil
}

func (s *Service) runRedrive(logger *zap.Logger, filter entity.RedriveFilter, matched int64) {
	ctx, cancel := context.WithTimeout(s.background, redriveMaxDuration)
	defer cancel()
	var total int64
	// Every batch moves rows out of FAILED, so the next call sees the rest;
	// the iteration bound guards against a repository that never shrinks.
	maxBatches := int(matched/int64(filter.Limit)) + 2
	for i := 0; i < maxBatches; i++ {
		n, err := s.deliveries.RedriveBatch(ctx, filter, s.clock.Now())
		if err != nil {
			logger.Error("dlq redrive aborted", zap.Int64("redriven", total), zap.Error(err))
			return
		}
		total += n
		if n < int64(filter.Limit) {
			break
		}
	}
	logger.Info("dlq redrive finished", zap.Int64("redriven", total), zap.Int64("matched", matched))
}

// ---- suppressions ------------------------------------------------------

// AddManualSuppression writes a MANUAL row for a user. The address is never
// supplied by the caller: the hash is resolved through Auth so an operator
// cannot suppress an arbitrary address (11-privacy.md §5).
func (s *Service) AddManualSuppression(ctx context.Context, userID uuid.UUID, until *time.Time, reason, principal string) (*entity.Suppression, error) {
	var fields []apperr.Field
	if userID == uuid.Nil {
		fields = append(fields, apperr.Field{Field: "user_id", Message: "must be a UUID"})
	}
	if strings.TrimSpace(reason) == "" {
		fields = append(fields, apperr.Field{Field: "reason", Message: "is required"})
	} else if len(reason) > MaxReasonLength {
		fields = append(fields, apperr.Field{Field: "reason", Message: "must be at most 512 characters"})
	}
	now := s.clock.Now()
	if until != nil && !until.After(now) {
		fields = append(fields, apperr.Field{Field: "until", Message: "must be in the future"})
	}
	if len(fields) > 0 {
		return nil, apperr.New(apperr.CodeValidationFailed, "invalid suppression request").WithFields(fields...)
	}

	contact, err := s.contacts.Contact(ctx, entity.LaneTransactional, userID)
	if err != nil {
		if apperr.Is(err, apperr.ErrNoRecipient) {
			return nil, apperr.New(apperr.CodeNotFound, "No contact exists for this user.")
		}
		if code, ok := apperr.PermanentCode(err); ok && code == entity.ReasonNoRecipient {
			return nil, apperr.New(apperr.CodeNotFound, "No contact exists for this user.")
		}
		return nil, apperr.Wrap(apperr.CodeDependencyUnavailable, "contact could not be resolved", err).AsRetryable(0)
	}
	if contact == nil || contact.Email.IsZero() {
		return nil, apperr.New(apperr.CodeNotFound, "No contact exists for this user.")
	}

	uid := userID
	row := &entity.Suppression{
		ID:        uuid.New(),
		UserID:    &uid,
		EmailHash: contact.Email.Hash(),
		Reason:    entity.SuppressionManual,
		Source:    SuppressionSourceOps,
		Until:     until,
		CreatedAt: now,
	}
	if err := s.suppressions.Insert(ctx, row); err != nil {
		return nil, err
	}
	s.logger.Info("manual suppression added",
		zap.String("suppression_id", row.ID.String()),
		zap.String("user_id", userID.String()),
		zap.String("principal", principal),
	)
	return row, nil
}

// RemoveSuppression deletes a MANUAL or SOFT_BOUNCE row; HARD_BOUNCE and
// COMPLAINT are refused with 409 (D-14).
func (s *Service) RemoveSuppression(ctx context.Context, id uuid.UUID) error {
	row, err := s.suppressions.GetByID(ctx, id)
	if err != nil {
		if apperr.Is(err, apperr.ErrNotFound) {
			return apperr.ErrNotFound
		}
		return err
	}
	if row == nil {
		return apperr.ErrNotFound
	}
	if !row.Reason.Removable() {
		return apperr.New(apperr.CodeStateConflict, "Suppressions with reason "+string(row.Reason)+" cannot be removed through the API.")
	}
	if err := s.suppressions.Delete(ctx, id); err != nil {
		return err
	}
	s.logger.Info("suppression removed", zap.String("suppression_id", id.String()), zap.String("reason", string(row.Reason)))
	return nil
}

// ---- replay mode -------------------------------------------------------

// ReplayModeInput is the ReplayMode request body (ISO-8601 durations).
type ReplayModeInput struct {
	Enabled               bool
	IgnoreEventsOlderThan string
	TTL                   string
}

// SetReplayMode switches replay mode on (with a threshold and an expiry) or
// off (04-idempotency.md §5, R-14).
func (s *Service) SetReplayMode(ctx context.Context, in ReplayModeInput, principal string) (entity.ReplayMode, error) {
	now := s.clock.Now()
	if !in.Enabled {
		mode := entity.ReplayMode{Enabled: false}
		if err := s.lanes.SetReplayMode(ctx, mode, principal); err != nil {
			return entity.ReplayMode{}, err
		}
		s.logger.Info("replay mode disabled", zap.String("principal", principal))
		return mode, nil
	}

	var fields []apperr.Field
	var olderThan time.Duration
	if in.IgnoreEventsOlderThan == "" {
		fields = append(fields, apperr.Field{Field: "ignore_events_older_than", Message: "is required when enabled"})
	} else if !olderThanPattern.MatchString(in.IgnoreEventsOlderThan) {
		fields = append(fields, apperr.Field{Field: "ignore_events_older_than", Message: "must be an ISO-8601 duration such as P7D or PT12H"})
	} else {
		d, err := catalog.ParseISODuration(in.IgnoreEventsOlderThan)
		if err != nil || d <= 0 {
			fields = append(fields, apperr.Field{Field: "ignore_events_older_than", Message: "must be a positive ISO-8601 duration"})
		}
		olderThan = d
	}
	ttl := s.replayTTL
	if in.TTL != "" {
		if !ttlPattern.MatchString(in.TTL) {
			fields = append(fields, apperr.Field{Field: "ttl", Message: "must match ^PT\\d+H$"})
		} else {
			d, err := catalog.ParseISODuration(in.TTL)
			if err != nil || d <= 0 {
				fields = append(fields, apperr.Field{Field: "ttl", Message: "must be a positive number of hours"})
			}
			ttl = d
		}
	}
	if len(fields) > 0 {
		return entity.ReplayMode{}, apperr.New(apperr.CodeValidationFailed, "invalid replay mode request").WithFields(fields...)
	}

	expires := now.Add(ttl)
	mode := entity.ReplayMode{Enabled: true, IgnoreEventsOlderThan: olderThan, ExpiresAt: &expires}
	if err := s.lanes.SetReplayMode(ctx, mode, principal); err != nil {
		return entity.ReplayMode{}, err
	}
	s.logger.Info("replay mode enabled",
		zap.Duration("ignore_events_older_than", olderThan),
		zap.Time("expires_at", expires),
		zap.String("principal", principal),
	)
	return mode, nil
}

func requestIDFrom(ctx context.Context) string {
	return reqctx.RequestID(ctx)
}
