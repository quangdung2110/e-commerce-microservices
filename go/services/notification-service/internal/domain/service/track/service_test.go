package track

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// ---- fakes (embedding the port so only used methods are implemented) ----

type fakeDeliveries struct {
	port.DeliveryRepository
	rows        map[string]*entity.Delivery // provider_message_id → row
	transitions []string
}

func (f *fakeDeliveries) FindByProviderMessage(_ context.Context, _ entity.Provider, id string) (*entity.Delivery, error) {
	d, ok := f.rows[id]
	if !ok {
		return nil, errors.ErrNotFound
	}
	cp := *d
	return &cp, nil
}

// TransitionTracked mirrors the repository contract: apply only when the new
// rank is >= current; equal rank refreshes timestamps and reports unchanged.
func (f *fakeDeliveries) TransitionTracked(_ context.Context, id uuid.UUID, to entity.DeliveryStatus, at time.Time, note string) (bool, error) {
	for _, d := range f.rows {
		if d.ID != id {
			continue
		}
		f.transitions = append(f.transitions, fmt.Sprintf("%s:%s", to, note))
		if to.TrackingRank() < d.Status.TrackingRank() {
			return false, nil
		}
		if d.Status == to {
			return false, nil
		}
		d.Status = to
		if to == entity.StatusDelivered {
			d.DeliveredAt = &at
		}
		return true, nil
	}
	return false, errors.ErrNotFound
}

type fakeRequests struct {
	port.RequestRepository
	req *entity.Request
}

func (f *fakeRequests) GetByID(_ context.Context, id uuid.UUID) (*entity.Request, error) {
	if f.req != nil && f.req.ID == id {
		return f.req, nil
	}
	return nil, errors.ErrNotFound
}

type fakeOutbox struct {
	port.OutboxRepository
	events []*entity.OutboxEvent
}

func (f *fakeOutbox) Insert(_ context.Context, ev *entity.OutboxEvent) error {
	f.events = append(f.events, ev)
	return nil
}

type fakeSuppressions struct {
	port.SuppressionRepository
	rows []*entity.Suppression
}

func (f *fakeSuppressions) Insert(_ context.Context, s *entity.Suppression) error {
	f.rows = append(f.rows, s)
	return nil
}

func (f *fakeSuppressions) ActiveByHash(_ context.Context, h string) ([]entity.Suppression, error) {
	var out []entity.Suppression
	for _, r := range f.rows {
		if r.EmailHash == h && r.SupersededAt == nil {
			out = append(out, *r)
		}
	}
	return out, nil
}

type fakePreferences struct {
	port.PreferenceRepository
	disabled []uuid.UUID
	version  int64
}

func (f *fakePreferences) DisableMarketing(_ context.Context, userID uuid.UUID, version int64, _ time.Time) error {
	f.disabled = append(f.disabled, userID)
	f.version = version
	return nil
}

type fakeTx struct {
	port.Tx
	deliveries   *fakeDeliveries
	requests     *fakeRequests
	outbox       *fakeOutbox
	suppressions *fakeSuppressions
	preferences  *fakePreferences
}

func (f *fakeTx) Deliveries() port.DeliveryRepository      { return f.deliveries }
func (f *fakeTx) Requests() port.RequestRepository         { return f.requests }
func (f *fakeTx) Outbox() port.OutboxRepository            { return f.outbox }
func (f *fakeTx) Suppressions() port.SuppressionRepository { return f.suppressions }
func (f *fakeTx) Preferences() port.PreferenceRepository   { return f.preferences }

// ---- fixture -------------------------------------------------------------

const (
	msgID     = "0100019996a1b2c3-1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d-000000"
	recipient = "recipient@example.com"
)

var (
	now        = time.Date(2026, 9, 22, 7, 50, 0, 0, time.UTC)
	occurredAt = time.Date(2026, 9, 22, 7, 47, 34, 800000000, time.UTC)
)

type fixture struct {
	svc *Service
	tx  *fakeTx
	d   *entity.Delivery
	clk *clock.Fake
}

