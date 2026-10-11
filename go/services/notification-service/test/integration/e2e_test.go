//go:build integration

package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/infrastructure/smtp"
)

const (
	typeRequested = "com.ecommerce.notification.requested.v1"
	typeCart      = "com.ecommerce.cart.abandoned.v1"
	typeReset     = "com.ecommerce.auth.password-reset-requested.v1"
	typeDeleted   = "com.ecommerce.auth.user-deleted.v1"
)

func (e *e2eEnv) sendOrder(t *testing.T, user uuid.UUID, o requestOpts) (string, *requestRow) {
	t.Helper()
	id, ev := requestEvent(user, o)
	e.publish(t, e.cfg.Kafka.Sources.Transactional.Topic, o.subject, id, typeRequested, ev)
	return id, e.waitRequest(t, "/services/order", id)
}

// IT-IDEM-01: the same CloudEvent three times → one request, two deliveries,
// one email, one inbox item, one delivered outcome per channel.
func TestE2E_IdempotencyLayerA(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := fmt.Sprint(2000000 + time.Now().UnixNano()%899999)
	id, ev := requestEvent(user, requestOpts{orderID: orderID, dedupKey: "order_confirmed:" + orderID})
	for i := 0; i < 3; i++ {
		e.publish(t, e.cfg.Kafka.Sources.Transactional.Topic, "orders/"+orderID, id, typeRequested, ev)
	}
	req := e.waitRequest(t, "/services/order", id)
	assert.Equal(t, "DECIDED", req.Decision)

	mail := e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSent)
	inapp := e.waitDeliveryStatus(t, req.ID, entity.ChannelInApp, entity.StatusDelivered)
	assert.Equal(t, entity.ProviderMailpit, *mail.Provider)
	assert.Equal(t, smtp.MessageID(mail.ID, e.cfg.Email.FromAddress), *mail.ProviderMessageID)
	assert.NotEmpty(t, *mail.TemplateVersion)
	assert.Equal(t, 1, mail.Attempt)
	assert.False(t, mail.PossiblyDuplicate)

	// Give the consumer time to see the two duplicates, then assert nothing multiplied.
	time.Sleep(1500 * time.Millisecond)
	assert.Equal(t, 1, e.countRequests(t, "/services/order", id), "layer A: one request row")
	assert.Len(t, e.deliveries(t, req.ID), 2)
	msgs := e.mailpitMessagesTo(t, email)
	require.Len(t, msgs, 1, "exactly one email")
	detail := e.mailpitDetail(t, msgs[0].ID)
	assert.Contains(t, detail.Subject, "ORD-"+orderID)
	assert.Equal(t, mail.ID.String(), strings.Join(detail.Headers[constants.HeaderDeliveryID], ""))
	assert.Contains(t, detail.HTML, "Áo thun cotton")
	assert.NotContains(t, detail.Headers, "List-Unsubscribe", "transactional mail carries no unsubscribe header")

	item, err := testStore.Inbox().Get(context.Background(), user, inapp.ID)
	require.NoError(t, err)
	assert.Equal(t, "ORDER_CONFIRMED", item.Template)
	assert.NotNil(t, item.DeepLink)
	// The Redis counter is only kept once a read initialised it (09 §7); the
	// source of truth is inbox_item.
	unread, err := testStore.Inbox().CountUnread(context.Background(), user)
	require.NoError(t, err)
	assert.EqualValues(t, 1, unread)

	waitFor(t, 20*time.Second, "delivered outcome for in-app", func() bool {
		return len(e.outcomes.find(entity.EventTypeDelivered, inapp.ID)) == 1
	})
	evt := e.outcomes.find(entity.EventTypeDelivered, inapp.ID)[0]
	var data map[string]any
	require.NoError(t, json.Unmarshal(evt.Data, &data))
	assert.Equal(t, req.ID.String(), data["request_id"], "request_id is the internal request id")
	assert.Equal(t, "inbox", data["provider"])
	assert.Equal(t, user.String(), data["user_id"])
	assert.Equal(t, "orders/"+orderID, data["subject"])
	assert.Equal(t, "/services/notification", evt.Source)
	assert.Equal(t, "e2e-"+id, evt.CorrelationID)
}

