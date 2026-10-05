// Package ingest is boundary 1 of the pipeline (02-pipeline.md §4): one
// normalised IngestInput becomes, inside a single Postgres transaction, an
// ingest_dedup claim, a notification_request row, a Decide run and one
// notification_delivery row per template channel. The Kafka offset is
// committed by the caller only after this returns without error.
package ingest

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/domain/service/decide"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Outcome summarises what one message became.
type Outcome struct {
	// Duplicate is true when layer A (ingest_dedup) already knew the event;
	// nothing else was written.
	Duplicate bool
	RequestID uuid.UUID
	Decision  entity.Decision
	Reason    string
	// Kept lists the channels that received a PENDING delivery.
	Kept []entity.Channel
}

// Service runs Ingest → Dedup → Decide for every source consumer.
type Service struct {
	store   port.Store
	decider *decide.Decider
	laneCtl port.LaneControl // nilable (replay mode never active)
	clock   clock.Clock
	metrics *telemetry.Metrics // nilable
	logger  *zap.Logger
}

// New wires the service. decider carries the process-wide Decide deps; its
// repositories are replaced per message with the transaction-bound ones.
func New(store port.Store, decider *decide.Decider, laneCtl port.LaneControl, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) *Service {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Service{store: store, decider: decider, laneCtl: laneCtl, clock: clk, metrics: metrics, logger: logger}
}

// Ingest processes one normalised message. An error means nothing was
// committed and the caller must retry (or dead-letter after the retry budget).
func (s *Service) Ingest(ctx context.Context, in entity.IngestInput) (Outcome, error) {
	start := s.clock.Now()
	out, err := s.ingest(ctx, in, start)
	if err != nil {
		return Outcome{}, err
	}
	s.observe(in, out, s.clock.Now().Sub(start))
	return out, nil
}

func (s *Service) ingest(ctx context.Context, in entity.IngestInput, now time.Time) (Outcome, error) {
	replay, err := s.replayMode(ctx)
	if err != nil {
		return Outcome{}, err
	}

	var out Outcome
	err = s.store.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		// Layer A: unique constraint decides, never SELECT-then-INSERT
		// (04-idempotency.md §3).
		claimed, err := tx.Dedup().ClaimIngest(ctx, in.Source, in.EventID)
		if err != nil {
			return fmt.Errorf("ingest: claim ingest dedup: %w", err)
		}
		if !claimed {
			out = Outcome{Duplicate: true}
			return nil
		}

		req := newRequest(in, now)
		if err := tx.Requests().Insert(ctx, req); err != nil {
			return fmt.Errorf("ingest: insert request: %w", err)
		}
		out.RequestID = req.ID

		// Replay mode (04-idempotency.md §5, 16-operations.md §5): events older
		// than the threshold are recorded and ignored instead of being resent.
		if replay.ActiveAt(now) && !in.EventTime.IsZero() && now.Sub(in.EventTime) > replay.IgnoreEventsOlderThan {
			req.Decision = entity.DecisionIgnored
			req.DecisionReason = strPtr(entity.ReasonReplayTooOld)
			if err := tx.Requests().UpdateDecision(ctx, req); err != nil {
				return fmt.Errorf("ingest: update decision: %w", err)
			}
			out.Decision, out.Reason = req.Decision, entity.ReasonReplayTooOld
			return nil
		}

		res, err := s.decider.WithRepos(tx.Preferences(), tx.Suppressions(), tx.Dedup()).Decide(ctx, in)
		if err != nil {
			return err
		}
		applyDecision(req, res)
		if err := tx.Requests().UpdateDecision(ctx, req); err != nil {
			return fmt.Errorf("ingest: update decision: %w", err)
		}
		out.Decision, out.Reason = res.Decision, res.Reason

		// Step 13: one delivery row per template channel — PENDING for kept,
		// SKIPPED with skip_reason for dropped, so the timeline shows why.
		for _, cd := range res.Channels {
			d := newDelivery(req, in, res, cd, now)
			if err := tx.Deliveries().Insert(ctx, d); err != nil {
				return fmt.Errorf("ingest: insert delivery %s: %w", cd.Channel, err)
			}
			if cd.Keep {
				out.Kept = append(out.Kept, cd.Channel)
			}
		}
		return nil
	})
	if err != nil {
		return Outcome{}, err
	}

	// Only critical needs the wake-up; other lanes poll (02-pipeline.md §4).
	// A failed NOTIFY is harmless: the worker's poll interval picks it up.
	if in.Lane == entity.LaneCritical && len(out.Kept) > 0 {
		if err := s.store.Maintenance().Notify(ctx, entity.LaneCritical); err != nil {
			s.logger.Warn("notify critical workers failed", zap.Error(err))
		}
	}
	return out, nil
}