func newFixture(t *testing.T, lane entity.Lane, status entity.DeliveryStatus) *fixture {
	t.Helper()
	userID := uuid.New()
	subject := "orders/1001"
	prov := entity.ProviderSES
	pmid := msgID
	d := &entity.Delivery{
		ID: uuid.New(), RequestID: uuid.New(), UserID: &userID, Subject: &subject,
		Lane: lane, Channel: entity.ChannelEmail, Template: "ORDER_CONFIRMED",
		Status: status, Provider: &prov, ProviderMessageID: &pmid,
	}
	corr := "corr-1"
	tp := "00-abc-def-01"
	tx := &fakeTx{
		deliveries:   &fakeDeliveries{rows: map[string]*entity.Delivery{msgID: d}},
		requests:     &fakeRequests{req: &entity.Request{ID: d.RequestID, CorrelationID: &corr, TraceParent: &tp}},
		outbox:       &fakeOutbox{},
		suppressions: &fakeSuppressions{},
		preferences:  &fakePreferences{},
	}
	clk := clock.NewFake(now)
	svc := New(config.TrackConfig{SoftBounceSuppression: 48 * time.Hour}, clk, telemetry.New(), nil)
	return &fixture{svc: svc, tx: tx, d: d, clk: clk}
}

// sanitisedRaw builds provider_event.raw the way the poller stores it:
// addresses already replaced by hashes.
func sanitisedRaw(t *testing.T, eventType string, deliveryID *uuid.UUID, bounceType string, recipientHash string) json.RawMessage {
	t.Helper()
	doc := map[string]any{
		"eventType": eventType,
		"mail": map[string]any{
			"messageId":   msgID,
			"destination": []string{recipientHash},
			"tags":        map[string][]string{},
		},
	}
	if deliveryID != nil {
		doc["mail"].(map[string]any)["tags"] = map[string][]string{"delivery_id": {deliveryID.String()}}
	}
	switch eventType {
	case "Bounce":
		doc["bounce"] = map[string]any{
			"bounceType":        bounceType,
			"bouncedRecipients": []map[string]string{{"emailAddress": "Name <" + recipientHash + ">"}},
		}
	case "Complaint":
		doc["complaint"] = map[string]any{
			"complainedRecipients": []map[string]string{{"emailAddress": recipientHash}},
		}
	}
	raw, err := json.Marshal(doc)
	require.NoError(t, err)
	return raw
}

func event(t *testing.T, id int64, typ entity.ProviderEventType, raw json.RawMessage) *entity.ProviderEvent {
	t.Helper()
	pmid := msgID
	at := occurredAt
	return &entity.ProviderEvent{
		ID: id, Provider: entity.ProviderSES, ProviderEventID: fmt.Sprintf("ev-%d", id),
		ProviderMessageID: &pmid, EventType: typ, OccurredAt: &at, Raw: raw,
		Status: entity.ProviderEventPending, ReceivedAt: now,
	}
}

func decodeData(t *testing.T, ev *entity.OutboxEvent) (entity.CloudEvent, map[string]any) {
	t.Helper()
	var ce entity.CloudEvent
	require.NoError(t, json.Unmarshal(ev.Payload, &ce))
	var data map[string]any
	require.NoError(t, json.Unmarshal(ce.Data, &data))
	return ce, data
}

// ---- tests ---------------------------------------------------------------

func TestProcess_DeliveredFlowWritesOutbox(t *testing.T) {
	f := newFixture(t, entity.LaneTransactional, entity.StatusSent)
	hash := pii.HashEmail(recipient)

	out, err := f.svc.Process(context.Background(), f.tx, event(t, 1, entity.ProviderEventDelivery, sanitisedRaw(t, "Delivery", &f.d.ID, "", hash)))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: true}, out)
	require.Equal(t, entity.StatusDelivered, f.tx.deliveries.rows[msgID].Status)
	require.Len(t, f.tx.outbox.events, 1)

	ev := f.tx.outbox.events[0]
	require.Equal(t, entity.EventTypeDelivered, ev.Type)
	require.Equal(t, f.d.UserID.String(), ev.AggregateID)
	ce, data := decodeData(t, ev)
	require.Equal(t, "corr-1", ce.CorrelationID)
	require.Equal(t, "00-abc-def-01", ce.TraceParent)
	require.Equal(t, f.d.ID.String(), data["delivery_id"])
	require.Equal(t, occurredAt.Format(time.RFC3339Nano), data["delivered_at"])
	require.NotContains(t, string(ev.Payload), recipient)

	// A duplicate Delivery (different event id) changes nothing and emits nothing.
	out, err = f.svc.Process(context.Background(), f.tx, event(t, 2, entity.ProviderEventDelivery, sanitisedRaw(t, "Delivery", &f.d.ID, "", hash)))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: false}, out)
	require.Len(t, f.tx.outbox.events, 1)
}