// IT-IDEM-02: two events with different ids and the same dedup_key → the
// second is SKIPPED (duplicate) on both channels; still one email.
func TestE2E_IdempotencyLayerB(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := fmt.Sprint(2100000 + time.Now().UnixNano()%899999)
	key := "order_confirmed:" + orderID
	_, first := e.sendOrder(t, user, requestOpts{orderID: orderID, dedupKey: key})
	e.waitDeliveryStatus(t, first.ID, entity.ChannelEmail, entity.StatusSent)
	_, second := e.sendOrder(t, user, requestOpts{orderID: orderID, dedupKey: key})
	assert.Equal(t, "SKIPPED", second.Decision)
	assert.Equal(t, entity.ReasonDuplicate, *second.DecisionReason)
	for ch, d := range e.deliveries(t, second.ID) {
		assert.Equal(t, entity.StatusSkipped, d.Status, ch)
		assert.Equal(t, entity.ReasonDuplicate, *d.SkipReason, ch)
	}
	time.Sleep(time.Second)
	assert.Len(t, e.mailpitMessagesTo(t, email), 1)
}

// IT-IDEM-03 + IT-PREF-02: two PASSWORD_RESET requests with different
// delivery ids are both sent — even with email_enabled = false — and the
// secret comes from the Auth content API, never from Kafka.
func TestE2E_AuthPasswordReset(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	e.setPreferences(t, user, false, true, false)

	var reqs []*requestRow
	for i := 0; i < 2; i++ {
		deliveryID := uuid.New()
		e.auth.setContent(deliveryID, fakeContent{Email: email, Template: "PASSWORD_RESET", ExpiresAt: time.Now().Add(15 * time.Minute),
			TemplateData: map[string]any{"reset_url": "https://shop.example.com/reset?token=tok-" + deliveryID.String()[:8]}})
		id, ev := authResetEvent(user, deliveryID, time.Now().Add(15*time.Minute))
		e.publish(t, e.cfg.Kafka.Sources.Auth.Topic, user.String(), id, typeReset, ev)
		req := e.waitRequest(t, "/services/auth", id)
		assert.Equal(t, "DECIDED", req.Decision)
		assert.Equal(t, "PASSWORD_RESET", *req.Template)
		reqs = append(reqs, req)
	}
	for _, req := range reqs {
		d := e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSent)
		assert.Equal(t, entity.LaneCritical, d.Lane)
		_, hasInApp := e.deliveries(t, req.ID)[entity.ChannelInApp]
		assert.False(t, hasInApp, "PASSWORD_RESET is email-only")
	}
	waitFor(t, 20*time.Second, "two reset emails", func() bool { return len(e.mailpitMessagesTo(t, email)) == 2 })
	detail := e.mailpitDetail(t, e.mailpitMessagesTo(t, email)[0].ID)
	assert.Contains(t, detail.HTML, "https://shop.example.com/reset?token=tok-")
	var td *string
	require.NoError(t, testPool.QueryRow(context.Background(), `SELECT template_data::text FROM notification_request WHERE id = $1`, reqs[0].ID).Scan(&td))
	assert.Nil(t, td, "secret template_data is never persisted")
}

// IT-PREF-01: email_enabled = false → no email, in-app still delivered.
func TestE2E_PreferenceEmailOff(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	e.setPreferences(t, user, false, true, false)
	_, req := e.sendOrder(t, user, requestOpts{dedupKey: "order_confirmed:" + fmt.Sprint(2200000+time.Now().UnixNano()%899999)})
	assert.Equal(t, "DECIDED", req.Decision)
	mail := e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSkipped)
	assert.Equal(t, entity.ReasonPreferenceOff, *mail.SkipReason)
	e.waitDeliveryStatus(t, req.ID, entity.ChannelInApp, entity.StatusDelivered)
	time.Sleep(time.Second)
	assert.Empty(t, e.mailpitMessagesTo(t, email))
}

