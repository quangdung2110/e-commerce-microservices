//go:build integration

package integration

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// Order ordering, sequence contract and deleted-user tombstones end to end
// (15-verification.md §2.3 IT-CTR-07…09, §2.4 IT-ORD-01…06, §2.7 IT-DEL-02).

func counter(t *testing.T, c prometheus.Counter) float64 {
	t.Helper()
	var m dto.Metric
	require.NoError(t, c.Write(&m))
	return m.Counter.GetValue()
}

func newOrderID(base int64) string {
	return fmt.Sprint(base + time.Now().UnixNano()%899999)
}

// orderReq is one Order notification with a pinned sequence and its own
// dedup key (each sequence is a distinct notification of the order).
func orderReq(orderID string, seq int64) requestOpts {
	return requestOpts{orderID: orderID, sequence: seq, dedupKey: fmt.Sprintf("order_confirmed:%s:%d", orderID, seq)}
}

func (e *e2eEnv) latestRequest(t *testing.T, source, eventID string) *requestRow {
	t.Helper()
	var r requestRow
	require.NoError(t, testPool.QueryRow(context.Background(),
		`SELECT id, decision, decision_reason, template FROM notification_request
		 WHERE source = $1 AND event_id = $2 ORDER BY received_at DESC LIMIT 1`, source, eventID).
		Scan(&r.ID, &r.Decision, &r.DecisionReason, &r.Template))
	return &r
}

// ---- 2.3 sequence contract ---------------------------------------------------

// IT-CTR-07: an Order request without sequence is a producer contract error:
// FAILED (missing_sequence), no delivery, nothing sent.
func TestE2E_OrderWithoutSequenceFails(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := newOrderID(3000000)
	_, req := e.sendOrder(t, user, requestOpts{orderID: orderID, sequence: -1, dedupKey: "order_confirmed:" + orderID})
	assert.Equal(t, "FAILED", req.Decision)
	assert.Equal(t, entity.ReasonMissingSequence, *req.DecisionReason)
	assert.Empty(t, e.deliveries(t, req.ID))
	time.Sleep(time.Second)
	assert.Empty(t, e.mailpitMessagesTo(t, email))
}

// IT-CTR-08: the same sequence reused for another event of the order.
func TestE2E_OrderSequenceCollision(t *testing.T) {
	e := e2e(t)
	user, _ := newUser(e)
	orderID := newOrderID(3100000)
	before := counter(t, e.metrics.SubjectSequenceEvents.WithLabelValues(entity.SourceOrder, "collision"))
	_, first := e.sendOrder(t, user, orderReq(orderID, 5))
	assert.Equal(t, "DECIDED", first.Decision)
	o := orderReq(orderID, 5)
	o.dedupKey += ":other"
	_, second := e.sendOrder(t, user, o)
	assert.Equal(t, "FAILED", second.Decision)
	assert.Equal(t, entity.ReasonSequenceCollision, *second.DecisionReason)
	assert.Empty(t, e.deliveries(t, second.ID))
	assert.Equal(t, before+1, counter(t, e.metrics.SubjectSequenceEvents.WithLabelValues(entity.SourceOrder, "collision")))
}

// IT-CTR-09: the highest Order event replayed after ingest_dedup and
// delivery_dedup were purged → SKIPPED (duplicate), no new delivery or email.
func TestE2E_OrderReplayAfterDedupPurge(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := newOrderID(3200000)
	o := orderReq(orderID, 4)
	id, ev := requestEvent(user, o)
	e.publish(t, e.cfg.Kafka.Sources.Transactional.Topic, "orders/"+orderID, id, typeRequested, ev)
	first := e.waitRequest(t, entity.SourceOrder, id)
	e.waitDeliveryStatus(t, first.ID, entity.ChannelEmail, entity.StatusSent)

	ctx := context.Background()
	_, err := testPool.Exec(ctx, `DELETE FROM ingest_dedup WHERE source = $1 AND event_id = $2`, entity.SourceOrder, id)
	require.NoError(t, err)
	_, err = testPool.Exec(ctx, `DELETE FROM delivery_dedup WHERE dedup_key = $1`, o.dedupKey)
	require.NoError(t, err)

	e.publish(t, e.cfg.Kafka.Sources.Transactional.Topic, "orders/"+orderID, id, typeRequested, ev)
	waitFor(t, 30*time.Second, "replayed request row", func() bool { return e.countRequests(t, entity.SourceOrder, id) == 2 })
	replay := e.latestRequest(t, entity.SourceOrder, id)
	assert.Equal(t, "SKIPPED", replay.Decision)
	assert.Equal(t, entity.ReasonDuplicate, *replay.DecisionReason)
	assert.Empty(t, e.deliveries(t, replay.ID))
	time.Sleep(time.Second)
	assert.Len(t, e.mailpitMessagesTo(t, email), 1)
}