func TestProcess_SendAfterDeliveredIsIgnoredLowerRank(t *testing.T) {
	f := newFixture(t, entity.LaneTransactional, entity.StatusDelivered)
	out, err := f.svc.Process(context.Background(), f.tx, event(t, 1, entity.ProviderEventSend, sanitisedRaw(t, "Send", &f.d.ID, "", "h")))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultIgnoredLowerRank}, out)
	require.Equal(t, entity.StatusDelivered, f.tx.deliveries.rows[msgID].Status)
	require.Empty(t, f.tx.deliveries.transitions, "no UPDATE issued for a lower-rank event")
	require.Empty(t, f.tx.outbox.events)

	// Send on a SENT row is the normal case: applied, nothing changed.
	f2 := newFixture(t, entity.LaneTransactional, entity.StatusSent)
	out, err = f2.svc.Process(context.Background(), f2.tx, event(t, 1, entity.ProviderEventSend, sanitisedRaw(t, "Send", &f2.d.ID, "", "h")))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: false}, out)
	require.Empty(t, f2.tx.outbox.events, "SENT never produces an outcome event")

	// Delivery before Send jumps straight to DELIVERED; Send afterwards is ignored.
	f3 := newFixture(t, entity.LaneTransactional, entity.StatusSent)
	hash := pii.HashEmail(recipient)
	_, err = f3.svc.Process(context.Background(), f3.tx, event(t, 1, entity.ProviderEventDelivery, sanitisedRaw(t, "Delivery", &f3.d.ID, "", hash)))
	require.NoError(t, err)
	out, err = f3.svc.Process(context.Background(), f3.tx, event(t, 2, entity.ProviderEventSend, sanitisedRaw(t, "Send", &f3.d.ID, "", hash)))
	require.NoError(t, err)
	require.Equal(t, ResultIgnoredLowerRank, out.Result)
}

func TestProcess_PermanentBounceSuppressesAndEmits(t *testing.T) {
	f := newFixture(t, entity.LaneTransactional, entity.StatusSent)
	hash := pii.HashEmail(recipient)

	out, err := f.svc.Process(context.Background(), f.tx, event(t, 7, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &f.d.ID, "Permanent", hash)))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: true}, out)
	require.Equal(t, entity.StatusBounced, f.tx.deliveries.rows[msgID].Status)

	require.Len(t, f.tx.suppressions.rows, 1)
	s := f.tx.suppressions.rows[0]
	require.Equal(t, hash, s.EmailHash, "hash extracted from the display form")
	require.Equal(t, entity.SuppressionHardBounce, s.Reason)
	require.Equal(t, SuppressionSource, s.Source)
	require.Nil(t, s.Until)
	require.Equal(t, f.d.UserID, s.UserID)
	require.NotNil(t, s.EvidenceEventID)
	require.EqualValues(t, 7, *s.EvidenceEventID)

	require.Len(t, f.tx.outbox.events, 1)
	require.Equal(t, entity.EventTypeBounced, f.tx.outbox.events[0].Type)
	_, data := decodeData(t, f.tx.outbox.events[0])
	require.Equal(t, "permanent", data["bounce_type"])
	require.Equal(t, true, data["suppressed"])

	// Same bounce again (SNS redelivery with a new timestamp): no second
	// suppression row, no second event.
	out, err = f.svc.Process(context.Background(), f.tx, event(t, 8, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &f.d.ID, "Permanent", hash)))
	require.NoError(t, err)
	require.Equal(t, ResultApplied, out.Result)
	require.Len(t, f.tx.suppressions.rows, 1)
	require.Len(t, f.tx.outbox.events, 1)
}

