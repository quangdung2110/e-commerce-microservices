package ingest

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/domain/service/decide"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// ---- fake store: only the methods boundary 1 uses are implemented; the
// embedded interfaces make any other call panic loudly. -------------------

type fakeTx struct {
	port.Tx
	ingestKeys map[string]bool
	dedupKeys  map[string]bool
	prefs      map[uuid.UUID]entity.Preference
	requests   []*entity.Request
	deliveries []*entity.Delivery
	insertErr  error

	// pending writes visible only after commit
	pReq  []*entity.Request
	pDel  []*entity.Delivery
	pKeys []string
}

func newTx() *fakeTx {
	return &fakeTx{ingestKeys: map[string]bool{}, dedupKeys: map[string]bool{}, prefs: map[uuid.UUID]entity.Preference{}}
}

func (t *fakeTx) begin()    { t.pReq, t.pDel, t.pKeys = nil, nil, nil }
func (t *fakeTx) rollback() { t.begin() }
func (t *fakeTx) commit() {
	t.requests = append(t.requests, t.pReq...)
	t.deliveries = append(t.deliveries, t.pDel...)
	for _, k := range t.pKeys {
		t.ingestKeys[k] = true
	}
	t.begin()
}

type fakeRequests struct {
	port.RequestRepository
	tx *fakeTx
}

func (r fakeRequests) Insert(_ context.Context, req *entity.Request) error {
	if r.tx.insertErr != nil {
		return r.tx.insertErr
	}
	cp := *req
	r.tx.pReq = append(r.tx.pReq, &cp)
	return nil
}

func (r fakeRequests) UpdateDecision(_ context.Context, req *entity.Request) error {
	for _, p := range r.tx.pReq {
		if p.ID == req.ID {
			*p = *req
			return nil
		}
	}
	return errors.New("request not found in tx")
}

type fakeDeliveries struct {
	port.DeliveryRepository
	tx *fakeTx
}

func (r fakeDeliveries) Insert(_ context.Context, d *entity.Delivery) error {
	cp := *d
	r.tx.pDel = append(r.tx.pDel, &cp)
	return nil
}

type fakeDedup struct {
	port.DedupRepository
	tx *fakeTx
}

func (r fakeDedup) ClaimIngest(_ context.Context, source, id string) (bool, error) {
	k := source + "|" + id
	if r.tx.ingestKeys[k] {
		return false, nil
	}
	for _, p := range r.tx.pKeys {
		if p == k {
			return false, nil
		}
	}
	r.tx.pKeys = append(r.tx.pKeys, k)
	return true, nil
}

func (r fakeDedup) ClaimDelivery(_ context.Context, key string, ch entity.Channel) (bool, error) {
	k := key + "|" + string(ch)
	if r.tx.dedupKeys[k] {
		return false, nil
	}
	r.tx.dedupKeys[k] = true
	return true, nil
}

type fakePrefs struct {
	port.PreferenceRepository
	tx *fakeTx
}

func (r fakePrefs) Get(_ context.Context, id uuid.UUID) (*entity.Preference, error) {
	p, ok := r.tx.prefs[id]
	if !ok {
		return nil, apperr.ErrNotFound
	}
	return &p, nil
}

type fakeSupp struct{ port.SuppressionRepository }

func (fakeSupp) ActiveByUser(context.Context, uuid.UUID) ([]entity.Suppression, error) {
	return nil, nil
}

// wire the typed adapters through the Tx accessors
type txAdapter struct {
	port.Tx
	t *fakeTx
}

func (a txAdapter) Requests() port.RequestRepository         { return fakeRequests{tx: a.t} }
func (a txAdapter) Deliveries() port.DeliveryRepository      { return fakeDeliveries{tx: a.t} }
func (a txAdapter) Dedup() port.DedupRepository              { return fakeDedup{tx: a.t} }
func (a txAdapter) Preferences() port.PreferenceRepository   { return fakePrefs{tx: a.t} }
func (a txAdapter) Suppressions() port.SuppressionRepository { return fakeSupp{} }

type store struct {
	port.Store
	t         *fakeTx
	committed int
	rolled    int
	notified  []entity.Lane
	notifyErr error
}