// ---- 2.4 ordering of Order notifications --------------------------------------

// IT-ORD-01: sequence 8 never starts a provider call while sequence 7 is in
// one; another order keeps sending meanwhile (no global head-of-line block).
func TestE2E_OrderProviderCallsSerialisedPerOrder(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	other, otherEmail := newUser(e)
	orderID := newOrderID(3300000)
	busyBefore := counter(t, e.metrics.OrderingLeaseContention.WithLabelValues(entity.SourceOrder))

	started, release := e.sender.holdNext(email)
	defer release()
	_, r7 := e.sendOrder(t, user, orderReq(orderID, 7))
	select {
	case <-started:
	case <-time.After(30 * time.Second):
		t.Fatal("sequence 7 never reached the provider")
	}
	_, r8 := e.sendOrder(t, user, orderReq(orderID, 8))
	assert.Equal(t, "DECIDED", r8.Decision)

	// A different order is not blocked by the held call.
	_, ro := e.sendOrder(t, other, orderReq(newOrderID(3350000), 1))
	e.waitDeliveryStatus(t, ro.ID, entity.ChannelEmail, entity.StatusSent)
	require.Len(t, e.mailpitMessagesTo(t, otherEmail), 1)

	time.Sleep(1500 * time.Millisecond)
	for ch, d := range e.deliveries(t, r8.ID) {
		assert.Contains(t, []entity.DeliveryStatus{entity.StatusPending, entity.StatusRetry, entity.StatusSending}, d.Status,
			"sequence 8 %s waits for the ordering lease", ch)
	}
	assert.Len(t, e.sender.startsTo(email), 1, "no second provider call for the order while 7 is in flight")
	assert.Greater(t, counter(t, e.metrics.OrderingLeaseContention.WithLabelValues(entity.SourceOrder)), busyBefore)

	release()
	m7 := e.waitDeliveryStatus(t, r7.ID, entity.ChannelEmail, entity.StatusSent)
	m8 := e.waitDeliveryStatus(t, r8.ID, entity.ChannelEmail, entity.StatusSent)
	e.waitDeliveryStatus(t, r8.ID, entity.ChannelInApp, entity.StatusDelivered)
	starts := e.sender.startsTo(email)
	require.Len(t, starts, 2)
	assert.Equal(t, m7.ID, starts[0].DeliveryID, "7 started first")
	assert.Equal(t, m8.ID, starts[1].DeliveryID, "8 started after 7")
	assert.Equal(t, 1, e.sender.maxConcurrent(email))
}

// IT-ORD-02: sequence 7 waiting (lane paused → RETRY) when 8 is ingested →
// 7 becomes SKIPPED (superseded); only 8 reaches the provider.
func TestE2E_OrderWaitingSequenceSuperseded(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := newOrderID(3400000)
	ctx := context.Background()
	require.NoError(t, e.control.SetLaneState(ctx, entity.LaneState{Lane: entity.LaneTransactional, Email: entity.LanePaused, InApp: entity.LaneActive, UpdatedAt: time.Now()}))
	t.Cleanup(func() {
		_ = e.control.SetLaneState(context.Background(), entity.DefaultLaneState(entity.LaneTransactional, time.Now()))
	})
	time.Sleep(2 * e.cfg.Deliver.ControlPollInterval)

	_, r7 := e.sendOrder(t, user, orderReq(orderID, 7))
	e.waitDeliveryStatus(t, r7.ID, entity.ChannelInApp, entity.StatusDelivered)
	waitFor(t, 15*time.Second, "sequence 7 email released by the paused lane", func() bool {
		d := e.deliveries(t, r7.ID)[entity.ChannelEmail]
		return d.Status == entity.StatusRetry
	})
	_, r8 := e.sendOrder(t, user, orderReq(orderID, 8))
	assert.Equal(t, "DECIDED", r8.Decision)

	require.NoError(t, e.control.SetLaneState(ctx, entity.DefaultLaneState(entity.LaneTransactional, time.Now())))
	m7 := e.waitDeliveryStatus(t, r7.ID, entity.ChannelEmail, entity.StatusSkipped)
	assert.Equal(t, entity.ReasonSuperseded, *m7.SkipReason)
	e.waitDeliveryStatus(t, r8.ID, entity.ChannelEmail, entity.StatusSent)
	time.Sleep(time.Second)
	assert.Len(t, e.mailpitMessagesTo(t, email), 1, "only sequence 8 was emailed")
}