func TestProcess_BounceAfterDeliveredStillApplies(t *testing.T) {
	// Delayed bounce (03-tracking.md §5.1): bounce outranks delivered.
	f := newFixture(t, entity.LaneBulk, entity.StatusDelivered)
	hash := pii.HashEmail(recipient)
	out, err := f.svc.Process(context.Background(), f.tx, event(t, 1, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &f.d.ID, "Permanent", hash)))
	require.NoError(t, err)
	require.True(t, out.Changed)
	require.Equal(t, entity.StatusBounced, f.tx.deliveries.rows[msgID].Status)
}

func TestProcess_TransientBounceSoftSuppression(t *testing.T) {
	f := newFixture(t, entity.LaneBulk, entity.StatusSent)
	hash := pii.HashEmail(recipient)

	out, err := f.svc.Process(context.Background(), f.tx, event(t, 3, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &f.d.ID, "Transient", hash)))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: true}, out)
	require.Equal(t, entity.StatusSent, f.tx.deliveries.rows[msgID].Status, "transient bounce keeps SENT")
	require.Empty(t, f.tx.deliveries.transitions)

	require.Len(t, f.tx.suppressions.rows, 1)
	s := f.tx.suppressions.rows[0]
	require.Equal(t, entity.SuppressionSoftBounce, s.Reason)
	require.NotNil(t, s.Until)
	require.Equal(t, now.Add(48*time.Hour), *s.Until)
	require.True(t, s.ActiveAt(now.Add(47*time.Hour)))
	require.False(t, s.ActiveAt(now.Add(49*time.Hour)))

	require.Len(t, f.tx.outbox.events, 1)
	_, data := decodeData(t, f.tx.outbox.events[0])
	require.Equal(t, "transient", data["bounce_type"])
	require.Equal(t, true, data["suppressed"])

	// Undetermined is treated as transient: no permanent suppression.
	f2 := newFixture(t, entity.LaneBulk, entity.StatusSent)
	_, err = f2.svc.Process(context.Background(), f2.tx, event(t, 4, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &f2.d.ID, "Undetermined", hash)))
	require.NoError(t, err)
	require.Equal(t, entity.SuppressionSoftBounce, f2.tx.suppressions.rows[0].Reason)
	require.Equal(t, entity.StatusSent, f2.tx.deliveries.rows[msgID].Status)
}

func TestProcess_ComplaintDisablesMarketingAndEmits(t *testing.T) {
	f := newFixture(t, entity.LaneBulk, entity.StatusDelivered)
	hash := pii.HashEmail(recipient)

	out, err := f.svc.Process(context.Background(), f.tx, event(t, 5, entity.ProviderEventComplaint, sanitisedRaw(t, "Complaint", &f.d.ID, "", hash)))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: true}, out)
	require.Equal(t, entity.StatusDelivered, f.tx.deliveries.rows[msgID].Status, "complaint keeps the delivered status")

	require.Len(t, f.tx.suppressions.rows, 1)
	require.Equal(t, entity.SuppressionComplaint, f.tx.suppressions.rows[0].Reason)
	require.Nil(t, f.tx.suppressions.rows[0].Until)

	require.Equal(t, []uuid.UUID{*f.d.UserID}, f.tx.preferences.disabled)
	require.Equal(t, occurredAt.UnixMilli(), f.tx.preferences.version)

	require.Len(t, f.tx.outbox.events, 1)
	require.Equal(t, entity.EventTypeComplaintReceived, f.tx.outbox.events[0].Type)
	_, data := decodeData(t, f.tx.outbox.events[0])
	require.Equal(t, true, data["marketing_disabled"])
}

func TestProcess_CriticalLaneBounceNoSuppression(t *testing.T) {
	f := newFixture(t, entity.LaneCritical, entity.StatusSent)
	hash := pii.HashEmail(recipient)

	out, err := f.svc.Process(context.Background(), f.tx, event(t, 1, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &f.d.ID, "Permanent", hash)))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: true}, out)
	require.Equal(t, entity.StatusBounced, f.tx.deliveries.rows[msgID].Status, "status still moves to BOUNCED")
	require.Empty(t, f.tx.suppressions.rows, "critical lane is never suppressed")
	require.Len(t, f.tx.outbox.events, 1)
	_, data := decodeData(t, f.tx.outbox.events[0])
	require.Equal(t, false, data["suppressed"])

	// Complaint on critical: no suppression row, marketing still disabled.
	f2 := newFixture(t, entity.LaneCritical, entity.StatusDelivered)
	_, err = f2.svc.Process(context.Background(), f2.tx, event(t, 2, entity.ProviderEventComplaint, sanitisedRaw(t, "Complaint", &f2.d.ID, "", hash)))
	require.NoError(t, err)
	require.Empty(t, f2.tx.suppressions.rows)
	require.Len(t, f2.tx.preferences.disabled, 1)
}