func (s *store) WithinTx(ctx context.Context, fn func(ctx context.Context, tx port.Tx) error) error {
	s.t.begin()
	if err := fn(ctx, txAdapter{t: s.t}); err != nil {
		s.t.rollback()
		s.rolled++
		return err
	}
	s.t.commit()
	s.committed++
	return nil
}

func (s *store) Maintenance() port.MaintenanceRepository { return maint{s: s} }

type maint struct {
	port.MaintenanceRepository
	s *store
}

func (m maint) Notify(_ context.Context, lane entity.Lane) error {
	m.s.notified = append(m.s.notified, lane)
	return m.s.notifyErr
}

type fakeLaneCtl struct {
	port.LaneControl
	mode entity.ReplayMode
	err  error
}

func (f *fakeLaneCtl) ReplayMode(context.Context) (entity.ReplayMode, error) { return f.mode, f.err }

type fakeFreq struct{}

func (fakeFreq) Increment(context.Context, uuid.UUID, string) (int64, bool, error) {
	return 1, true, nil
}
func (fakeFreq) Forget(context.Context, uuid.UUID) error { return nil }

// ---- helpers -------------------------------------------------------------

var (
	userID = uuid.MustParse("0f6d3a8e-6a4e-4d0d-9c3b-2b1e6f7a8c9d")
	now    = time.Date(2026, 9, 20, 10, 15, 30, 0, time.UTC)
)

func orderConfirmed() entity.IngestInput {
	return entity.IngestInput{
		Source: "/services/order", EventID: "evt-1", EventType: "com.ecommerce.notification.requested.v1",
		EventTime: now.Add(-time.Minute), Subject: "orders/1000234", InputKind: entity.InputRequest,
		Lane: entity.LaneTransactional, Template: "ORDER_CONFIRMED", UserID: userID.String(),
		DedupKey: "order_confirmed:1000234", CorrelationID: "corr-1", TraceParent: "00-t-s-01",
		TemplateData: json.RawMessage(`{"order_id":"1000234","order_code":"ORD-1000234","placed_at":"2026-09-20T10:15:02Z","items":[{"name":"A","quantity":1,"unit_price_minor":1}],"subtotal_minor":1,"discount_minor":0,"shipping_fee_minor":0,"total_minor":1,"currency":"VND","payment_method_label":"x","shipping_address":{"recipient_name":"A","ward":"","district":"","city":""},"order_url":"https://shop.example.com/orders/1000234"}`),
	}
}

func emailChanged() entity.IngestInput {
	return entity.IngestInput{
		Source: "/services/auth", EventID: "evt-a", EventType: "com.ecommerce.auth.user-email-changed.v1",
		InputKind: entity.InputRequest, Lane: entity.LaneCritical, Template: "EMAIL_CHANGED",
		UserID: userID.String(), DedupKey: "email_changed:d1", SourceRef: "d1",
		TemplateData: json.RawMessage(`{"event":"EMAIL_CHANGED","changed_at":"2026-09-20T10:00:00Z"}`),
	}
}

type env struct {
	svc     *Service
	st      *store
	lane    *fakeLaneCtl
	clk     *clock.Fake
	metrics *telemetry.Metrics
}

func newEnv(t *testing.T) *env {
	t.Helper()
	cat, err := catalog.Load()
	if err != nil {
		t.Fatal(err)
	}
	e := &env{st: &store{t: newTx()}, lane: &fakeLaneCtl{}, clk: clock.NewFake(now), metrics: telemetry.New()}
	dec := decide.New(decide.Deps{Catalog: cat, FreqCap: fakeFreq{}, Clock: e.clk, Metrics: e.metrics,
		Policy: decide.Policy{QuietFrom: "22:00", QuietTo: "08:00", DefaultTimezone: "Asia/Ho_Chi_Minh", FrequencyCapPerDay: 2}})
	e.svc = New(e.st, dec, e.lane, e.clk, e.metrics, nil)
	return e
}

// ---- tests ---------------------------------------------------------------