func (s *Service) replayMode(ctx context.Context) (entity.ReplayMode, error) {
	if s.laneCtl == nil {
		return entity.ReplayMode{}, nil
	}
	mode, err := s.laneCtl.ReplayMode(ctx)
	if err != nil {
		return entity.ReplayMode{}, fmt.Errorf("ingest: read replay mode: %w", err)
	}
	return mode, nil
}

func (s *Service) observe(in entity.IngestInput, out Outcome, took time.Duration) {
	if s.metrics == nil {
		return
	}
	lane := string(in.Lane)
	if lane == "" {
		lane = "unknown"
	}
	decision, reason := string(out.Decision), out.Reason
	if out.Duplicate {
		decision, reason = "DUPLICATE", ""
	}
	s.metrics.IngestEventsTotal.WithLabelValues(lane, in.Source, decision, reason).Inc()
	s.metrics.IngestDuration.WithLabelValues(lane).Observe(took.Seconds())
}

// newRequest maps the input to the row. The decision is a placeholder that
// UpdateDecision overwrites inside the same transaction (the column is NOT
// NULL; a crash before the update rolls the row back anyway).
func newRequest(in entity.IngestInput, now time.Time) *entity.Request {
	req := &entity.Request{
		ID:            uuid.New(),
		Source:        in.Source,
		EventID:       in.EventID,
		EventType:     in.EventType,
		Subject:       optStr(in.Subject),
		InputKind:     in.InputKind,
		Template:      optStr(in.Template),
		DedupKey:      optStr(in.DedupKey),
		ExpiresAt:     in.ExpiresAt,
		TemplateData:  in.TemplateData,
		SourceRef:     optStr(in.SourceRef),
		CorrelationID: optStr(in.CorrelationID),
		TraceParent:   optStr(in.TraceParent),
		Decision:      entity.DecisionIgnored,
		ReceivedAt:    now,
	}
	if !in.EventTime.IsZero() {
		t := in.EventTime.UTC()
		req.EventTime = &t
	}
	if in.Lane != "" {
		lane := in.Lane
		req.Lane = &lane
	}
	if id, err := uuid.Parse(in.UserID); err == nil && id != uuid.Nil {
		req.UserID = &id
	}
	return req
}

func applyDecision(req *entity.Request, res entity.DecideResult) {
	req.Decision = res.Decision
	req.DecisionReason = optStr(res.Reason)
	if !res.EffectiveExpiresAt.IsZero() {
		t := res.EffectiveExpiresAt
		req.ExpiresAt = &t
	}
	if res.UserID != uuid.Nil {
		id := res.UserID
		req.UserID = &id
	}
}

func newDelivery(req *entity.Request, in entity.IngestInput, res entity.DecideResult, cd entity.ChannelDecision, now time.Time) *entity.Delivery {
	userID := res.UserID
	d := &entity.Delivery{
		ID:            uuid.New(),
		RequestID:     req.ID,
		UserID:        &userID,
		Subject:       optStr(in.Subject),
		Lane:          in.Lane,
		Channel:       cd.Channel,
		Template:      in.Template,
		Status:        entity.StatusPending,
		NextAttemptAt: cd.NextAttemptAt,
		DedupKey:      optStr(in.DedupKey),
		ExpiresAt:     res.EffectiveExpiresAt,
		SourceRef:     optStr(in.SourceRef),
		CreatedAt:     now,
		UpdatedAt:     now,
	}
	if !cd.Keep {
		d.Status = entity.StatusSkipped
		d.SkipReason = optStr(cd.SkipReason)
		finished := now
		d.FinishedAt = &finished
	}
	return d
}

func optStr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func strPtr(s string) *string { return &s }