// IT-PREF-03/04/06 + bulk headers of IT-SEND-01: CART_ABANDONED without
// marketing consent is skipped; with consent it is sent with List-Unsubscribe
// and capped at two per day.
func TestE2E_CartAbandonedPolicies(t *testing.T) {
	e := e2e(t)
	quiet, _ := newUser(e) // no preference row → marketing defaults to false
	id, ev := cartEvent(quiet, "cart-"+uuid.NewString()[:8], 1)
	e.publish(t, e.cfg.Kafka.Sources.Cart.Topic, quiet.String(), id, typeCart, ev)
	req := e.waitRequest(t, "/services/cart", id)
	assert.Equal(t, "SKIPPED", req.Decision)
	assert.Equal(t, entity.ReasonAllChannelsOff, *req.DecisionReason)
	for _, d := range e.deliveries(t, req.ID) {
		assert.Equal(t, entity.StatusSkipped, d.Status)
	}

	optIn, email := newUser(e)
	e.setPreferences(t, optIn, true, true, true)
	var decided []*requestRow
	var capped *requestRow
	for i := 1; i <= 3; i++ {
		id, ev := cartEvent(optIn, "cart-"+uuid.NewString()[:8], i)
		e.publish(t, e.cfg.Kafka.Sources.Cart.Topic, optIn.String(), id, typeCart, ev)
		r := e.waitRequest(t, "/services/cart", id)
		if i < 3 {
			assert.Equal(t, "DECIDED", r.Decision, "cart %d", i)
			decided = append(decided, r)
		} else {
			capped = r
		}
	}
	require.NotNil(t, capped)
	assert.Equal(t, "SKIPPED", capped.Decision)
	assert.Equal(t, entity.ReasonFrequencyCap, *capped.DecisionReason)
	for _, r := range decided {
		d := e.waitDeliveryStatus(t, r.ID, entity.ChannelEmail, entity.StatusSent)
		assert.Equal(t, entity.LaneBulk, d.Lane)
		e.waitDeliveryStatus(t, r.ID, entity.ChannelInApp, entity.StatusDelivered)
	}
	waitFor(t, 20*time.Second, "two cart emails", func() bool { return len(e.mailpitMessagesTo(t, email)) == 2 })
	detail := e.mailpitDetail(t, e.mailpitMessagesTo(t, email)[0].ID)
	assert.Equal(t, "<"+e.cfg.Email.UnsubscribeURL+">", strings.Join(detail.Headers["List-Unsubscribe"], ""))
	assert.Contains(t, detail.HTML, e.cfg.Frontend.BaseURL+e.cfg.Frontend.CartPath)
}

// IT-CTR-01..04: producer contract errors end as FAILED request rows with
// the documented reason codes, no deliveries, nothing sent.
func TestE2E_ProducerContractErrors(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := fmt.Sprint(2300000 + time.Now().UnixNano()%899999)
	cases := []struct {
		name   string
		topic  string
		opts   requestOpts
		reason string
	}{
		{"unknown template", e.cfg.Kafka.Sources.Transactional.Topic, requestOpts{template: "FOO", dedupKey: "foo:1", orderID: orderID}, entity.ReasonUnknownTemplate},
		{"lane mismatch", e.cfg.Kafka.Sources.Bulk.Topic, requestOpts{dedupKey: "order_confirmed:" + orderID, orderID: orderID}, entity.ReasonLaneMismatch},
		{"extra email field", e.cfg.Kafka.Sources.Transactional.Topic, requestOpts{dedupKey: "order_confirmed:" + orderID, orderID: orderID,
			templateData: func() map[string]any { m := orderTemplateData(orderID, "x"); m["email"] = "leak@example.com"; return m }()}, entity.ReasonInvalidTemplateData},
		{"missing dedup_key", e.cfg.Kafka.Sources.Transactional.Topic, requestOpts{orderID: orderID}, entity.ReasonMissingDedupKey},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			id, ev := requestEvent(user, tc.opts)
			e.publish(t, tc.topic, "orders/"+orderID, id, typeRequested, ev)
			req := e.waitRequest(t, "/services/order", id)
			assert.Equal(t, "FAILED", req.Decision)
			assert.Equal(t, tc.reason, *req.DecisionReason)
			assert.Empty(t, e.deliveries(t, req.ID))
		})
	}
	time.Sleep(time.Second)
	assert.Empty(t, e.mailpitMessagesTo(t, email))
}

