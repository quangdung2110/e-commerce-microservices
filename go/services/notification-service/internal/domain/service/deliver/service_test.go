package deliver

import (
	"context"
	stderrors "errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var (
	now      = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)
	workerID = "transactional-email-host-0"
)

// harness bundles the fakes behind one Service.
type harness struct {
	svc      *Service
	store    *fakeStore
	renderer *fakeRenderer
	sender   *fakeSender
	contacts *fakeContacts
	limiter  *fakeLimiter
	daily    *fakeDaily
	budget   *fakeBudget
	unread   *fakeUnread
	control  *fakeControl
	breaker  *fakeBreaker
	clk      *clock.Fake
	metrics  *telemetry.Metrics
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	cat, err := catalog.Load()
	require.NoError(t, err)
	h := &harness{
		store:    newFakeStore(),
		renderer: &fakeRenderer{},
		sender:   &fakeSender{provider: entity.ProviderMailpit, msgID: "msg-1"},
		contacts: &fakeContacts{contact: &entity.Contact{Email: pii.MustEmail("alice@example.com"), EmailVerified: true}},
		limiter:  &fakeLimiter{},
		daily:    &fakeDaily{},
		budget:   &fakeBudget{},
		unread:   &fakeUnread{},
		control:  &fakeControl{},
		breaker:  &fakeBreaker{allow: true},
		clk:      clock.NewFake(now),
		metrics:  telemetry.New(),
	}
	cfg := Config{
		Lanes: config.LanesConfig{
			Critical:      config.LaneConfig{BreakerOpenRetry: 30 * time.Second, MaxAttempts: 5},
			Transactional: config.LaneConfig{BreakerOpenRetry: 5 * time.Minute, MaxAttempts: 8},
			Bulk:          config.LaneConfig{BreakerOpenRetry: 30 * time.Minute, MaxAttempts: 8},
		},
		Deliver: config.DeliverConfig{
			RetryBase:           30 * time.Second,
			RetryMax:            time.Hour,
			RetryJitter:         30 * time.Second,
			RateLimitWait:       5 * time.Second,
			RetryBudgetRatio:    0.2,
			RetryBudgetDefer:    15 * time.Minute,
			ControlPollInterval: 5 * time.Second,
		},
		Email: config.EmailConfig{
			Provider:       "smtp",
			UnsubscribeURL: "http://localhost:3000/profile/preferences",
			SES:            config.SESConfig{Max24HourSend: 1000, BulkStopDailyRatio: 0.8},
		},
		Bulk:      config.BulkConfig{DefaultTimezone: "Asia/Ho_Chi_Minh"},
		Templates: config.TemplatesConfig{DefaultLocale: "vi"},
	}
	h.svc = New(Deps{
		Store:       h.store,
		Renderer:    h.renderer,
		Email:       h.sender,
		Contacts:    h.contacts,
		Limiter:     h.limiter,
		Daily:       h.daily,
		RetryBudget: h.budget,
		Unread:      h.unread,
		Control:     h.control,
		Breaker:     h.breaker,
		Catalog:     cat,
		Clock:       h.clk,
		Metrics:     h.metrics,
		Rand:        func() float64 { return 0.5 },
	}, cfg)
	return h
}

func (h *harness) delivery(lane entity.Lane, ch entity.Channel, template string) entity.Delivery {
	userID := uuid.New()
	reqID := uuid.New()
	corr := "corr-1"
	h.store.reqs.byID[reqID] = &entity.Request{
		ID:            reqID,
		UserID:        &userID,
		TemplateData:  []byte(`{"order_id":"o-1"}`),
		CorrelationID: &corr,
		ReceivedAt:    now.Add(-2 * time.Second),
	}
	return entity.Delivery{
		ID:            uuid.New(),
		RequestID:     reqID,
		UserID:        &userID,
		Lane:          lane,
		Channel:       ch,
		Template:      template,
		Status:        entity.StatusSending,
		Attempt:       1,
		NextAttemptAt: now,
		ExpiresAt:     now.Add(time.Hour),
		CreatedAt:     now.Add(-time.Second),
	}
}

