// Package track is steps 4–7 of 03-tracking.md §3: correlate a provider
// event to its delivery, move the delivery by rank, turn bounce/complaint
// into suppression, and emit the outcome event through the outbox — all
// inside the transaction the track worker holds.
package track

import (
	"context"
	"encoding/json"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/domain/service/outcome"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Result is the label value of provider_event_processed_total{outcome}
// (14-observability.md §2.4).
type Result string

const (
	ResultApplied          Result = "applied"
	ResultIgnoredLowerRank Result = "ignored_lower_rank"
	ResultUnmatched        Result = "unmatched"
	ResultIgnored          Result = "ignored"
)

// Outcome tells the worker what to do with the provider_event row.
type Outcome struct {
	Result Result
	// Changed is true when a delivery row, suppression or preference was
	// written (the outbox event follows the same flag).
	Changed bool
}

// SuppressionSource is the suppression.source value for provider events.
const SuppressionSource = "ses"

// Service processes one provider event against the repositories of the
// transaction it is given.
type Service struct {
	softBounce time.Duration
	// correlate is the provider whose deliveries SES events are matched
	// against (track.correlate_provider; empty = the event provider).
	correlate entity.Provider
	clock     clock.Clock
	metrics   *telemetry.Metrics
	log       *zap.Logger
}

// New builds the service; cfg.SoftBounceSuppression sets the transient
// bounce suppression window (03-tracking.md §5.2: 24–72 h).
func New(cfg config.TrackConfig, clk clock.Clock, m *telemetry.Metrics, log *zap.Logger) *Service {
	if clk == nil {
		clk = clock.System{}
	}
	if log == nil {
		log = zap.NewNop()
	}
	soft := cfg.SoftBounceSuppression
	if soft <= 0 {
		soft = 48 * time.Hour
	}
	return &Service{softBounce: soft, correlate: entity.Provider(cfg.CorrelateProvider), clock: clk, metrics: m, log: log.With(zap.String("component", "track"))}
}

// Process applies ev using the repositories of tx. The caller owns the
// transaction: a returned error must roll it back and leave the event for a
// later attempt. Unmatched is not an error (the deliver worker may not have
// committed provider_message_id yet, 03-tracking.md §5.1).
func (s *Service) Process(ctx context.Context, tx port.Tx, ev *entity.ProviderEvent) (Outcome, error) {
	out, err := s.process(ctx, tx, ev)
	if err == nil && s.metrics != nil {
		s.metrics.ProviderEventProcessed.WithLabelValues(string(ev.EventType), string(out.Result)).Inc()
	}
	return out, err
}

func (s *Service) process(ctx context.Context, tx port.Tx, ev *entity.ProviderEvent) (Outcome, error) {
	log := s.log.With(zap.Int64("provider_event_id", ev.ID), zap.String("event_type", string(ev.EventType)))

	switch ev.EventType {
	case entity.ProviderEventDeliveryDelay, entity.ProviderEventOther:
		return Outcome{Result: ResultIgnored}, nil
	}
	if ev.ProviderMessageID == nil || *ev.ProviderMessageID == "" {
		log.Warn("provider event without message id cannot be correlated; ignoring")
		return Outcome{Result: ResultIgnored}, nil
	}

	// Step 4: correlate. Not found is expected during the deliver-worker
	// commit window; the worker reschedules.
	// The event source is always SES; the delivery was sent by the configured
	// provider (ses in production, mailpit in dev — track.correlate_provider).
	provider := ev.Provider
	if s.correlate != "" {
		provider = s.correlate
	}
	d, err := tx.Deliveries().FindByProviderMessage(ctx, provider, *ev.ProviderMessageID)
	if err != nil {
		if errors.Is(err, errors.ErrNotFound) {
			return Outcome{Result: ResultUnmatched}, nil
		}
		return Outcome{}, err
	}
	if d == nil {
		return Outcome{Result: ResultUnmatched}, nil
	}
	log = log.With(zap.String("delivery_id", d.ID.String()), zap.String("lane", string(d.Lane)))

	now := s.clock.Now()
	at := now
	if ev.OccurredAt != nil {
		at = ev.OccurredAt.UTC()
	}
	det := parseDetails(ev.Raw)

	// 03-tracking.md §6 item 3: the payload is data. When the deliver worker
	// tagged the mail with delivery_id, the tag must agree with the row the
	// message id resolved to before we let the event write suppression.
	trusted := det.deliveryID == nil || *det.deliveryID == d.ID
	if !trusted {
		log.Warn("delivery_id tag disagrees with correlated delivery; suppression disabled for this event",
			zap.String("tag_delivery_id", det.deliveryID.String()))
	}
	rc := s.requestContext(ctx, tx, d)

	switch ev.EventType {
	case entity.ProviderEventSend:
		return s.transition(ctx, tx, d, entity.StatusSent, at, "send", nil)

	case entity.ProviderEventDelivery:
		return s.transition(ctx, tx, d, entity.StatusDelivered, at, "delivery", func() (*entity.OutboxEvent, error) {
			return outcome.Delivered(d, ev.Provider, at, rc)
		})

	case entity.ProviderEventBounce:
		return s.bounce(ctx, tx, d, ev, det, trusted, at, rc, log)

	case entity.ProviderEventComplaint:
		return s.complaint(ctx, tx, d, ev, det, trusted, at, rc, log)

	case entity.ProviderEventReject:
		// SES accepted the mail then refused it (virus scan). No SES bounce
		// record exists and MarkFinal needs a SENDING lease, so the delivery
		// goes BOUNCED (rank 3, note "reject") and downstream sees a
		// permanent, unsuppressed bounce.
		return s.transition(ctx, tx, d, entity.StatusBounced, at, "reject", func() (*entity.OutboxEvent, error) {
			return outcome.Bounced(d, ev.Provider, entity.BouncePermanent, false, at, rc)
		})
	}
	return Outcome{Result: ResultIgnored}, nil
}

// transition applies the rank rule through the repository and inserts the
// outbox event only when the status actually changed (layer D: one event
// per state change, never per webhook).
func (s *Service) transition(ctx context.Context, tx port.Tx, d *entity.Delivery, to entity.DeliveryStatus, at time.Time, note string, event func() (*entity.OutboxEvent, error)) (Outcome, error) {
	if d.Status.TrackingRank() > to.TrackingRank() {
		return Outcome{Result: ResultIgnoredLowerRank}, nil
	}
	changed, err := tx.Deliveries().TransitionTracked(ctx, d.ID, to, at, note)
	if err != nil {
		return Outcome{}, err
	}
	if changed && event != nil {
		if err := s.emit(ctx, tx, d, event); err != nil {
			return Outcome{}, err
		}
		d.Status = to
	}
	return Outcome{Result: ResultApplied, Changed: changed}, nil
}

func (s *Service) bounce(ctx context.Context, tx port.Tx, d *entity.Delivery, ev *entity.ProviderEvent, det details, trusted bool, at time.Time, rc outcome.Context, log *zap.Logger) (Outcome, error) {
	bounceType := det.bounceType
	if bounceType == "" {
		bounceType = entity.BounceTransient
	}
	if s.metrics != nil {
		s.metrics.BounceTotal.WithLabelValues(string(d.Lane), string(bounceType)).Inc()
	}
	// 03-tracking.md §5.2: every lane but critical is suppressed — a user
	// who asked for an OTP still gets the attempt.
	suppressAllowed := trusted && d.Lane != entity.LaneCritical

	if bounceType == entity.BouncePermanent {
		suppressed := false
		if suppressAllowed {
			n, err := s.suppress(ctx, tx, d, det.recipientHashes, entity.SuppressionHardBounce, nil, ev.ID)
			if err != nil {
				return Outcome{}, err
			}
			suppressed = n > 0
		}
		if d.Status.TrackingRank() > entity.StatusBounced.TrackingRank() {
			return Outcome{Result: ResultIgnoredLowerRank, Changed: suppressed}, nil
		}
		changed, err := tx.Deliveries().TransitionTracked(ctx, d.ID, entity.StatusBounced, at, "bounce:permanent")
		if err != nil {
			return Outcome{}, err
		}
		if changed {
			if err := s.emit(ctx, tx, d, func() (*entity.OutboxEvent, error) {
				return outcome.Bounced(d, ev.Provider, entity.BouncePermanent, suppressed, at, rc)
			}); err != nil {
				return Outcome{}, err
			}
			d.Status = entity.StatusBounced
		}
		return Outcome{Result: ResultApplied, Changed: changed || suppressed}, nil
	}

	// Transient: the status stays (a later Delivery may still land); a
	// temporary suppression protects the next sends for the window.
	suppressed := false
	if suppressAllowed {
		until := s.clock.Now().Add(s.softBounce)
		n, err := s.suppress(ctx, tx, d, det.recipientHashes, entity.SuppressionSoftBounce, &until, ev.ID)
		if err != nil {
			return Outcome{}, err
		}
		suppressed = n > 0
	}
	if err := s.emit(ctx, tx, d, func() (*entity.OutboxEvent, error) {
		return outcome.Bounced(d, ev.Provider, entity.BounceTransient, suppressed, at, rc)
	}); err != nil {
		return Outcome{}, err
	}
	log.Info("transient bounce recorded", zap.Bool("suppressed", suppressed))
	return Outcome{Result: ResultApplied, Changed: true}, nil
}

func (s *Service) complaint(ctx context.Context, tx port.Tx, d *entity.Delivery, ev *entity.ProviderEvent, det details, trusted bool, at time.Time, rc outcome.Context, log *zap.Logger) (Outcome, error) {
	if s.metrics != nil {
		s.metrics.ComplaintTotal.WithLabelValues(string(d.Lane)).Inc()
	}
	// The status is left alone: a complaint arrives after delivery and
	// DELIVERED remains true. What changes is future behaviour (08-decide §4).
	if trusted && d.Lane != entity.LaneCritical {
		if _, err := s.suppress(ctx, tx, d, det.recipientHashes, entity.SuppressionComplaint, nil, ev.ID); err != nil {
			return Outcome{}, err
		}
	}
	marketingDisabled := false
	if d.UserID != nil {
		// Local replica first so Decide stops immediately; User Service
		// switches the source off when it consumes complaint-received.
		if err := tx.Preferences().DisableMarketing(ctx, *d.UserID, at.UnixMilli(), s.clock.Now()); err != nil {
			return Outcome{}, err
		}
		marketingDisabled = true
	}
	if err := s.emit(ctx, tx, d, func() (*entity.OutboxEvent, error) {
		return outcome.ComplaintReceived(d, ev.Provider, marketingDisabled, at, rc)
	}); err != nil {
		return Outcome{}, err
	}
	log.Info("complaint recorded", zap.Bool("marketing_disabled", marketingDisabled))
	return Outcome{Result: ResultApplied, Changed: true}, nil
}

// suppress inserts one row per recipient hash unless an equal active row
// already exists. Returns how many hashes are now suppressed (inserted or
// pre-existing).
func (s *Service) suppress(ctx context.Context, tx port.Tx, d *entity.Delivery, hashes []string, reason entity.SuppressionReason, until *time.Time, evidence int64) (int, error) {
	now := s.clock.Now()
	count := 0
	for _, h := range hashes {
		existing, err := tx.Suppressions().ActiveByHash(ctx, h)
		if err != nil {
			return count, err
		}
		already := false
		for _, row := range existing {
			if row.Reason == reason && row.ActiveAt(now) && sameUser(row.UserID, d.UserID) {
				already = true
				break
			}
		}
		if already {
			count++
			continue
		}
		row := &entity.Suppression{
			ID:              uuid.New(),
			UserID:          d.UserID,
			EmailHash:       h,
			Reason:          reason,
			Source:          SuppressionSource,
			Until:           until,
			EvidenceEventID: &evidence,
			CreatedAt:       now,
		}
		if err := tx.Suppressions().Insert(ctx, row); err != nil {
			return count, err
		}
		if s.metrics != nil {
			s.metrics.SuppressionAdded.WithLabelValues(string(reason)).Inc()
		}
		count++
	}
	return count, nil
}

func sameUser(a, b *uuid.UUID) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

// emit builds and inserts the outbox event. An anonymised delivery (user_id
// NULL) cannot produce an outcome event; that is logged, not fatal.
func (s *Service) emit(ctx context.Context, tx port.Tx, d *entity.Delivery, build func() (*entity.OutboxEvent, error)) error {
	if d.UserID == nil {
		s.log.Info("delivery is anonymised; outcome event skipped", zap.String("delivery_id", d.ID.String()))
		return nil
	}
	ev, err := build()
	if err != nil {
		return err
	}
	return tx.Outbox().Insert(ctx, ev)
}

// requestContext fetches correlation ids for the outcome event; a missing
// request (purged, anonymised) just yields an empty context.
func (s *Service) requestContext(ctx context.Context, tx port.Tx, d *entity.Delivery) outcome.Context {
	r, err := tx.Requests().GetByID(ctx, d.RequestID)
	if err != nil || r == nil {
		return outcome.Context{}
	}
	var rc outcome.Context
	if r.CorrelationID != nil {
		rc.CorrelationID = *r.CorrelationID
	}
	if r.TraceParent != nil {
		rc.TraceParent = *r.TraceParent
	}
	return rc
}

// ---- reading the sanitised raw JSON -------------------------------------

// details is what Track needs from provider_event.raw. The poller already
// replaced every address with its sha256, so "emailAddress" fields hold
// hashes (possibly inside a display form "Name <hash>").
type details struct {
	bounceType      entity.BounceType
	deliveryID      *uuid.UUID
	recipientHashes []string
}

var hexHashRE = regexp.MustCompile(`[0-9a-f]{64}`)

func parseDetails(raw json.RawMessage) details {
	var r struct {
		Mail struct {
			Destination []string            `json:"destination"`
			Tags        map[string][]string `json:"tags"`
		} `json:"mail"`
		Bounce *struct {
			BounceType        string `json:"bounceType"`
			BouncedRecipients []struct {
				EmailAddress string `json:"emailAddress"`
			} `json:"bouncedRecipients"`
		} `json:"bounce"`
		Complaint *struct {
			ComplainedRecipients []struct {
				EmailAddress string `json:"emailAddress"`
			} `json:"complainedRecipients"`
		} `json:"complaint"`
	}
	var det details
	if len(raw) == 0 || json.Unmarshal(raw, &r) != nil {
		return det
	}
	var recipients []string
	if r.Bounce != nil {
		if strings.EqualFold(r.Bounce.BounceType, "Permanent") {
			det.bounceType = entity.BouncePermanent
		} else if r.Bounce.BounceType != "" {
			det.bounceType = entity.BounceTransient
		}
		for _, rc := range r.Bounce.BouncedRecipients {
			recipients = append(recipients, rc.EmailAddress)
		}
	}
	if r.Complaint != nil {
		for _, rc := range r.Complaint.ComplainedRecipients {
			recipients = append(recipients, rc.EmailAddress)
		}
	}
	if len(recipients) == 0 {
		recipients = r.Mail.Destination
	}
	seen := map[string]bool{}
	for _, v := range recipients {
		h := hexHashRE.FindString(strings.ToLower(v))
		if h == "" || seen[h] {
			continue
		}
		seen[h] = true
		det.recipientHashes = append(det.recipientHashes, h)
	}
	if ids := r.Mail.Tags["delivery_id"]; len(ids) > 0 {
		if id, err := uuid.Parse(strings.TrimSpace(ids[0])); err == nil {
			det.deliveryID = &id
		}
	}
	return det
}