// IT-SEND-01 (injection): template_data from another service is data, never markup.
func TestE2E_InjectionNeutralised(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := fmt.Sprint(2400000 + time.Now().UnixNano()%899999)
	td := orderTemplateData(orderID, "<script>alert(1)</script>")
	_, req := e.sendOrder(t, user, requestOpts{orderID: orderID, dedupKey: "order_confirmed:" + orderID, templateData: td})
	e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSent)
	waitFor(t, 20*time.Second, "email", func() bool { return len(e.mailpitMessagesTo(t, email)) == 1 })
	detail := e.mailpitDetail(t, e.mailpitMessagesTo(t, email)[0].ID)
	assert.NotContains(t, strings.ToLower(detail.HTML), "<script")
	assert.Contains(t, detail.HTML, "&lt;script&gt;")
}

// IT-SUPP-01/02 + tracked state ranking: Delivery then a permanent Bounce
// through the SQS path; the bounce suppresses the user for transactional
// mail but not for critical; a changed address supersedes the suppression.
func TestE2E_TrackBounceAndSuppression(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := fmt.Sprint(2500000 + time.Now().UnixNano()%899999)
	_, req := e.sendOrder(t, user, requestOpts{orderID: orderID, dedupKey: "order_confirmed:" + orderID})
	mail := e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSent)
	msgID := *mail.ProviderMessageID

	// Delivery → DELIVERED + delivered outcome.
	delivered := sesEvent("Delivery", msgID, mail.ID, email, "")
	e.sqsQueue.push(snsEnvelope(delivered))
	n, err := e.poller.PollOnce(context.Background())
	require.NoError(t, err)
	require.Equal(t, 1, n)
	e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusDelivered)
	waitFor(t, 20*time.Second, "delivered outcome", func() bool { return len(e.outcomes.find(entity.EventTypeDelivered, mail.ID)) == 1 })

	// The same SES event redelivered by SNS is a duplicate (provider_event_dedup).
	e.sqsQueue.push(snsEnvelope(delivered))
	_, err = e.poller.PollOnce(context.Background())
	require.NoError(t, err)
	var eventRows int
	require.NoError(t, testPool.QueryRow(context.Background(), `SELECT count(*) FROM provider_event WHERE provider_message_id = $1`, msgID).Scan(&eventRows))
	assert.Equal(t, 1, eventRows, "IT-IDEM-05: duplicate SES event is not stored twice")

	// Late Bounce after DELIVERED still applies (higher rank) and suppresses.
	e.sqsQueue.push(snsEnvelope(sesEvent("Bounce", msgID, mail.ID, email, "Permanent")))
	_, err = e.poller.PollOnce(context.Background())
	require.NoError(t, err)
	e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusBounced)
	waitFor(t, 20*time.Second, "suppression", func() bool {
		rows, _ := testStore.Suppressions().ActiveByUser(context.Background(), user)
		return len(rows) == 1
	})
	rows, _ := testStore.Suppressions().ActiveByUser(context.Background(), user)
	assert.Equal(t, entity.SuppressionHardBounce, rows[0].Reason)
	assert.Nil(t, rows[0].Until)
	assert.Len(t, rows[0].EmailHash, 64)
	waitFor(t, 20*time.Second, "bounced outcome", func() bool { return len(e.outcomes.find(entity.EventTypeBounced, mail.ID)) == 1 })
	var bounced map[string]any
	require.NoError(t, json.Unmarshal(e.outcomes.find(entity.EventTypeBounced, mail.ID)[0].Data, &bounced))
	assert.Equal(t, "permanent", bounced["bounce_type"])
	assert.Equal(t, true, bounced["suppressed"])

	// No raw address anywhere in provider_event.raw (IT-TRACK-01).
	var leaked int
	require.NoError(t, testPool.QueryRow(context.Background(), `SELECT count(*) FROM provider_event WHERE raw::text ILIKE '%' || $1 || '%'`, email).Scan(&leaked))
	assert.Equal(t, 0, leaked)

	// Next transactional mail: email suppressed, in-app still goes.
	orderID2 := fmt.Sprint(2600000 + time.Now().UnixNano()%899999)
	_, req2 := e.sendOrder(t, user, requestOpts{orderID: orderID2, dedupKey: "order_confirmed:" + orderID2})
	d := e.waitDeliveryStatus(t, req2.ID, entity.ChannelEmail, entity.StatusSkipped)
	assert.Equal(t, entity.ReasonSuppressed, *d.SkipReason)
	e.waitDeliveryStatus(t, req2.ID, entity.ChannelInApp, entity.StatusDelivered)

	// Critical ignores suppression.
	deliveryID := uuid.New()
	e.auth.setContent(deliveryID, fakeContent{Email: email, Template: "PASSWORD_RESET", ExpiresAt: time.Now().Add(10 * time.Minute),
		TemplateData: map[string]any{"reset_url": "https://shop.example.com/reset?token=x"}})
	id, ev := authResetEvent(user, deliveryID, time.Now().Add(10*time.Minute))
	e.publish(t, e.cfg.Kafka.Sources.Auth.Topic, user.String(), id, typeReset, ev)
	critical := e.waitRequest(t, "/services/auth", id)
	e.waitDeliveryStatus(t, critical.ID, entity.ChannelEmail, entity.StatusSent)

	// IT-SUPP-02: the user changes address → hash differs → suppression superseded, mail sent.
	newEmail := "new-" + email
	e.auth.setContact(user, newEmail, true)
	require.NoError(t, e.contacts.ForgetContact(context.Background(), user))
	orderID3 := fmt.Sprint(2700000 + time.Now().UnixNano()%899999)
	_, req3 := e.sendOrder(t, user, requestOpts{orderID: orderID3, dedupKey: "order_confirmed:" + orderID3})
	e.waitDeliveryStatus(t, req3.ID, entity.ChannelEmail, entity.StatusSent)
	waitFor(t, 20*time.Second, "mail to the new address", func() bool { return len(e.mailpitMessagesTo(t, newEmail)) == 1 })
	var superseded int
	require.NoError(t, testPool.QueryRow(context.Background(), `SELECT count(*) FROM suppression WHERE user_id = $1 AND superseded_at IS NOT NULL`, user).Scan(&superseded))
	assert.Equal(t, 1, superseded)
}