func (h *harness) process(t *testing.T, d entity.Delivery) {
	t.Helper()
	require.NoError(t, h.svc.Process(context.Background(), d, workerID))
}

func strp(s string) *string { return &s }

// ---- generic paths ---------------------------------------------------------

func TestExpiredRowIsMarkedExpiredWithFailedEvent(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	d.ExpiresAt = now.Add(-time.Minute)
	h.process(t, d)

	last := h.store.deliv.last()
	assert.Equal(t, "final", last.Op)
	assert.Equal(t, entity.StatusExpired, last.Status)
	assert.Equal(t, entity.ReasonExpired, last.Reason)
	assert.True(t, last.InTx, "mark + outbox in one transaction")
	assert.Equal(t, []string{entity.EventTypeFailed}, h.store.outbox.types())
	data := h.store.outbox.lastData()
	assert.Equal(t, "EXPIRED", data["final_status"])
	assert.Equal(t, "expired", data["reason_code"])
	assert.Equal(t, d.RequestID.String(), data["request_id"])
	assert.Equal(t, 0, h.contacts.contactN)
	assert.Empty(t, h.sender.sent)
}

func TestPausedLaneReleasesWithoutAttempt(t *testing.T) {
	h := newHarness(t)
	h.control.states = map[entity.Lane]entity.LaneState{
		entity.LaneBulk: {Lane: entity.LaneBulk, Email: entity.LanePaused, InApp: entity.LaneActive},
	}
	d := h.delivery(entity.LaneBulk, entity.ChannelEmail, "CART_ABANDONED")
	h.process(t, d)

	last := h.store.deliv.last()
	assert.Equal(t, "release", last.Op)
	assert.Equal(t, ReasonLanePaused, last.Reason)
	assert.Equal(t, now.Add(5*time.Second), last.Next)
	assert.Empty(t, h.store.outbox.types())

	// in_app of the same lane is still active.
	h.store.deliv.calls = nil
	h.store.prefs.byUser[*d.UserID] = &entity.Preference{UserID: *d.UserID, MarketingEnabled: true}
	inApp := d
	inApp.ID = uuid.New()
	inApp.Channel = entity.ChannelInApp
	h.process(t, inApp)
	assert.Equal(t, "delivered_in_app", h.store.deliv.last().Op)
}

func TestLaneStateErrorFailsOpen(t *testing.T) {
	h := newHarness(t)
	h.control.err = stderrors.New("ops_control unreachable")
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Equal(t, "sent", h.store.deliv.last().Op)
}

func TestBulkPreferenceRecheck(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneBulk, entity.ChannelEmail, "CART_ABANDONED")
	// No preference row → default marketing_enabled=false → SKIPPED.
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "final", last.Op)
	assert.Equal(t, entity.StatusSkipped, last.Status)
	assert.Equal(t, entity.ReasonPreferenceOff, last.Reason)
	assert.Empty(t, h.store.outbox.types(), "skips emit no event")
	assert.Equal(t, 0, h.contacts.contactN)
}

func TestAnonymisedRowIsSkipped(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	d.UserID = nil
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusSkipped, last.Status)
	assert.Equal(t, entity.ReasonUserDeleted, last.Reason)
}