func TestProcess_DeliveryIDTagMismatchDisablesSuppression(t *testing.T) {
	// A validly signed event whose tag points at another delivery must not
	// suppress anyone (03-tracking.md §6 item 3).
	f := newFixture(t, entity.LaneBulk, entity.StatusSent)
	other := uuid.New()
	hash := pii.HashEmail(recipient)
	out, err := f.svc.Process(context.Background(), f.tx, event(t, 1, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &other, "Permanent", hash)))
	require.NoError(t, err)
	require.Equal(t, ResultApplied, out.Result)
	require.Empty(t, f.tx.suppressions.rows)
	require.Equal(t, entity.StatusBounced, f.tx.deliveries.rows[msgID].Status)

	// No tag at all: correlation by message id is accepted.
	f2 := newFixture(t, entity.LaneBulk, entity.StatusSent)
	_, err = f2.svc.Process(context.Background(), f2.tx, event(t, 2, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", nil, "Permanent", hash)))
	require.NoError(t, err)
	require.Len(t, f2.tx.suppressions.rows, 1)
}

func TestProcess_Unmatched(t *testing.T) {
	f := newFixture(t, entity.LaneTransactional, entity.StatusSent)
	ev := event(t, 1, entity.ProviderEventDelivery, sanitisedRaw(t, "Delivery", nil, "", "h"))
	unknown := "unknown-message-id"
	ev.ProviderMessageID = &unknown
	out, err := f.svc.Process(context.Background(), f.tx, ev)
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultUnmatched}, out)
	require.Empty(t, f.tx.outbox.events)

	// No message id at all cannot ever match: ignored, not retried.
	ev.ProviderMessageID = nil
	out, err = f.svc.Process(context.Background(), f.tx, ev)
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultIgnored}, out)
}

func TestProcess_RejectAndIgnoredTypes(t *testing.T) {
	f := newFixture(t, entity.LaneTransactional, entity.StatusSent)
	out, err := f.svc.Process(context.Background(), f.tx, event(t, 1, entity.ProviderEventReject, sanitisedRaw(t, "Reject", &f.d.ID, "", "h")))
	require.NoError(t, err)
	require.Equal(t, Outcome{Result: ResultApplied, Changed: true}, out)
	require.Equal(t, entity.StatusBounced, f.tx.deliveries.rows[msgID].Status)
	require.Equal(t, []string{"BOUNCED:reject"}, f.tx.deliveries.transitions)
	require.Empty(t, f.tx.suppressions.rows)
	require.Len(t, f.tx.outbox.events, 1)
	_, data := decodeData(t, f.tx.outbox.events[0])
	require.Equal(t, false, data["suppressed"])

	for _, typ := range []entity.ProviderEventType{entity.ProviderEventDeliveryDelay, entity.ProviderEventOther} {
		out, err := f.svc.Process(context.Background(), f.tx, event(t, 2, typ, nil))
		require.NoError(t, err)
		require.Equal(t, Outcome{Result: ResultIgnored}, out)
	}
}

func TestProcess_AnonymisedDeliverySkipsOutbox(t *testing.T) {
	f := newFixture(t, entity.LaneBulk, entity.StatusSent)
	f.d.UserID = nil
	hash := pii.HashEmail(recipient)
	out, err := f.svc.Process(context.Background(), f.tx, event(t, 1, entity.ProviderEventBounce, sanitisedRaw(t, "Bounce", &f.d.ID, "Permanent", hash)))
	require.NoError(t, err)
	require.True(t, out.Changed)
	require.Empty(t, f.tx.outbox.events, "no user_id → no outcome event")
	require.Len(t, f.tx.suppressions.rows, 1, "hash is still suppressed without a user")
	require.Nil(t, f.tx.suppressions.rows[0].UserID)
}