// IT-SUPP-03: a complaint suppresses, switches marketing off locally and
// publishes complaint-received for User Service.
func TestE2E_TrackComplaint(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	e.setPreferences(t, user, true, true, true)
	orderID := fmt.Sprint(2800000 + time.Now().UnixNano()%899999)
	_, req := e.sendOrder(t, user, requestOpts{orderID: orderID, dedupKey: "order_confirmed:" + orderID})
	mail := e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSent)

	e.sqsQueue.push(snsEnvelope(sesEvent("Complaint", *mail.ProviderMessageID, mail.ID, email, "")))
	_, err := e.poller.PollOnce(context.Background())
	require.NoError(t, err)
	waitFor(t, 20*time.Second, "marketing disabled", func() bool {
		p, err := testStore.Preferences().Get(context.Background(), user)
		return err == nil && !p.MarketingEnabled
	})
	rows, _ := testStore.Suppressions().ActiveByUser(context.Background(), user)
	require.Len(t, rows, 1)
	assert.Equal(t, entity.SuppressionComplaint, rows[0].Reason)
	waitFor(t, 20*time.Second, "complaint outcome", func() bool { return len(e.outcomes.find(entity.EventTypeComplaintReceived, mail.ID)) == 1 })
	var data map[string]any
	require.NoError(t, json.Unmarshal(e.outcomes.find(entity.EventTypeComplaintReceived, mail.ID)[0].Data, &data))
	assert.Equal(t, true, data["marketing_disabled"])
	d, _ := testStore.Deliveries().GetByID(context.Background(), mail.ID)
	assert.Equal(t, entity.StatusSent, d.Status, "a complaint does not change the delivery status")
}