func TestLeaseExpiredPolicyPerLane(t *testing.T) {
	cases := []struct {
		name     string
		lane     entity.Lane
		channel  entity.Channel
		template string
		wantOp   string
		wantDup  bool
	}{
		{"critical email is not resent", entity.LaneCritical, entity.ChannelEmail, "EMAIL_CHANGED", "final", false},
		{"transactional email resent possibly_duplicate", entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED", "sent", true},
		{"bulk email resent", entity.LaneBulk, entity.ChannelEmail, "CART_ABANDONED", "sent", false},
		{"critical in_app resent (safe)", entity.LaneCritical, entity.ChannelInApp, "EMAIL_CHANGED", "delivered_in_app", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			d := h.delivery(tc.lane, tc.channel, tc.template)
			d.Attempt = 2
			d.LastErrorCode = strp(entity.ReasonLeaseExpired)
			if tc.lane == entity.LaneBulk {
				h.store.prefs.byUser[*d.UserID] = &entity.Preference{UserID: *d.UserID, MarketingEnabled: true}
			}
			if tc.lane == entity.LaneCritical {
				authID := uuid.New()
				d.SourceRef = strp(authID.String())
				h.contacts.content = &entity.AuthContent{DeliveryID: authID, Recipient: pii.MustEmail("c@example.com"), Template: tc.template, TemplateData: []byte(`{"event":"EMAIL_CHANGED","changed_at":"2026-09-22T10:00:00Z"}`)}
			}
			h.process(t, d)
			last := h.store.deliv.last()
			assert.Equal(t, tc.wantOp, last.Op)
			if tc.wantOp == "final" {
				assert.Equal(t, entity.StatusFailed, last.Status)
				assert.Equal(t, entity.ReasonLeaseExpired, last.Reason)
				assert.Equal(t, []string{entity.EventTypeFailed}, h.store.outbox.types())
				assert.Empty(t, h.sender.sent)
			}
			if tc.wantOp == "sent" {
				assert.Equal(t, tc.wantDup, last.PossDup)
			}
		})
	}
}

// ---- in_app ----------------------------------------------------------------

func TestInAppHappyPath(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneTransactional, entity.ChannelInApp, "ORDER_CONFIRMED")
	tz := "Europe/Paris"
	h.store.prefs.byUser[*d.UserID] = &entity.Preference{UserID: *d.UserID, EmailEnabled: true, InAppEnabled: true, Timezone: &tz}
	h.process(t, d)

	require.Len(t, h.store.inbox.items, 1)
	item := h.store.inbox.items[0]
	assert.Equal(t, d.ID, item.ID)
	assert.Equal(t, *d.UserID, item.UserID)
	assert.Equal(t, "ORDER_CONFIRMED", item.Template)
	assert.Equal(t, "title ORDER_CONFIRMED", item.Title)
	assert.Equal(t, now, item.CreatedAt)

	last := h.store.deliv.last()
	assert.Equal(t, "delivered_in_app", last.Op)
	assert.Equal(t, "vORD", last.Version)
	assert.True(t, last.InTx)
	assert.Equal(t, []string{entity.EventTypeDelivered}, h.store.outbox.types())
	data := h.store.outbox.lastData()
	assert.Equal(t, "inbox", data["provider"])
	assert.Equal(t, "in_app", data["channel"])
	assert.Equal(t, []int64{1}, h.unread.adds)
	assert.Equal(t, 1, h.store.txCount)
	assert.Equal(t, "Europe/Paris", h.renderer.seenOpts.Timezone)
	assert.Equal(t, "vi", h.renderer.seenLoc)
	assert.JSONEq(t, `{"order_id":"o-1"}`, string(h.renderer.seenData))
	assert.Equal(t, 0, h.contacts.contactN, "in_app never calls Auth")
	assert.Equal(t, 0, h.contacts.contentN)
}

func TestInAppRenderErrorFails(t *testing.T) {
	h := newHarness(t)
	h.renderer.inAppErr = stderrors.New("missing field total")
	d := h.delivery(entity.LaneTransactional, entity.ChannelInApp, "ORDER_CONFIRMED")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusFailed, last.Status)
	assert.Equal(t, entity.ReasonRenderFailed, last.Reason)
	assert.Equal(t, []string{entity.EventTypeFailed}, h.store.outbox.types())
	assert.Empty(t, h.store.inbox.items)
	assert.Empty(t, h.unread.adds)
}