func TestIngest_DecidedCreatesPendingRows(t *testing.T) {
	e := newEnv(t)
	out, err := e.svc.Ingest(context.Background(), orderConfirmed())
	if err != nil {
		t.Fatal(err)
	}
	if out.Duplicate || out.Decision != entity.DecisionDecided || len(out.Kept) != 2 || out.RequestID == uuid.Nil {
		t.Fatalf("outcome %+v", out)
	}
	if e.st.committed != 1 || len(e.st.t.requests) != 1 || len(e.st.t.deliveries) != 2 {
		t.Fatalf("store: committed=%d requests=%d deliveries=%d", e.st.committed, len(e.st.t.requests), len(e.st.t.deliveries))
	}
	req := e.st.t.requests[0]
	if req.Decision != entity.DecisionDecided || req.DecisionReason != nil || req.UserID == nil || *req.UserID != userID {
		t.Fatalf("request row %+v", req)
	}
	if req.ExpiresAt == nil || !req.ExpiresAt.Equal(now.Add(6*time.Hour)) {
		t.Fatalf("request expires_at = %v", req.ExpiresAt)
	}
	if req.EventTime == nil || *req.Lane != entity.LaneTransactional || *req.Template != "ORDER_CONFIRMED" || *req.CorrelationID != "corr-1" || *req.TraceParent != "00-t-s-01" || !req.ReceivedAt.Equal(now) {
		t.Fatalf("request columns %+v", req)
	}
	for _, d := range e.st.t.deliveries {
		if d.RequestID != req.ID || d.Status != entity.StatusPending || d.Lane != entity.LaneTransactional || d.Template != "ORDER_CONFIRMED" ||
			*d.DedupKey != "order_confirmed:1000234" || *d.Subject != "orders/1000234" || *d.UserID != userID ||
			!d.ExpiresAt.Equal(now.Add(6*time.Hour)) || !d.NextAttemptAt.Equal(now) || d.FinishedAt != nil || d.SkipReason != nil {
			t.Fatalf("delivery row %+v", d)
		}
	}
	if len(e.st.notified) != 0 {
		t.Fatalf("transactional must not NOTIFY")
	}
}

func TestIngest_DuplicateEvent(t *testing.T) {
	e := newEnv(t)
	if _, err := e.svc.Ingest(context.Background(), orderConfirmed()); err != nil {
		t.Fatal(err)
	}
	out, err := e.svc.Ingest(context.Background(), orderConfirmed())
	if err != nil {
		t.Fatal(err)
	}
	if !out.Duplicate || out.RequestID != uuid.Nil {
		t.Fatalf("expected duplicate outcome, got %+v", out)
	}
	if len(e.st.t.requests) != 1 || len(e.st.t.deliveries) != 2 {
		t.Fatalf("duplicate must write nothing")
	}
}

func TestIngest_SkippedRowsForDroppedChannels(t *testing.T) {
	e := newEnv(t)
	e.st.t.prefs[userID] = entity.Preference{UserID: userID, EmailEnabled: false, InAppEnabled: true}
	out, err := e.svc.Ingest(context.Background(), orderConfirmed())
	if err != nil {
		t.Fatal(err)
	}
	if out.Decision != entity.DecisionDecided || len(out.Kept) != 1 || out.Kept[0] != entity.ChannelInApp {
		t.Fatalf("outcome %+v", out)
	}
	var skipped *entity.Delivery
	for _, d := range e.st.t.deliveries {
		if d.Channel == entity.ChannelEmail {
			skipped = d
		}
	}
	if skipped == nil || skipped.Status != entity.StatusSkipped || *skipped.SkipReason != entity.ReasonPreferenceOff || skipped.FinishedAt == nil || !skipped.FinishedAt.Equal(now) {
		t.Fatalf("skipped row %+v", skipped)
	}

	// Second request with a fresh event id: both channels now duplicate.
	in := orderConfirmed()
	in.EventID = "evt-2"
	e.st.t.prefs[userID] = entity.Preference{UserID: userID, EmailEnabled: true, InAppEnabled: true}
	out, err = e.svc.Ingest(context.Background(), in)
	if err != nil {
		t.Fatal(err)
	}
	// email was never claimed (preference_off the first time) → kept; in_app duplicate
	if out.Decision != entity.DecisionDecided || len(out.Kept) != 1 || out.Kept[0] != entity.ChannelEmail {
		t.Fatalf("outcome %+v", out)
	}
}

