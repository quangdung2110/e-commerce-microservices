// Package deliver is the per-row logic of boundary 2 (02-pipeline.md §5.3):
// given one delivery row a worker has just claimed, re-check what may have
// changed while it waited, resolve the recipient, render, send (or write the
// inbox item) and record the outcome with compare-and-set writes
// (04-idempotency.md lớp C2). The worker pool in internal/workers/deliver
// owns claiming, concurrency and heartbeats; this package owns the decision
// tree so it can be tested with in-memory fakes.
package deliver

import (
	"context"
	stderrors "errors"
	"fmt"
	"math"
	"math/rand/v2"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/domain/service/outcome"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Local reason codes recorded in last_error_code for a Release/Retry that the
// entity vocabulary does not cover. They are operational hints, never final
// FAILED reasons except auth_unavailable after max_attempts.
const (
	// ReasonLanePaused: row released because ops paused the lane × channel.
	ReasonLanePaused = "lane_paused"
	// ReasonDailyQuota: bulk row released because the account is past the
	// bulk share of Max24HourSend (06-backpressure.md §4.1).
	ReasonDailyQuota = "daily_quota"
	// ReasonRetryBudgetDefer marks a retry pushed out by the retry budget.
	ReasonRetryBudgetDefer = "retry_budget_defer"
	// ReasonAuthUnavailable: Auth internal API failed with a retryable error.
	ReasonAuthUnavailable = "auth_unavailable"
)

// Attempt outcomes for delivery_attempts_total (14-observability.md §2.2).
const (
	OutcomeSent           = "sent"
	OutcomeRetryableError = "retryable_error"
	OutcomePermanentError = "permanent_error"
	OutcomeBreakerOpen    = "breaker_open"
	OutcomeRateLimited    = "rate_limited"
	OutcomeLanePaused     = "lane_paused"
	OutcomeDailyQuota     = "daily_quota"
)

// maxErrorLen bounds last_error.
const maxErrorLen = 500

// dailyQuotaRetry is how far a bulk row is pushed when the daily quota share
// is used up; the counter resets on the UTC day boundary anyway.
const dailyQuotaRetry = time.Hour

// Deps are the ports the service talks to.
type Deps struct {
	Store       port.Store
	Renderer    port.Renderer
	Email       port.EmailSender
	Contacts    port.ContactResolver
	Limiter     port.RateLimiter
	Daily       port.DailyQuota
	RetryBudget port.RetryBudget
	Unread      port.UnreadCounter
	Control     port.LaneControl
	// Breaker guards the email provider; shared across lanes (06 §5).
	Breaker port.CircuitBreaker
	Catalog *catalog.Catalog
	// Keyring re-derives every key version of an Order subject for the
	// superseded check (dual-read); nil → only the stored ordering key.
	Keyring *pii.Keyring
	Clock   clock.Clock
	Metrics *telemetry.Metrics // nilable
	Logger  *zap.Logger        // nilable
	// Rand returns [0,1) for jitter; nil → math/rand.
	Rand func() float64
}

// Config is the subset of the runtime configuration the service reads.
type Config struct {
	Lanes     config.LanesConfig
	Deliver   config.DeliverConfig
	Email     config.EmailConfig
	Frontend  config.FrontendConfig
	Bulk      config.BulkConfig
	Templates config.TemplatesConfig
}

// Service processes claimed delivery rows.
type Service struct {
	deps Deps
	cfg  Config
}

// New wires the service. Every port is required except Metrics/Logger/Rand.
func New(deps Deps, cfg Config) *Service {
	if deps.Logger == nil {
		deps.Logger = zap.NewNop()
	}
	if deps.Clock == nil {
		deps.Clock = clock.System{}
	}
	if deps.Rand == nil {
		deps.Rand = rand.Float64
	}
	if cfg.Templates.DefaultLocale == "" {
		cfg.Templates.DefaultLocale = "vi"
	}
	return &Service{deps: deps, cfg: cfg}
}

// Backoff is the retry schedule of 02-pipeline.md §5.3:
// min(base × 2^(attempt-1), max) + random(0, jitter). attempt is the attempt
// that just failed (>= 1).
func Backoff(attempt int, base, maxDelay, jitter time.Duration, rnd func() float64) time.Duration {
	if attempt < 1 {
		attempt = 1
	}
	if base <= 0 {
		base = 30 * time.Second
	}
	if maxDelay <= 0 {
		maxDelay = time.Hour
	}
	exp := math.Ldexp(float64(base), attempt-1) // base × 2^(attempt-1) without overflow
	d := maxDelay
	if exp < float64(maxDelay) {
		d = time.Duration(exp)
	}
	if jitter > 0 && rnd != nil {
		d += time.Duration(rnd() * float64(jitter))
	}
	return d
}

// run is the per-call working set.
type run struct {
	ctx      context.Context
	d        *entity.Delivery
	workerID string
	now      time.Time
	lane     config.LaneConfig
	log      *zap.Logger
	req      *entity.Request
	reqErr   error
	reqDone  bool
	pref     entity.Preference
	possDup  bool
	// ordering is the held per-Order-subject lease; nil for other rows.
	ordering *orderingGuard
}

// errStop aborts a transaction on a lost compare-and-set (another worker or
// the reaper took the row; layer C2).
var errStop = stderrors.New("deliver: lost compare-and-set race")

// Process handles one claimed row end to end. It returns an error only for
// infrastructure failures (database down); the row then stays SENDING until
// the lease expires and the reaper returns it to the queue.
func (s *Service) Process(ctx context.Context, d entity.Delivery, workerID string) error {
	r := &run{
		ctx:      ctx,
		d:        &d,
		workerID: workerID,
		now:      s.deps.Clock.Now(),
		lane:     s.cfg.Lanes.For(string(d.Lane)),
		log: s.deps.Logger.With(
			zap.String("delivery_id", d.ID.String()),
			zap.String("request_id", d.RequestID.String()),
			zap.String("lane", string(d.Lane)),
			zap.String("channel", string(d.Channel)),
			zap.String("template", d.Template),
			zap.Int("attempt", d.Attempt),
			zap.String("worker", workerID),
		),
	}

	// (a) TTL per template: cheapest re-check first (08-decide.md §2).
	if d.ExpiresAt.Before(r.now) {
		return s.finalFailed(r, entity.StatusExpired, entity.ReasonExpired, "")
	}

	// (b) Ops pause switch (16-operations.md §2): release without counting.
	if state, err := s.deps.Control.LaneState(ctx, d.Lane); err != nil {
		r.log.Warn("lane state unavailable, assuming active", zap.Error(err))
	} else if state.IsPaused(d.Channel) {
		return s.release(r, r.now.Add(s.cfg.Deliver.ControlPollInterval), ReasonLanePaused, OutcomeLanePaused)
	}

	if d.UserID == nil {
		// Anonymised while waiting (11-privacy.md §4): nothing to send to.
		return s.skip(r, entity.ReasonUserDeleted)
	}

	// (c) Preference re-check: marketing may have been switched off while a
	// bulk row waited for quiet hours to end.
	if err := s.loadPreference(r); err != nil {
		return err
	}
	if d.Lane == entity.LaneBulk && !r.pref.MarketingEnabled {
		return s.skip(r, entity.ReasonPreferenceOff)
	}

	// (d) Layer C3: a row the reaper returned was already tried once
	// (04-idempotency.md §3).
	if d.WasLeaseExpired() && d.Channel == entity.ChannelEmail {
		switch d.Lane {
		case entity.LaneCritical:
			return s.finalFailed(r, entity.StatusFailed, entity.ReasonLeaseExpired, "previous attempt lost its lease; OTP is not resent")
		case entity.LaneTransactional:
			r.possDup = true
			if s.deps.Metrics != nil {
				s.deps.Metrics.DeliveryPossiblyDupTotal.WithLabelValues(string(d.Lane)).Inc()
			}
		}
	}

	// (e) Order rows: one provider call per order at a time, and never a
	// lower sequence after a newer one was ingested (05-ordering.md §4).
	if d.OrderingKey != nil {
		g, handled, err := s.acquireOrdering(r)
		if handled || err != nil {
			return err
		}
		defer g.stop()
		r.ordering = g
		superseded, err := s.supersededUnderLease(r)
		if err != nil {
			return err
		}
		if superseded {
			return s.skip(r, entity.ReasonSuperseded)
		}
	}

	switch d.Channel {
	case entity.ChannelInApp:
		return s.processInApp(r)
	case entity.ChannelEmail:
		return s.processEmail(r)
	default:
		return s.finalFailed(r, entity.StatusFailed, entity.ReasonChannelNotAllowed, "unknown channel")
	}
}

// ---- in_app --------------------------------------------------------------

// processInApp renders from the stored template_data and writes inbox item,
// DELIVERED status and the outcome event in one transaction (08-decide.md §8).
// Auth secret templates never have an in_app channel, so Auth is never called.
func (s *Service) processInApp(r *run) error {
	d := r.d
	req, err := s.loadRequest(r)
	if err != nil {
		return err
	}
	locale := r.pref.LocaleOr(s.cfg.Templates.DefaultLocale)
	content, err := s.deps.Renderer.RenderInApp(d.Template, locale, req.TemplateData, port.RenderOptions{
		DeliveryID: d.ID,
		Lane:       d.Lane,
		Timezone:   r.pref.TimezoneOr(s.cfg.Bulk.DefaultTimezone),
	})
	if err != nil {
		return s.finalFailed(r, entity.StatusFailed, entity.ReasonRenderFailed, err.Error())
	}
	version := s.deps.Renderer.Version(d.Template)
	item := &entity.InboxItem{
		ID:        d.ID,
		UserID:    *d.UserID,
		Template:  d.Template,
		Title:     content.Title,
		Body:      content.Body,
		DeepLink:  content.DeepLink,
		CreatedAt: r.now,
	}
	ev, err := outcome.Delivered(d, entity.ProviderInbox, r.now, s.outcomeCtx(r))
	if err != nil {
		return fmt.Errorf("deliver: build delivered event: %w", err)
	}
	err = s.deps.Store.WithinTx(r.ctx, func(ctx context.Context, tx port.Tx) error {
		// Order rows commit only while still owning the ordering lease; the
		// FOR SHARE lock holds it for the rest of this transaction.
		if r.ordering != nil {
			holds, err := tx.Ordering().HoldsLease(ctx, r.ordering.key, d.ID, r.workerID)
			if err != nil {
				return err
			}
			if !holds {
				return errOrderingLost
			}
		}
		ok, err := tx.Deliveries().MarkDeliveredInApp(ctx, d.ID, r.workerID, version, r.now)
		if err != nil {
			return err
		}
		if !ok {
			return errStop
		}
		if err := tx.Inbox().Insert(ctx, item); err != nil {
			return err
		}
		return tx.Outbox().Insert(ctx, ev)
	})
	if stderrors.Is(err, errStop) {
		r.log.Info("lost race on in_app delivery; another worker finished it")
		return nil
	}
	if stderrors.Is(err, errOrderingLost) {
		r.ordering.lost(stageBeforeSend)
		return s.release(r, r.now.Add(s.cfg.Deliver.OrderingBusyRetry), entity.ReasonOrderingLost, OutcomeOrderingLost)
	}
	if err != nil {
		return fmt.Errorf("deliver: in_app transaction: %w", err)
	}
	// Redis INCR after commit; a miss is recomputed from inbox_item.
	if err := s.deps.Unread.Add(r.ctx, *d.UserID, 1); err != nil {
		r.log.Warn("unread counter increment failed", zap.Error(err))
	}
	s.attempt(d, string(entity.ProviderInbox), OutcomeSent)
	s.finished(d, entity.StatusDelivered, "")
	s.endToEnd(r)
	r.log.Info("in_app delivered")
	return nil
}

// ---- email ---------------------------------------------------------------

func (s *Service) processEmail(r *run) error {
	d := r.d
	providerLabel := string(s.deps.Email.Provider())

	// (f) Provider breaker: refuse without spending the attempt (06 §5).
	if !s.deps.Breaker.Allow() {
		return s.release(r, r.now.Add(orDefault(r.lane.BreakerOpenRetry, 30*time.Second)), entity.ReasonBreakerOpen, OutcomeBreakerOpen)
	}

	// Bulk stops at the bulk share of the daily account quota (06 §4.1).
	if d.Lane == entity.LaneBulk && s.cfg.Email.SES.Max24HourSend > 0 {
		used, err := s.deps.Daily.Get(r.ctx, s.deps.Email.Provider(), r.now)
		if err != nil {
			r.log.Warn("daily quota unavailable, continuing", zap.Error(err))
		} else {
			if s.deps.Metrics != nil {
				s.deps.Metrics.ProviderDailyQuota.WithLabelValues(providerLabel).Set(float64(used) / float64(s.cfg.Email.SES.Max24HourSend))
			}
			ratio := s.cfg.Email.SES.BulkStopDailyRatio
			if ratio <= 0 {
				ratio = 0.8
			}
			if float64(used) >= float64(s.cfg.Email.SES.Max24HourSend)*ratio {
				return s.release(r, r.now.Add(dailyQuotaRetry), ReasonDailyQuota, OutcomeDailyQuota)
			}
		}
	}

	// Resolve the recipient (02-pipeline.md §2.3). templateData for Auth
	// secret templates lives only in this frame.
	tpl, ok := s.deps.Catalog.Get(d.Template)
	if !ok {
		return s.finalFailed(r, entity.StatusFailed, entity.ReasonUnknownTemplate, "template not in catalog")
	}
	var (
		to           = entity.Contact{}
		templateData []byte
		verified     = true
	)
	if tpl.DataSource == catalog.SourceAuthContentAPI {
		if d.SourceRef == nil {
			return s.finalFailed(r, entity.StatusFailed, entity.ReasonNoRecipient, "auth template without source_ref")
		}
		authID, err := uuid.Parse(*d.SourceRef)
		if err != nil {
			return s.finalFailed(r, entity.StatusFailed, entity.ReasonNoRecipient, "auth template with malformed source_ref")
		}
		content, err := s.deps.Contacts.Content(r.ctx, d.Lane, authID)
		if err != nil {
			return s.resolveError(r, err)
		}
		if content.Template != d.Template {
			return s.finalFailed(r, entity.StatusFailed, entity.ReasonTemplateMismatch,
				fmt.Sprintf("auth returned template %s for delivery of %s", content.Template, d.Template))
		}
		to.UserID, to.Email = *d.UserID, content.Recipient
		templateData = content.TemplateData
	} else {
		req, err := s.loadRequest(r)
		if err != nil {
			return err
		}
		contact, err := s.deps.Contacts.Contact(r.ctx, d.Lane, *d.UserID)
		if err != nil {
			return s.resolveError(r, err)
		}
		to = *contact
		verified = contact.EmailVerified
		templateData = req.TemplateData
	}
	if to.Email.IsZero() {
		return s.finalFailed(r, entity.StatusFailed, entity.ReasonNoRecipient, "empty recipient")
	}

	// Bulk never goes to an unverified address (10-interfaces.md §4).
	if d.Lane == entity.LaneBulk && !verified {
		return s.skip(r, entity.ReasonEmailUnverified)
	}

	// Suppression confirmed by hash (08-decide.md §4 step 3).
	if !s.deps.Catalog.Lane(d.Lane).IgnoresSuppression {
		suppressed, err := s.checkSuppression(r, to.Email.Hash())
		if err != nil {
			return err
		}
		if suppressed {
			return s.skip(r, entity.ReasonSuppressed)
		}
	}

	// Render (12-render.md). Locale: preference, then Auth contact, then vi.
	locale := r.pref.LocaleOr(to.Locale)
	if locale == "" {
		locale = s.cfg.Templates.DefaultLocale
	}
	opts := port.RenderOptions{
		DeliveryID: d.ID,
		Lane:       d.Lane,
		Timezone:   r.pref.TimezoneOr(s.cfg.Bulk.DefaultTimezone),
	}
	if d.Lane == entity.LaneBulk {
		opts.UnsubscribeURL = s.cfg.Email.UnsubscribeURL
	}
	content, err := s.deps.Renderer.RenderEmail(d.Template, locale, templateData, opts)
	templateData = nil // secret is no longer needed
	if err != nil {
		return s.finalFailed(r, entity.StatusFailed, entity.ReasonRenderFailed, err.Error())
	}
	if content.Headers == nil {
		content.Headers = map[string]string{}
	}
	if _, set := content.Headers[constants.HeaderDeliveryID]; !set {
		content.Headers[constants.HeaderDeliveryID] = d.ID.String() // 04 lớp C1: trace, not idempotency
	}

	// Rate limiter in front of the provider, behind the claim (02 §5.3).
	if err := s.deps.Limiter.Acquire(r.ctx, d.Lane, d.Channel, s.cfg.Deliver.RateLimitWait); err != nil {
		if !stderrors.Is(err, errors.ErrRateLimitWait) {
			r.log.Warn("rate limiter error, releasing row", zap.Error(err))
		}
		return s.release(r, r.now, entity.ReasonRateLimited, OutcomeRateLimited)
	}

	// Order rows: never start a provider call the ordering lease cannot cover
	// (05-ordering.md §4) — release and let the next claim re-acquire.
	if r.ordering != nil && r.ordering.lostWithin(s.sendBudget()) {
		r.ordering.lost(stageBeforeSend)
		return s.release(r, r.now.Add(s.cfg.Deliver.OrderingBusyRetry), entity.ReasonOrderingLost, OutcomeOrderingLost)
	}

	// Send.
	msg := &entity.EmailMessage{DeliveryID: d.ID, Lane: d.Lane, To: to.Email, Content: *content}
	sendStart := s.deps.Clock.Now()
	res, err := s.deps.Email.Send(r.ctx, msg)
	sendDur := s.deps.Clock.Now().Sub(sendStart)
	s.observeSend(providerLabel, d.Lane, sendDur, err)

	// Ownership lost while the provider call was in flight: another delivery
	// of the order may have started meanwhile, so the outcome is uncertain —
	// record it, never repair Order state from it (05-ordering.md §4).
	if r.ordering != nil && r.ordering.lostWithin(0) {
		r.ordering.lost(stageDuringSend)
		r.possDup = true
	}

	if err == nil {
		s.deps.Breaker.Success()
		return s.sent(r, res, s.deps.Renderer.Version(d.Template))
	}
	if code, permanent := errors.PermanentCode(err); permanent {
		// (g) Provider answered: the breaker learns nothing bad.
		s.deps.Breaker.Success()
		s.attempt(d, providerLabel, OutcomePermanentError)
		return s.finalFailed(r, entity.StatusFailed, code, err.Error())
	}
	// (h) Retryable (5xx, timeout, 429) or unclassified.
	s.deps.Breaker.Failure()
	s.attempt(d, providerLabel, OutcomeRetryableError)
	return s.scheduleRetry(r, entity.ReasonProviderError, err)
}

// sent records a successful provider call: SENT with compare-and-set, daily
// counter, retry budget and metrics.
func (s *Service) sent(r *run, res *entity.SendResult, version string) error {
	d := r.d
	provider := s.deps.Email.Provider()
	messageID := ""
	if res != nil {
		if res.Provider != "" {
			provider = res.Provider
		}
		messageID = res.MessageID
	}
	if _, err := s.deps.Daily.Increment(r.ctx, provider, r.now); err != nil {
		r.log.Warn("daily quota increment failed", zap.Error(err))
	}
	sentAt := s.deps.Clock.Now()
	ok, err := s.deps.Store.Deliveries().MarkSent(r.ctx, d.ID, r.workerID, provider, messageID, version, sentAt, r.possDup)
	if err != nil {
		return fmt.Errorf("deliver: mark sent: %w", err)
	}
	if !ok {
		// Another worker (after a reaper pass) finished the row: the email
		// may have gone twice, but no second event or metric (04 lớp C2).
		r.log.Warn("lost race after send; row already finished by another worker", zap.String("provider_message_id", messageID))
		return nil
	}
	if err := s.deps.RetryBudget.Record(r.ctx, d.Lane, d.Attempt > 1); err != nil {
		r.log.Warn("retry budget record failed", zap.Error(err))
	}
	s.attempt(d, string(provider), OutcomeSent)
	s.endToEnd(r)
	r.log.Info("email sent", zap.String("provider", string(provider)), zap.String("provider_message_id", messageID), zap.Bool("possibly_duplicate", r.possDup))
	return nil
}

// resolveError maps a ContactResolver error to a delivery outcome.
func (s *Service) resolveError(r *run, err error) error {
	if code, permanent := errors.PermanentCode(err); permanent {
		return s.finalFailed(r, entity.StatusFailed, code, err.Error())
	}
	if stderrors.Is(err, errors.ErrBreakerOpen) {
		// Auth breaker open is not this row's fault either: release.
		return s.release(r, r.now.Add(orDefault(r.lane.BreakerOpenRetry, 30*time.Second)), entity.ReasonBreakerOpen, OutcomeBreakerOpen)
	}
	s.attempt(r.d, "auth", OutcomeRetryableError)
	return s.scheduleRetry(r, ReasonAuthUnavailable, err)
}

// checkSuppression compares the resolved address hash with the active
// suppressions of the user: equal → suppressed; different → the user changed
// address, mark those rows superseded and send (08-decide.md §4).
func (s *Service) checkSuppression(r *run, emailHash string) (bool, error) {
	rows, err := s.deps.Store.Suppressions().ActiveByUser(r.ctx, *r.d.UserID)
	if err != nil {
		return false, fmt.Errorf("deliver: load suppressions: %w", err)
	}
	suppressed := false
	for _, row := range rows {
		if !row.ActiveAt(r.now) {
			continue
		}
		if row.EmailHash == emailHash {
			suppressed = true
			continue
		}
		if err := s.deps.Store.Suppressions().MarkSuperseded(r.ctx, row.ID, r.now); err != nil {
			return false, fmt.Errorf("deliver: supersede suppression %s: %w", row.ID, err)
		}
		r.log.Info("suppression superseded: user changed address", zap.String("suppression_id", row.ID.String()), zap.String("reason", string(row.Reason)))
	}
	return suppressed, nil
}

// scheduleRetry applies the backoff schedule, the retry budget (06 §6.1) and
// the per-lane attempt ceiling.
func (s *Service) scheduleRetry(r *run, code string, cause error) error {
	d := r.d
	maxAttempts := r.lane.MaxAttempts
	if maxAttempts <= 0 {
		maxAttempts = 5
	}
	if d.Attempt >= maxAttempts {
		return s.finalFailed(r, entity.StatusFailed, code, truncate(cause.Error(), maxErrorLen))
	}
	delay := Backoff(d.Attempt, s.cfg.Deliver.RetryBase, s.cfg.Deliver.RetryMax, s.cfg.Deliver.RetryJitter, s.deps.Rand)
	errCode := code
	if ratio, err := s.deps.RetryBudget.Ratio(r.ctx, d.Lane); err != nil {
		r.log.Warn("retry budget ratio unavailable", zap.Error(err))
	} else {
		if s.deps.Metrics != nil {
			s.deps.Metrics.RetryBudgetRatio.WithLabelValues(string(d.Lane)).Set(ratio)
		}
		if s.cfg.Deliver.RetryBudgetRatio > 0 && ratio > s.cfg.Deliver.RetryBudgetRatio {
			// System is weak: prefer fresh rows, push this retry far out.
			defer_ := s.cfg.Deliver.RetryBudgetDefer
			if defer_ <= 0 {
				defer_ = 15 * time.Minute
			}
			delay = defer_ + time.Duration(s.deps.Rand()*float64(s.cfg.Deliver.RetryJitter))
			errCode = ReasonRetryBudgetDefer
		}
	}
	next := r.now.Add(delay)
	ok, err := s.deps.Store.Deliveries().MarkRetry(r.ctx, d.ID, r.workerID, next, errCode, truncate(cause.Error(), maxErrorLen))
	if err != nil {
		return fmt.Errorf("deliver: mark retry: %w", err)
	}
	if !ok {
		r.log.Info("lost race while scheduling retry")
		return nil
	}
	if err := s.deps.RetryBudget.Record(r.ctx, d.Lane, true); err != nil {
		r.log.Warn("retry budget record failed", zap.Error(err))
	}
	r.log.Warn("delivery scheduled for retry", zap.String("reason", errCode), zap.Time("next_attempt_at", next), zap.String("error", truncate(cause.Error(), 200)))
	return nil
}

// ---- state writes --------------------------------------------------------

// finalFailed moves the row to FAILED/EXPIRED and inserts notification.failed
// in the same transaction (layer D).
func (s *Service) finalFailed(r *run, status entity.DeliveryStatus, reason, detail string) error {
	d := r.d
	var ev *entity.OutboxEvent
	if d.UserID != nil {
		var err error
		ev, err = outcome.Failed(d, s.providerFor(d), status, reason, r.now, s.outcomeCtx(r))
		if err != nil {
			r.log.Warn("cannot build failed event", zap.Error(err))
			ev = nil
		}
	}
	err := s.deps.Store.WithinTx(r.ctx, func(ctx context.Context, tx port.Tx) error {
		ok, err := tx.Deliveries().MarkFinal(ctx, d.ID, r.workerID, status, reason, truncate(detail, maxErrorLen), r.now)
		if err != nil {
			return err
		}
		if !ok {
			return errStop
		}
		if ev != nil {
			return tx.Outbox().Insert(ctx, ev)
		}
		return nil
	})
	if stderrors.Is(err, errStop) {
		r.log.Info("lost race while finishing row", zap.String("status", string(status)), zap.String("reason", reason))
		return nil
	}
	if err != nil {
		return fmt.Errorf("deliver: mark %s: %w", status, err)
	}
	s.finished(d, status, reason)
	r.log.Warn("delivery finished", zap.String("status", string(status)), zap.String("reason", reason))
	return nil
}

// skip marks the row SKIPPED with skip_reason; no outcome event exists for
// a skip (the timeline shows the reason).
func (s *Service) skip(r *run, reason string) error {
	d := r.d
	ok, err := s.deps.Store.Deliveries().MarkFinal(r.ctx, d.ID, r.workerID, entity.StatusSkipped, reason, "", r.now)
	if err != nil {
		return fmt.Errorf("deliver: mark skipped: %w", err)
	}
	if !ok {
		r.log.Info("lost race while skipping row", zap.String("reason", reason))
		return nil
	}
	s.finished(d, entity.StatusSkipped, reason)
	r.log.Info("delivery skipped", zap.String("reason", reason))
	return nil
}

// release returns a row that was never really tried (attempt − 1).
func (s *Service) release(r *run, next time.Time, code, outcomeLabel string) error {
	d := r.d
	ok, err := s.deps.Store.Deliveries().Release(r.ctx, d.ID, r.workerID, next, code)
	if err != nil {
		return fmt.Errorf("deliver: release: %w", err)
	}
	if !ok {
		r.log.Info("lost race while releasing row", zap.String("reason", code))
		return nil
	}
	s.attempt(d, s.providerLabelFor(d), outcomeLabel)
	r.log.Info("delivery released", zap.String("reason", code), zap.Time("next_attempt_at", next))
	return nil
}

// ---- helpers -------------------------------------------------------------

func (s *Service) loadPreference(r *run) error {
	p, err := s.deps.Store.Preferences().Get(r.ctx, *r.d.UserID)
	switch {
	case err == nil && p != nil:
		r.pref = *p
	case stderrors.Is(err, errors.ErrNotFound) || (err == nil && p == nil):
		r.pref = entity.DefaultPreference(*r.d.UserID)
	default:
		return fmt.Errorf("deliver: load preference: %w", err)
	}
	return nil
}

// loadRequest fetches the request row once per Process (template_data,
// correlation id, received_at).
func (s *Service) loadRequest(r *run) (*entity.Request, error) {
	if !r.reqDone {
		r.reqDone = true
		req, err := s.deps.Store.Requests().GetByID(r.ctx, r.d.RequestID)
		if err != nil {
			r.reqErr = fmt.Errorf("deliver: load request: %w", err)
		} else if req == nil {
			r.reqErr = fmt.Errorf("deliver: load request: %w", errors.ErrNotFound)
		} else {
			r.req = req
		}
	}
	return r.req, r.reqErr
}

// outcomeCtx is best effort: an outcome without correlation id is better
// than no outcome.
func (s *Service) outcomeCtx(r *run) outcome.Context {
	req, err := s.loadRequest(r)
	if err != nil || req == nil {
		return outcome.Context{}
	}
	oc := outcome.Context{}
	if req.CorrelationID != nil {
		oc.CorrelationID = *req.CorrelationID
	}
	if req.TraceParent != nil {
		oc.TraceParent = *req.TraceParent
	}
	return oc
}

func (s *Service) providerFor(d *entity.Delivery) entity.Provider {
	if d.Provider != nil && *d.Provider != "" {
		return *d.Provider
	}
	if d.Channel == entity.ChannelInApp {
		return entity.ProviderInbox
	}
	return s.deps.Email.Provider()
}

func (s *Service) providerLabelFor(d *entity.Delivery) string {
	return string(s.providerFor(d))
}

func orDefault(d, def time.Duration) time.Duration {
	if d <= 0 {
		return def
	}
	return d
}

func (s *Service) attempt(d *entity.Delivery, provider, outcomeLabel string) {
	if s.deps.Metrics == nil {
		return
	}
	s.deps.Metrics.DeliveryAttemptsTotal.WithLabelValues(string(d.Lane), string(d.Channel), provider, outcomeLabel).Inc()
}

func (s *Service) finished(d *entity.Delivery, status entity.DeliveryStatus, reason string) {
	if s.deps.Metrics == nil {
		return
	}
	s.deps.Metrics.DeliveryFinishedTotal.WithLabelValues(string(d.Lane), string(d.Channel), d.Template, string(status), reason).Inc()
}

func (s *Service) endToEnd(r *run) {
	if s.deps.Metrics == nil {
		return
	}
	req, err := s.loadRequest(r)
	if err != nil || req == nil {
		return
	}
	s.deps.Metrics.DeliveryEndToEnd.WithLabelValues(string(r.d.Lane), string(r.d.Channel)).Observe(s.deps.Clock.Now().Sub(req.ReceivedAt).Seconds())
}

func (s *Service) observeSend(provider string, lane entity.Lane, d time.Duration, err error) {
	if s.deps.Metrics == nil {
		return
	}
	status := "ok"
	if err != nil {
		status = "error"
		if errors.IsRetryable(err) {
			status = "retryable"
		} else if _, permanent := errors.PermanentCode(err); permanent {
			status = "permanent"
		}
	}
	s.deps.Metrics.DependencyDuration.WithLabelValues(provider, string(lane), status).Observe(d.Seconds())
	s.deps.Metrics.DeliverySendDuration.WithLabelValues(provider, string(lane)).Observe(d.Seconds())
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