func TestInAppLostRaceRollsBack(t *testing.T) {
	h := newHarness(t)
	h.store.deliv.casFail = true
	d := h.delivery(entity.LaneTransactional, entity.ChannelInApp, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Empty(t, h.store.inbox.items)
	assert.Empty(t, h.store.outbox.types())
	assert.Empty(t, h.unread.adds)
}

func TestInAppUnreadErrorIsIgnored(t *testing.T) {
	h := newHarness(t)
	h.unread.err = stderrors.New("redis down")
	d := h.delivery(entity.LaneTransactional, entity.ChannelInApp, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Equal(t, "delivered_in_app", h.store.deliv.last().Op)
	assert.Len(t, h.store.inbox.items, 1)
}

// ---- email -----------------------------------------------------------------

func TestEmailHappyPath(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)

	require.Len(t, h.sender.sent, 1)
	msg := h.sender.sent[0]
	assert.Equal(t, "alice@example.com", msg.To.Raw())
	assert.Equal(t, d.ID, msg.DeliveryID)
	assert.Equal(t, d.ID.String(), msg.Content.Headers[constants.HeaderDeliveryID])

	last := h.store.deliv.last()
	assert.Equal(t, "sent", last.Op)
	assert.Equal(t, entity.ProviderMailpit, last.Provider)
	assert.Equal(t, "msg-1", last.MessageID)
	assert.Equal(t, "vORD", last.Version)
	assert.False(t, last.PossDup)
	assert.Equal(t, workerID, last.WorkerID)
	assert.Empty(t, h.store.outbox.types(), "SENT emits no event; DELIVERED comes from Track")
	assert.Equal(t, 1, h.breaker.successes)
	assert.Equal(t, 0, h.breaker.failures)
	assert.Equal(t, 1, h.daily.incr)
	assert.Equal(t, []bool{false}, h.budget.records)
	assert.Equal(t, 1, h.limiter.calls)
	assert.Equal(t, 5*time.Second, h.limiter.wait)
	assert.Equal(t, entity.LaneTransactional, h.contacts.lastLane)
	assert.Equal(t, *d.UserID, h.contacts.lastID)
	assert.Equal(t, "", h.renderer.seenOpts.UnsubscribeURL)
	assert.JSONEq(t, `{"order_id":"o-1"}`, string(h.renderer.seenData))
}

func TestEmailRetryRecordsRetryInBudget(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	d.Attempt = 3
	h.process(t, d)
	assert.Equal(t, []bool{true}, h.budget.records)
}

func TestEmailLostRaceAfterSendOnlyLogs(t *testing.T) {
	h := newHarness(t)
	h.store.deliv.casFail = true
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Len(t, h.sender.sent, 1)
	assert.Empty(t, h.budget.records)
}

func TestAuthTemplateUsesContentAPI(t *testing.T) {
	h := newHarness(t)
	authID := uuid.New()
	d := h.delivery(entity.LaneCritical, entity.ChannelEmail, "PASSWORD_RESET")
	d.SourceRef = strp(authID.String())
	h.store.reqs.byID[d.RequestID].TemplateData = nil
	h.contacts.content = &entity.AuthContent{
		DeliveryID:   authID,
		Recipient:    pii.MustEmail("reset@example.com"),
		Template:     "PASSWORD_RESET",
		ExpiresAt:    now.Add(15 * time.Minute),
		TemplateData: []byte(`{"reset_url":"https://x/reset?t=SECRET"}`),
	}
	h.process(t, d)

	assert.Equal(t, 1, h.contacts.contentN)
	assert.Equal(t, 0, h.contacts.contactN)
	assert.Equal(t, authID, h.contacts.lastID)
	assert.Equal(t, entity.LaneCritical, h.contacts.lastLane)
	require.Len(t, h.sender.sent, 1)
	assert.Equal(t, "reset@example.com", h.sender.sent[0].To.Raw())
	assert.JSONEq(t, `{"reset_url":"https://x/reset?t=SECRET"}`, string(h.renderer.seenData))
	assert.Equal(t, "sent", h.store.deliv.last().Op)
	// The secret never reaches a store write.
	for _, c := range h.store.deliv.calls {
		assert.NotContains(t, c.Detail, "SECRET")
	}
}

func TestAuthTemplateMismatchFails(t *testing.T) {
	h := newHarness(t)
	authID := uuid.New()
	d := h.delivery(entity.LaneCritical, entity.ChannelEmail, "PASSWORD_RESET")
	d.SourceRef = strp(authID.String())
	h.contacts.content = &entity.AuthContent{DeliveryID: authID, Recipient: pii.MustEmail("r@example.com"), Template: "REGISTRATION_VERIFICATION"}
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusFailed, last.Status)
	assert.Equal(t, entity.ReasonTemplateMismatch, last.Reason)
	assert.Empty(t, h.sender.sent)
}