// IT-ORD-03: 8 first, then 7 with a new event id → 7 kept on the timeline,
// every delivery SKIPPED (superseded).
func TestE2E_OrderLowerSequenceAfterHigher(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := newOrderID(3500000)
	_, r8 := e.sendOrder(t, user, orderReq(orderID, 8))
	e.waitDeliveryStatus(t, r8.ID, entity.ChannelEmail, entity.StatusSent)
	_, r7 := e.sendOrder(t, user, orderReq(orderID, 7))
	assert.Equal(t, "SKIPPED", r7.Decision)
	assert.Equal(t, entity.ReasonSuperseded, *r7.DecisionReason)
	ds := e.deliveries(t, r7.ID)
	require.Len(t, ds, 2)
	for ch, d := range ds {
		assert.Equal(t, entity.StatusSkipped, d.Status, ch)
		assert.Equal(t, entity.ReasonSuperseded, *d.SkipReason, ch)
	}
	time.Sleep(time.Second)
	assert.Len(t, e.mailpitMessagesTo(t, email), 1)
}

// IT-ORD-04: the owner of the ordering lease died before its provider call →
// nobody sends until the lease expires, then the next delivery takes it over.
func TestE2E_OrderDeadLeaseOwner(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := newOrderID(3600000)
	key := e.keyring.OrderingKey(entity.SourceOrder, "orders/"+orderID)
	expiry := time.Now().Add(3 * time.Second)
	_, err := testPool.Exec(context.Background(),
		`INSERT INTO notification_ordering_lease (ordering_key, delivery_id, lease_until, locked_by) VALUES ($1, $2, $3, 'dead-worker')`,
		key, uuid.New(), expiry)
	require.NoError(t, err)

	_, r := e.sendOrder(t, user, orderReq(orderID, 1))
	time.Sleep(1500 * time.Millisecond)
	assert.Empty(t, e.mailpitMessagesTo(t, email), "no provider call while the dead owner's lease is valid")
	assert.Empty(t, e.sender.startsTo(email))

	e.waitDeliveryStatus(t, r.ID, entity.ChannelEmail, entity.StatusSent)
	starts := e.sender.startsTo(email)
	require.Len(t, starts, 1)
	assert.False(t, starts[0].At.Before(expiry), "taken over only after expiry")
}

// IT-ORD-05: back-to-back sequences of one order under several workers →
// provider calls never overlap and never go backwards in sequence.
func TestE2E_OrderSequencesNeverInvert(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := newOrderID(3700000)
	e.sender.delay(email, 300*time.Millisecond)
	reqs := map[int64]*requestRow{}
	for seq := int64(1); seq <= 3; seq++ {
		id, ev := requestEvent(user, orderReq(orderID, seq))
		e.publish(t, e.cfg.Kafka.Sources.Transactional.Topic, "orders/"+orderID, id, typeRequested, ev)
		reqs[seq] = e.waitRequest(t, entity.SourceOrder, id)
	}
	e.waitDeliveryStatus(t, reqs[3].ID, entity.ChannelEmail, entity.StatusSent)
	e.waitDeliveryStatus(t, reqs[3].ID, entity.ChannelInApp, entity.StatusDelivered)
	for seq := int64(1); seq <= 2; seq++ {
		for ch := range e.deliveries(t, reqs[seq].ID) {
			d := e.waitDeliveryStatus(t, reqs[seq].ID, ch, entity.StatusSent, entity.StatusDelivered, entity.StatusSkipped)
			if d.Status == entity.StatusSkipped {
				assert.Equal(t, entity.ReasonSuperseded, *d.SkipReason)
			}
		}
	}
	seqOf := map[uuid.UUID]int64{}
	for seq, r := range reqs {
		for _, d := range e.deliveries(t, r.ID) {
			seqOf[d.ID] = seq
		}
	}
	var last int64
	for _, s := range e.sender.startsTo(email) {
		seq := seqOf[s.DeliveryID]
		assert.GreaterOrEqual(t, seq, last, "provider calls must not go back in sequence")
		last = seq
	}
	assert.Equal(t, 1, e.sender.maxConcurrent(email), "one provider call per order at a time")
}