// IT-DEL-01: USER_DELETED anonymises, deletes and skips in one pass.
func TestE2E_UserDeleted(t *testing.T) {
	e := e2e(t)
	user, _ := newUser(e)
	e.setPreferences(t, user, true, true, true)
	orderID := fmt.Sprint(2900000 + time.Now().UnixNano()%899999)
	_, req := e.sendOrder(t, user, requestOpts{orderID: orderID, dedupKey: "order_confirmed:" + orderID})
	e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSent)
	e.waitDeliveryStatus(t, req.ID, entity.ChannelInApp, entity.StatusDelivered)

	// A row still waiting in the bulk queue (far-future next_attempt_at so no worker claims it).
	pending := newDelivery(user, entity.LaneBulk, entity.ChannelEmail)
	pending.NextAttemptAt = time.Now().Add(time.Hour)
	require.NoError(t, testStore.Deliveries().Insert(context.Background(), pending))
	require.NoError(t, e.redis.Set(context.Background(), e.keys.FreqCap(user, "2026-09-22"), 1, time.Hour).Err())

	e.publish(t, e.cfg.Kafka.Sources.UserLifecycle.Topic, user.String(), uuid.NewString(), typeDeleted, userDeletedEvent(user))

	waitFor(t, 30*time.Second, "anonymisation", func() bool {
		var n int
		_ = testPool.QueryRow(context.Background(), `SELECT count(*) FROM notification_request WHERE user_id = $1`, user).Scan(&n)
		return n == 0
	})
	for _, q := range []string{
		`SELECT count(*) FROM notification_delivery WHERE user_id = $1`,
		`SELECT count(*) FROM inbox_item WHERE user_id = $1`,
		`SELECT count(*) FROM user_preference WHERE user_id = $1`,
		`SELECT count(*) FROM suppression WHERE user_id = $1`,
	} {
		var n int
		require.NoError(t, testPool.QueryRow(context.Background(), q, user).Scan(&n))
		assert.Equal(t, 0, n, q)
	}
	var leftover int
	require.NoError(t, testPool.QueryRow(context.Background(),
		`SELECT count(*) FROM notification_request WHERE id = $1 AND (subject IS NOT NULL OR template_data IS NOT NULL OR user_id IS NOT NULL OR dedup_key IS NOT NULL OR source_ref IS NOT NULL)`, req.ID).Scan(&leftover))
	assert.Equal(t, 0, leftover, "request anonymised, including the keys that embed the order id")
	var deliveryLeft int
	require.NoError(t, testPool.QueryRow(context.Background(),
		`SELECT count(*) FROM notification_delivery WHERE request_id = $1 AND (dedup_key IS NOT NULL OR source_ref IS NOT NULL OR subject IS NOT NULL)`, req.ID).Scan(&deliveryLeft))
	assert.Equal(t, 0, deliveryLeft, "deliveries lose dedup_key, source_ref and subject")
	skipped, err := testStore.Deliveries().GetByID(context.Background(), pending.ID)
	require.NoError(t, err)
	assert.Equal(t, entity.StatusSkipped, skipped.Status)
	assert.Equal(t, entity.ReasonUserDeleted, *skipped.SkipReason)
	assert.Nil(t, skipped.UserID)
	for _, key := range []string{e.keys.Unread(user), e.keys.Contact(user), e.keys.FreqCap(user, "2026-09-22")} {
		n, err := e.redis.Exists(context.Background(), key).Result()
		require.NoError(t, err)
		assert.EqualValues(t, 0, n, key)
	}
}

// Ops switch through the shared control table: a paused lane keeps
// accepting requests but stops sending until resumed (IT-LANE-02).
func TestE2E_PauseLane(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	e.setPreferences(t, user, true, true, true)
	require.NoError(t, e.control.SetLaneState(context.Background(), entity.LaneState{Lane: entity.LaneBulk, Email: entity.LanePaused, InApp: entity.LaneActive, UpdatedAt: time.Now()}))
	t.Cleanup(func() {
		_ = e.control.SetLaneState(context.Background(), entity.DefaultLaneState(entity.LaneBulk, time.Now()))
	})
	time.Sleep(2 * e.cfg.Deliver.ControlPollInterval)

	id, ev := cartEvent(user, "cart-"+uuid.NewString()[:8], 1)
	e.publish(t, e.cfg.Kafka.Sources.Cart.Topic, user.String(), id, typeCart, ev)
	req := e.waitRequest(t, "/services/cart", id)
	assert.Equal(t, "DECIDED", req.Decision)
	e.waitDeliveryStatus(t, req.ID, entity.ChannelInApp, entity.StatusDelivered)
	time.Sleep(2 * time.Second)
	d := e.deliveries(t, req.ID)[entity.ChannelEmail]
	assert.Contains(t, []entity.DeliveryStatus{entity.StatusPending, entity.StatusRetry}, d.Status, "email waits while the lane is paused")
	assert.Empty(t, e.mailpitMessagesTo(t, email))

	require.NoError(t, e.control.SetLaneState(context.Background(), entity.DefaultLaneState(entity.LaneBulk, time.Now())))
	e.waitDeliveryStatus(t, req.ID, entity.ChannelEmail, entity.StatusSent)
}