func TestAuthTemplateWithoutSourceRefFails(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneCritical, entity.ChannelEmail, "PASSWORD_RESET")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusFailed, last.Status)
	assert.Equal(t, entity.ReasonNoRecipient, last.Reason)
	assert.Equal(t, 0, h.contacts.contentN)
}

func TestNoRecipientIsPermanent(t *testing.T) {
	cases := []struct {
		name string
		err  error
		code string
	}{
		{"404 contact", errors.NewPermanent(entity.ReasonNoRecipient, errors.ErrNoRecipient), entity.ReasonNoRecipient},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.contacts.contactErr = tc.err
			d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
			h.process(t, d)
			last := h.store.deliv.last()
			assert.Equal(t, "final", last.Op)
			assert.Equal(t, entity.StatusFailed, last.Status)
			assert.Equal(t, tc.code, last.Reason)
			assert.Equal(t, []string{entity.EventTypeFailed}, h.store.outbox.types())
			assert.Equal(t, tc.code, h.store.outbox.lastData()["reason_code"])
			assert.Empty(t, h.sender.sent)
		})
	}
}

func TestContentExpiredIsPermanent(t *testing.T) {
	h := newHarness(t)
	authID := uuid.New()
	d := h.delivery(entity.LaneCritical, entity.ChannelEmail, "PASSWORD_RESET")
	d.SourceRef = strp(authID.String())
	h.contacts.contentErr = errors.NewPermanent(entity.ReasonContentExpired, errors.ErrContentExpired)
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusFailed, last.Status)
	assert.Equal(t, entity.ReasonContentExpired, last.Reason)
	assert.Equal(t, []string{entity.EventTypeFailed}, h.store.outbox.types())
}

func TestAuthRetryableSchedulesRetryWithoutTouchingProviderBreaker(t *testing.T) {
	h := newHarness(t)
	h.contacts.contactErr = errors.NewRetryable(stderrors.New("auth 503"))
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "retry", last.Op)
	assert.Equal(t, ReasonAuthUnavailable, last.Reason)
	assert.Equal(t, 0, h.breaker.failures)
	assert.Empty(t, h.sender.sent)
}

func TestAuthBreakerOpenReleasesRow(t *testing.T) {
	h := newHarness(t)
	h.contacts.contactErr = errors.NewRetryable(errors.ErrBreakerOpen)
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "release", last.Op)
	assert.Equal(t, entity.ReasonBreakerOpen, last.Reason)
	assert.Equal(t, now.Add(5*time.Minute), last.Next)
}