func TestIngest_ProducerErrorCreatesNoDeliveries(t *testing.T) {
	e := newEnv(t)
	in := orderConfirmed()
	in.Template = "NOPE"
	out, err := e.svc.Ingest(context.Background(), in)
	if err != nil {
		t.Fatal(err)
	}
	if out.Decision != entity.DecisionFailed || out.Reason != entity.ReasonUnknownTemplate || len(out.Kept) != 0 {
		t.Fatalf("outcome %+v", out)
	}
	if len(e.st.t.requests) != 1 || len(e.st.t.deliveries) != 0 {
		t.Fatalf("FAILED must persist the request only")
	}
	if *e.st.t.requests[0].DecisionReason != entity.ReasonUnknownTemplate {
		t.Fatalf("reason not stored")
	}
}

func TestIngest_CriticalNotifies(t *testing.T) {
	e := newEnv(t)
	e.st.notifyErr = errors.New("listen/notify unsupported")
	out, err := e.svc.Ingest(context.Background(), emailChanged())
	if err != nil {
		t.Fatal(err)
	}
	if out.Decision != entity.DecisionDecided || len(out.Kept) != 2 {
		t.Fatalf("outcome %+v", out)
	}
	if len(e.st.notified) != 1 || e.st.notified[0] != entity.LaneCritical {
		t.Fatalf("expected NOTIFY critical (error tolerated), got %v", e.st.notified)
	}
	for _, d := range e.st.t.deliveries {
		if d.SourceRef == nil || *d.SourceRef != "d1" {
			t.Fatalf("source_ref must reach the delivery row: %+v", d)
		}
	}
}

func TestIngest_ReplayMode(t *testing.T) {
	e := newEnv(t)
	e.lane.mode = entity.ReplayMode{Enabled: true, IgnoreEventsOlderThan: 24 * time.Hour}
	old := orderConfirmed()
	old.EventTime = now.Add(-48 * time.Hour)
	out, err := e.svc.Ingest(context.Background(), old)
	if err != nil {
		t.Fatal(err)
	}
	if out.Decision != entity.DecisionIgnored || out.Reason != entity.ReasonReplayTooOld || len(e.st.t.deliveries) != 0 {
		t.Fatalf("outcome %+v", out)
	}
	if *e.st.t.requests[0].DecisionReason != entity.ReasonReplayTooOld {
		t.Fatalf("request row must record replay_too_old")
	}

	fresh := orderConfirmed()
	fresh.EventID = "evt-fresh"
	out, err = e.svc.Ingest(context.Background(), fresh)
	if err != nil {
		t.Fatal(err)
	}
	if out.Decision != entity.DecisionDecided {
		t.Fatalf("recent event must still be decided: %+v", out)
	}

	t.Run("expired replay mode is inactive", func(t *testing.T) {
		e := newEnv(t)
		past := now.Add(-time.Hour)
		e.lane.mode = entity.ReplayMode{Enabled: true, IgnoreEventsOlderThan: time.Hour, ExpiresAt: &past}
		out, err := e.svc.Ingest(context.Background(), old)
		if err != nil {
			t.Fatal(err)
		}
		if out.Decision != entity.DecisionDecided {
			t.Fatalf("got %+v", out)
		}
	})
	t.Run("lane control error is retryable", func(t *testing.T) {
		e := newEnv(t)
		e.lane.err = errors.New("pg down")
		if _, err := e.svc.Ingest(context.Background(), orderConfirmed()); err == nil {
			t.Fatal("expected error")
		}
	})
}

func TestIngest_StoreErrorRollsBack(t *testing.T) {
	e := newEnv(t)
	e.st.t.insertErr = errors.New("pg down")
	if _, err := e.svc.Ingest(context.Background(), orderConfirmed()); err == nil {
		t.Fatal("expected error")
	}
	if e.st.rolled != 1 || e.st.committed != 0 || len(e.st.t.requests) != 0 {
		t.Fatalf("rollback expected")
	}
	// The ingest_dedup claim was rolled back too: the retry must succeed.
	e.st.t.insertErr = nil
	out, err := e.svc.Ingest(context.Background(), orderConfirmed())
	if err != nil || out.Duplicate {
		t.Fatalf("retry after rollback: %v %+v", err, out)
	}
}