// IT-ORD-06: ownership lost while the provider call is in flight → the send is
// recorded as an uncertain outcome (possibly_duplicate + metric), not hidden.
func TestE2E_OrderLeaseLostDuringSend(t *testing.T) {
	e := e2e(t)
	user, email := newUser(e)
	orderID := newOrderID(3800000)
	key := e.keyring.OrderingKey(entity.SourceOrder, "orders/"+orderID)
	lostBefore := counter(t, e.metrics.OrderingLeaseLost.WithLabelValues(entity.SourceOrder, "during_send"))

	started, release := e.sender.holdNext(email)
	defer release()
	_, r := e.sendOrder(t, user, orderReq(orderID, 1))
	select {
	case <-started:
	case <-time.After(30 * time.Second):
		t.Fatal("provider call never started")
	}
	// Another owner / the reaper took the lease while the call was open.
	_, err := testPool.Exec(context.Background(), `DELETE FROM notification_ordering_lease WHERE ordering_key = $1`, key)
	require.NoError(t, err)
	time.Sleep(2500 * time.Millisecond) // > ordering heartbeat (1 s): the refusal is seen
	release()

	d := e.waitDeliveryStatus(t, r.ID, entity.ChannelEmail, entity.StatusSent)
	assert.True(t, d.PossiblyDuplicate, "uncertain outcome is flagged")
	assert.Equal(t, lostBefore+1, counter(t, e.metrics.OrderingLeaseLost.WithLabelValues(entity.SourceOrder, "during_send")))
}

// ---- 2.7 tombstone --------------------------------------------------------------

// IT-DEL-02: after user.deleted, an old Order request replayed (new event id,
// and the original id once ingest_dedup is gone) and a late
// preferences-updated never rebuild anything for the user.
func TestE2E_DeletedUserReplayIsSanitised(t *testing.T) {
	e := e2e(t)
	ctx := context.Background()
	user, email := newUser(e)
	orderID := newOrderID(3900000)
	origID, origEv := requestEvent(user, orderReq(orderID, 1))
	e.publish(t, e.cfg.Kafka.Sources.Transactional.Topic, "orders/"+orderID, origID, typeRequested, origEv)
	orig := e.waitRequest(t, entity.SourceOrder, origID)
	e.waitDeliveryStatus(t, orig.ID, entity.ChannelEmail, entity.StatusSent)

	e.publish(t, e.cfg.Kafka.Sources.UserLifecycle.Topic, user.String(), uuid.NewString(), typeDeleted, userDeletedEvent(user))
	waitFor(t, 30*time.Second, "tombstone", func() bool {
		var n int
		_ = testPool.QueryRow(ctx, `SELECT count(*) FROM deleted_user_tombstone`).Scan(&n)
		var left int
		_ = testPool.QueryRow(ctx, `SELECT count(*) FROM notification_request WHERE user_id = $1`, user).Scan(&left)
		return n > 0 && left == 0
	})

	// Replay with a new event id (and a higher sequence).
	_, replay := e.sendOrder(t, user, orderReq(orderID, 2))
	assert.Equal(t, "SKIPPED", replay.Decision)
	assert.Equal(t, entity.ReasonUserDeleted, *replay.DecisionReason)
	assert.Empty(t, e.deliveries(t, replay.ID))

	// Replay of the original event once ingest_dedup has expired.
	_, err := testPool.Exec(ctx, `DELETE FROM ingest_dedup WHERE source = $1 AND event_id = $2`, entity.SourceOrder, origID)
	require.NoError(t, err)
	e.publish(t, e.cfg.Kafka.Sources.Transactional.Topic, "orders/"+orderID, origID, typeRequested, origEv)
	waitFor(t, 30*time.Second, "replayed original", func() bool { return e.countRequests(t, entity.SourceOrder, origID) == 2 })
	again := e.latestRequest(t, entity.SourceOrder, origID)
	assert.Equal(t, entity.ReasonUserDeleted, *again.DecisionReason)
	assert.Empty(t, e.deliveries(t, again.ID))

	// A late preferences-updated does not re-create the replica.
	e.publish(t, e.cfg.Kafka.Sources.Preferences.Topic, user.String(), uuid.NewString(), "com.ecommerce.user.preferences-updated.v1",
		preferencesEvent(user, true, true, true))
	time.Sleep(2 * time.Second)

	for _, q := range []string{
		`SELECT count(*) FROM notification_request WHERE user_id = $1`,
		`SELECT count(*) FROM notification_delivery WHERE user_id = $1`,
		`SELECT count(*) FROM inbox_item WHERE user_id = $1`,
		`SELECT count(*) FROM user_preference WHERE user_id = $1`,
	} {
		var n int
		require.NoError(t, testPool.QueryRow(ctx, q, user).Scan(&n))
		assert.Equal(t, 0, n, q)
	}
	var leaks int
	require.NoError(t, testPool.QueryRow(ctx,
		`SELECT count(*) FROM notification_request WHERE event_id = $1 AND decision_reason = 'user_deleted'
		   AND (subject IS NOT NULL OR template_data IS NOT NULL OR dedup_key IS NOT NULL)`, origID).Scan(&leaks))
	assert.Zero(t, leaks, "sanitised rows keep no subject, template data or dedup key")
	assert.Len(t, e.mailpitMessagesTo(t, email), 1, "only the pre-deletion email")
}