func TestBulkUnverifiedIsSkipped(t *testing.T) {
	h := newHarness(t)
	h.contacts.contact = &entity.Contact{Email: pii.MustEmail("new@example.com"), EmailVerified: false}
	d := h.delivery(entity.LaneBulk, entity.ChannelEmail, "CART_ABANDONED")
	h.store.prefs.byUser[*d.UserID] = &entity.Preference{UserID: *d.UserID, MarketingEnabled: true}
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusSkipped, last.Status)
	assert.Equal(t, entity.ReasonEmailUnverified, last.Reason)
	assert.Empty(t, h.sender.sent)

	// Transactional still sends to an unverified address.
	h2 := newHarness(t)
	h2.contacts.contact = &entity.Contact{Email: pii.MustEmail("new@example.com"), EmailVerified: false}
	h2.process(t, h2.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED"))
	assert.Equal(t, "sent", h2.store.deliv.last().Op)
}

func TestBulkRenderGetsUnsubscribeURLAndDailyQuota(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneBulk, entity.ChannelEmail, "CART_ABANDONED")
	h.store.prefs.byUser[*d.UserID] = &entity.Preference{UserID: *d.UserID, MarketingEnabled: true}
	h.process(t, d)
	assert.Equal(t, "sent", h.store.deliv.last().Op)
	assert.Equal(t, "http://localhost:3000/profile/preferences", h.renderer.seenOpts.UnsubscribeURL)
	assert.Equal(t, "Asia/Ho_Chi_Minh", h.renderer.seenOpts.Timezone)

	// At 80 % of Max24HourSend bulk is released for an hour; transactional continues.
	h.daily.count = 800
	h.store.deliv.calls = nil
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "release", last.Op)
	assert.Equal(t, ReasonDailyQuota, last.Reason)
	assert.Equal(t, now.Add(time.Hour), last.Next)

	h.store.deliv.calls = nil
	h.process(t, h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED"))
	assert.Equal(t, "sent", h.store.deliv.last().Op)
}

func TestSuppressionHashEqualSkips(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.store.supp.rows = []entity.Suppression{{
		ID: uuid.New(), UserID: d.UserID, EmailHash: pii.HashEmail("Alice@Example.com"), Reason: entity.SuppressionHardBounce,
	}}
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusSkipped, last.Status)
	assert.Equal(t, entity.ReasonSuppressed, last.Reason)
	assert.Empty(t, h.sender.sent)
	assert.Empty(t, h.store.supp.superseded)
}

func TestSuppressionHashDifferentSupersedesAndSends(t *testing.T) {
	h := newHarness(t)
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	old := uuid.New()
	expired := uuid.New()
	past := now.Add(-time.Hour)
	h.store.supp.rows = []entity.Suppression{
		{ID: old, UserID: d.UserID, EmailHash: pii.HashEmail("old@example.com"), Reason: entity.SuppressionHardBounce},
		{ID: expired, UserID: d.UserID, EmailHash: pii.HashEmail("alice@example.com"), Reason: entity.SuppressionSoftBounce, Until: &past},
	}
	h.process(t, d)
	assert.Equal(t, "sent", h.store.deliv.last().Op)
	assert.Equal(t, []uuid.UUID{old}, h.store.supp.superseded, "only the active row with another hash is superseded")
}

func TestCriticalIgnoresSuppression(t *testing.T) {
	h := newHarness(t)
	authID := uuid.New()
	d := h.delivery(entity.LaneCritical, entity.ChannelEmail, "EMAIL_CHANGED")
	d.SourceRef = strp(authID.String())
	h.contacts.content = &entity.AuthContent{DeliveryID: authID, Recipient: pii.MustEmail("alice@example.com"), Template: "EMAIL_CHANGED", TemplateData: []byte(`{}`)}
	h.store.supp.rows = []entity.Suppression{{ID: uuid.New(), UserID: d.UserID, EmailHash: pii.HashEmail("alice@example.com"), Reason: entity.SuppressionComplaint}}
	h.process(t, d)
	assert.Equal(t, "sent", h.store.deliv.last().Op)
}

func TestEmailRenderErrorFails(t *testing.T) {
	h := newHarness(t)
	h.renderer.emailErr = stderrors.New("template: missing total")
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, entity.StatusFailed, last.Status)
	assert.Equal(t, entity.ReasonRenderFailed, last.Reason)
	assert.Equal(t, 0, h.limiter.calls, "render happens before the rate limiter")
	assert.Empty(t, h.sender.sent)
}

func TestRateLimitReleasesRow(t *testing.T) {
	h := newHarness(t)
	h.limiter.err = errors.ErrRateLimitWait
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "release", last.Op)
	assert.Equal(t, entity.ReasonRateLimited, last.Reason)
	assert.Equal(t, now, last.Next)
	assert.Empty(t, h.sender.sent)
	assert.Empty(t, h.budget.records)
}

func TestBreakerOpenReleasesRowPerLane(t *testing.T) {
	cases := []struct {
		lane     entity.Lane
		template string
		want     time.Duration
	}{
		{entity.LaneCritical, "EMAIL_CHANGED", 30 * time.Second},
		{entity.LaneTransactional, "ORDER_CONFIRMED", 5 * time.Minute},
		{entity.LaneBulk, "CART_ABANDONED", 30 * time.Minute},
	}
	for _, tc := range cases {
		t.Run(string(tc.lane), func(t *testing.T) {
			h := newHarness(t)
			h.breaker.allow = false
			d := h.delivery(tc.lane, entity.ChannelEmail, tc.template)
			h.store.prefs.byUser[*d.UserID] = &entity.Preference{UserID: *d.UserID, MarketingEnabled: true}
			h.process(t, d)
			last := h.store.deliv.last()
			assert.Equal(t, "release", last.Op)
			assert.Equal(t, entity.ReasonBreakerOpen, last.Reason)
			assert.Equal(t, now.Add(tc.want), last.Next)
			assert.Equal(t, 0, h.contacts.contactN+h.contacts.contentN, "no Auth call while the provider breaker is open")
			assert.Empty(t, h.sender.sent)
		})
	}
}

func TestBreakerOpenDoesNotBlockInApp(t *testing.T) {
	h := newHarness(t)
	h.breaker.allow = false
	d := h.delivery(entity.LaneTransactional, entity.ChannelInApp, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Equal(t, "delivered_in_app", h.store.deliv.last().Op)
}

func TestRetryableSendErrorSchedulesRetryWithBackoff(t *testing.T) {
	h := newHarness(t)
	h.sender.err = errors.NewRetryable(stderrors.New("smtp 421 try later"))
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	d.Attempt = 3
	h.process(t, d)

	last := h.store.deliv.last()
	assert.Equal(t, "retry", last.Op)
	assert.Equal(t, entity.ReasonProviderError, last.Reason)
	assert.Contains(t, last.Detail, "smtp 421")
	// base 30 s × 2^(3-1) = 120 s + 0.5 × 30 s jitter = 135 s
	assert.Equal(t, now.Add(135*time.Second), last.Next)
	assert.Equal(t, 1, h.breaker.failures)
	assert.Equal(t, 0, h.breaker.successes)
	assert.Equal(t, []bool{true}, h.budget.records)
	assert.Equal(t, 0, h.daily.incr)
	assert.Empty(t, h.store.outbox.types())
}

func TestUnclassifiedSendErrorIsTreatedRetryable(t *testing.T) {
	h := newHarness(t)
	h.sender.err = stderrors.New("dial tcp: connection reset")
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Equal(t, "retry", h.store.deliv.last().Op)
	assert.Equal(t, 1, h.breaker.failures)
}

func TestRetryBudgetDefers(t *testing.T) {
	h := newHarness(t)
	h.sender.err = errors.NewRetryable(stderrors.New("503"))
	h.budget.ratio = 0.35
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "retry", last.Op)
	assert.Equal(t, ReasonRetryBudgetDefer, last.Reason)
	assert.Equal(t, now.Add(15*time.Minute+15*time.Second), last.Next)
}

func TestMaxAttemptsFails(t *testing.T) {
	h := newHarness(t)
	h.sender.err = errors.NewRetryable(stderrors.New("503"))
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	d.Attempt = 8
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "final", last.Op)
	assert.Equal(t, entity.StatusFailed, last.Status)
	assert.Equal(t, entity.ReasonProviderError, last.Reason)
	assert.Equal(t, []string{entity.EventTypeFailed}, h.store.outbox.types())
	data := h.store.outbox.lastData()
	assert.Equal(t, float64(8), data["attempts"])
	assert.Equal(t, "mailpit", data["provider"])
}

func TestPermanentSendErrorFailsAndCountsAsProviderAnswer(t *testing.T) {
	h := newHarness(t)
	h.sender.err = errors.NewPermanent(entity.ReasonProviderError, stderrors.New("550 mailbox unavailable"))
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	last := h.store.deliv.last()
	assert.Equal(t, "final", last.Op)
	assert.Equal(t, entity.StatusFailed, last.Status)
	assert.Equal(t, entity.ReasonProviderError, last.Reason)
	assert.Contains(t, last.Detail, "550")
	assert.Equal(t, 1, h.breaker.successes)
	assert.Equal(t, 0, h.breaker.failures)
	assert.Equal(t, []string{entity.EventTypeFailed}, h.store.outbox.types())
}

func TestLastErrorIsTruncated(t *testing.T) {
	h := newHarness(t)
	long := make([]byte, 2000)
	for i := range long {
		long[i] = 'x'
	}
	h.sender.err = errors.NewRetryable(stderrors.New(string(long)))
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Len(t, h.store.deliv.last().Detail, maxErrorLen)
}

func TestInfrastructureErrorIsReturned(t *testing.T) {
	h := newHarness(t)
	h.store.deliv.err = stderrors.New("pg: connection refused")
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	err := h.svc.Process(context.Background(), d, workerID)
	require.Error(t, err)
	assert.Len(t, h.sender.sent, 1, "the send happened; the row stays SENDING until the reaper")
}

func TestPreferenceLocaleWinsOverContactLocale(t *testing.T) {
	h := newHarness(t)
	h.contacts.contact = &entity.Contact{Email: pii.MustEmail("a@example.com"), EmailVerified: true, Locale: "en"}
	d := h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED")
	h.process(t, d)
	assert.Equal(t, "en", h.renderer.seenLoc)

	loc := "vi"
	h.store.prefs.byUser[*d.UserID] = &entity.Preference{UserID: *d.UserID, Locale: &loc}
	h.process(t, d)
	assert.Equal(t, "vi", h.renderer.seenLoc)
}

// ---- backoff ---------------------------------------------------------------

func TestBackoff(t *testing.T) {
	base, maxD, jitter := 30*time.Second, time.Hour, 30*time.Second
	zero := func() float64 { return 0 }
	one := func() float64 { return 0.999 }
	assert.Equal(t, 30*time.Second, Backoff(1, base, maxD, jitter, zero))
	assert.Equal(t, 60*time.Second, Backoff(2, base, maxD, jitter, zero))
	assert.Equal(t, 120*time.Second, Backoff(3, base, maxD, jitter, zero))
	assert.Equal(t, 8*time.Minute, Backoff(5, base, maxD, jitter, zero))
	assert.Equal(t, time.Hour, Backoff(8, base, maxD, jitter, zero), "capped at max")
	assert.Equal(t, time.Hour, Backoff(64, base, maxD, jitter, zero), "no overflow on huge attempts")
	assert.Equal(t, 30*time.Second, Backoff(0, base, maxD, jitter, zero), "attempt < 1 treated as 1")
	j := Backoff(1, base, maxD, jitter, one)
	assert.Greater(t, j, 30*time.Second)
	assert.Less(t, j, 60*time.Second)
	assert.Equal(t, 30*time.Second, Backoff(1, base, maxD, 0, one), "no jitter")
	assert.Equal(t, 30*time.Second, Backoff(1, 0, 0, 0, nil), "defaults")
}
