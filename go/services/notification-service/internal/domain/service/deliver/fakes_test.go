package deliver

import (
	"context"
	"encoding/json"
	stderrors "errors"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// ---- store -----------------------------------------------------------------
//
// The fakes embed the port interfaces so only the methods the service uses
// are implemented; anything else panics with a nil-interface call, which is
// exactly what a test should surface.

type fakeStore struct {
	port.Store
	mu       sync.Mutex
	txCount  int
	txFailed error // returned by WithinTx before fn runs
	deliv    *fakeDeliveries
	reqs     *fakeRequests
	prefs    *fakePreferences
	supp     *fakeSuppressions
	inbox    *fakeInbox
	outbox   *fakeOutbox
	ord      *fakeOrdering
	inTxSeen []string // repository calls made inside a transaction
}

func newFakeStore() *fakeStore {
	s := &fakeStore{
		deliv:  &fakeDeliveries{},
		reqs:   &fakeRequests{byID: map[uuid.UUID]*entity.Request{}},
		prefs:  &fakePreferences{byUser: map[uuid.UUID]*entity.Preference{}},
		supp:   &fakeSuppressions{},
		inbox:  &fakeInbox{},
		outbox: &fakeOutbox{},
		ord:    &fakeOrdering{holds: true, extendOK: true},
	}
	s.deliv.store = s
	s.inbox.store = s
	s.outbox.store = s
	return s
}

func (s *fakeStore) WithinTx(ctx context.Context, fn func(ctx context.Context, tx port.Tx) error) error {
	s.mu.Lock()
	s.txCount++
	failed := s.txFailed
	s.mu.Unlock()
	if failed != nil {
		return failed
	}
	ctx = context.WithValue(ctx, txKey{}, true)
	before := s.snapshot()
	if err := fn(ctx, s); err != nil {
		s.restore(before) // rollback
		return err
	}
	return nil
}

type txKey struct{}

func inTx(ctx context.Context) bool { v, _ := ctx.Value(txKey{}).(bool); return v }

type storeSnapshot struct {
	deliv  []delivCall
	inbox  []entity.InboxItem
	outbox []entity.OutboxEvent
}

func (s *fakeStore) snapshot() storeSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return storeSnapshot{
		deliv:  append([]delivCall(nil), s.deliv.calls...),
		inbox:  append([]entity.InboxItem(nil), s.inbox.items...),
		outbox: append([]entity.OutboxEvent(nil), s.outbox.events...),
	}
}

func (s *fakeStore) restore(snap storeSnapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deliv.calls = snap.deliv
	s.inbox.items = snap.inbox
	s.outbox.events = snap.outbox
}

func (s *fakeStore) Requests() port.RequestRepository         { return s.reqs }
func (s *fakeStore) Deliveries() port.DeliveryRepository      { return s.deliv }
func (s *fakeStore) Preferences() port.PreferenceRepository   { return s.prefs }
func (s *fakeStore) Suppressions() port.SuppressionRepository { return s.supp }
func (s *fakeStore) Inbox() port.InboxRepository              { return s.inbox }
func (s *fakeStore) Outbox() port.OutboxRepository            { return s.outbox }
func (s *fakeStore) Ordering() port.OrderingRepository        { return s.ord }

// delivCall records one state write on the delivery repository.
type delivCall struct {
	Op        string // release | sent | retry | final | delivered_in_app
	ID        uuid.UUID
	WorkerID  string
	Status    entity.DeliveryStatus
	Reason    string
	Detail    string
	Next      time.Time
	At        time.Time
	Provider  entity.Provider
	MessageID string
	Version   string
	PossDup   bool
	InTx      bool
}

type fakeDeliveries struct {
	port.DeliveryRepository
	store   *fakeStore
	calls   []delivCall
	casFail bool  // every compare-and-set returns false
	err     error // every write returns this error
}

func (f *fakeDeliveries) record(c delivCall) (bool, error) {
	f.store.mu.Lock()
	defer f.store.mu.Unlock()
	if f.err != nil {
		return false, f.err
	}
	if f.casFail {
		return false, nil
	}
	f.calls = append(f.calls, c)
	return true, nil
}

func (f *fakeDeliveries) Release(ctx context.Context, id uuid.UUID, workerID string, next time.Time, code string) (bool, error) {
	return f.record(delivCall{Op: "release", ID: id, WorkerID: workerID, Next: next, Reason: code, InTx: inTx(ctx)})
}

func (f *fakeDeliveries) MarkSent(ctx context.Context, id uuid.UUID, workerID string, provider entity.Provider, msgID, version string, sentAt time.Time, possDup bool) (bool, error) {
	return f.record(delivCall{Op: "sent", ID: id, WorkerID: workerID, Provider: provider, MessageID: msgID, Version: version, At: sentAt, PossDup: possDup, InTx: inTx(ctx)})
}

func (f *fakeDeliveries) MarkRetry(ctx context.Context, id uuid.UUID, workerID string, next time.Time, code, msg string) (bool, error) {
	return f.record(delivCall{Op: "retry", ID: id, WorkerID: workerID, Next: next, Reason: code, Detail: msg, InTx: inTx(ctx)})
}

func (f *fakeDeliveries) MarkFinal(ctx context.Context, id uuid.UUID, workerID string, status entity.DeliveryStatus, reason, detail string, at time.Time) (bool, error) {
	return f.record(delivCall{Op: "final", ID: id, WorkerID: workerID, Status: status, Reason: reason, Detail: detail, At: at, InTx: inTx(ctx)})
}

func (f *fakeDeliveries) MarkDeliveredInApp(ctx context.Context, id uuid.UUID, workerID string, version string, at time.Time) (bool, error) {
	return f.record(delivCall{Op: "delivered_in_app", ID: id, WorkerID: workerID, Version: version, At: at, InTx: inTx(ctx)})
}

func (f *fakeDeliveries) last() delivCall {
	f.store.mu.Lock()
	defer f.store.mu.Unlock()
	if len(f.calls) == 0 {
		return delivCall{}
	}
	return f.calls[len(f.calls)-1]
}

type fakeRequests struct {
	port.RequestRepository
	byID map[uuid.UUID]*entity.Request
	err  error
}

func (f *fakeRequests) GetByID(_ context.Context, id uuid.UUID) (*entity.Request, error) {
	if f.err != nil {
		return nil, f.err
	}
	r, ok := f.byID[id]
	if !ok {
		return nil, errors.ErrNotFound
	}
	return r, nil
}

type fakePreferences struct {
	port.PreferenceRepository
	byUser map[uuid.UUID]*entity.Preference
	err    error
}

func (f *fakePreferences) Get(_ context.Context, userID uuid.UUID) (*entity.Preference, error) {
	if f.err != nil {
		return nil, f.err
	}
	p, ok := f.byUser[userID]
	if !ok {
		return nil, errors.ErrNotFound
	}
	return p, nil
}

type fakeSuppressions struct {
	port.SuppressionRepository
	rows       []entity.Suppression
	superseded []uuid.UUID
}

func (f *fakeSuppressions) ActiveByUser(_ context.Context, userID uuid.UUID) ([]entity.Suppression, error) {
	var out []entity.Suppression
	for _, r := range f.rows {
		if r.UserID != nil && *r.UserID == userID && r.SupersededAt == nil {
			out = append(out, r)
		}
	}
	return out, nil
}

func (f *fakeSuppressions) MarkSuperseded(_ context.Context, id uuid.UUID, at time.Time) error {
	f.superseded = append(f.superseded, id)
	for i := range f.rows {
		if f.rows[i].ID == id {
			t := at
			f.rows[i].SupersededAt = &t
		}
	}
	return nil
}

type fakeInbox struct {
	port.InboxRepository
	store *fakeStore
	items []entity.InboxItem
	err   error
}

func (f *fakeInbox) Insert(_ context.Context, item *entity.InboxItem) error {
	if f.err != nil {
		return f.err
	}
	f.store.mu.Lock()
	defer f.store.mu.Unlock()
	f.items = append(f.items, *item)
	return nil
}

type fakeOutbox struct {
	port.OutboxRepository
	store  *fakeStore
	events []entity.OutboxEvent
}

func (f *fakeOutbox) Insert(ctx context.Context, ev *entity.OutboxEvent) error {
	f.store.mu.Lock()
	defer f.store.mu.Unlock()
	if !inTx(ctx) {
		return stderrors.New("outbox insert outside transaction")
	}
	f.events = append(f.events, *ev)
	return nil
}

func (f *fakeOutbox) types() []string {
	f.store.mu.Lock()
	defer f.store.mu.Unlock()
	var out []string
	for _, e := range f.events {
		out = append(out, e.Type)
	}
	return out
}

func (f *fakeOutbox) lastData() map[string]any {
	f.store.mu.Lock()
	defer f.store.mu.Unlock()
	if len(f.events) == 0 {
		return nil
	}
	var ce entity.CloudEvent
	_ = json.Unmarshal(f.events[len(f.events)-1].Payload, &ce)
	var data map[string]any
	_ = json.Unmarshal(ce.Data, &data)
	return data
}

// ---- external ports -----------------------------------------------------

type fakeRenderer struct {
	emailErr error
	inAppErr error
	seenData []byte
	seenOpts port.RenderOptions
	seenLoc  string
}

func (f *fakeRenderer) RenderEmail(templateID, locale string, data json.RawMessage, opts port.RenderOptions) (*entity.EmailContent, error) {
	f.seenData, f.seenOpts, f.seenLoc = append([]byte(nil), data...), opts, locale
	if f.emailErr != nil {
		return nil, f.emailErr
	}
	return &entity.EmailContent{Subject: "subject " + templateID, HTML: "<p>hi</p>", Text: "hi", Headers: map[string]string{}}, nil
}

func (f *fakeRenderer) RenderInApp(templateID, locale string, data json.RawMessage, opts port.RenderOptions) (*entity.InAppContent, error) {
	f.seenData, f.seenOpts, f.seenLoc = append([]byte(nil), data...), opts, locale
	if f.inAppErr != nil {
		return nil, f.inAppErr
	}
	link := "/orders/1"
	return &entity.InAppContent{Title: "title " + templateID, Body: "body", DeepLink: &link}, nil
}

func (f *fakeRenderer) Version(templateID string) string { return "v" + templateID[:3] }

type fakeSender struct {
	provider entity.Provider
	err      error
	sent     []*entity.EmailMessage
	msgID    string
	onSend   func() // runs inside Send (e.g. advance the clock past a lease)
}

func (f *fakeSender) Provider() entity.Provider { return f.provider }

func (f *fakeSender) Send(_ context.Context, msg *entity.EmailMessage) (*entity.SendResult, error) {
	f.sent = append(f.sent, msg)
	if f.onSend != nil {
		f.onSend()
	}
	if f.err != nil {
		return nil, f.err
	}
	return &entity.SendResult{Provider: f.provider, MessageID: f.msgID}, nil
}

type fakeContacts struct {
	contact    *entity.Contact
	contactErr error
	content    *entity.AuthContent
	contentErr error
	contactN   int
	contentN   int
	lastLane   entity.Lane
	lastID     uuid.UUID
}

func (f *fakeContacts) Contact(_ context.Context, lane entity.Lane, userID uuid.UUID) (*entity.Contact, error) {
	f.contactN++
	f.lastLane, f.lastID = lane, userID
	if f.contactErr != nil {
		return nil, f.contactErr
	}
	return f.contact, nil
}

func (f *fakeContacts) Content(_ context.Context, lane entity.Lane, id uuid.UUID) (*entity.AuthContent, error) {
	f.contentN++
	f.lastLane, f.lastID = lane, id
	if f.contentErr != nil {
		return nil, f.contentErr
	}
	return f.content, nil
}

func (f *fakeContacts) ForgetContact(context.Context, uuid.UUID) error { return nil }

type fakeLimiter struct {
	err   error
	calls int
	wait  time.Duration
}

func (f *fakeLimiter) Acquire(_ context.Context, _ entity.Lane, _ entity.Channel, wait time.Duration) error {
	f.calls++
	f.wait = wait
	return f.err
}

func (f *fakeLimiter) Available(context.Context, entity.Lane, entity.Channel) (float64, error) {
	return 0, nil
}

type fakeDaily struct {
	count int64
	incr  int
	err   error
}

func (f *fakeDaily) Increment(context.Context, entity.Provider, time.Time) (int64, error) {
	f.incr++
	f.count++
	return f.count, f.err
}

func (f *fakeDaily) Get(context.Context, entity.Provider, time.Time) (int64, error) {
	return f.count, f.err
}

type fakeBudget struct {
	ratio   float64
	records []bool
}

func (f *fakeBudget) Record(_ context.Context, _ entity.Lane, isRetry bool) error {
	f.records = append(f.records, isRetry)
	return nil
}

func (f *fakeBudget) Ratio(context.Context, entity.Lane) (float64, error) { return f.ratio, nil }

type fakeUnread struct {
	adds []int64
	err  error
}

func (f *fakeUnread) Get(context.Context, uuid.UUID) (int64, error) { return 0, errors.ErrCacheMiss }
func (f *fakeUnread) Set(context.Context, uuid.UUID, int64) error   { return nil }
func (f *fakeUnread) Add(_ context.Context, _ uuid.UUID, delta int64) error {
	f.adds = append(f.adds, delta)
	return f.err
}
func (f *fakeUnread) Delete(context.Context, uuid.UUID) error { return nil }

type fakeControl struct {
	states map[entity.Lane]entity.LaneState
	err    error
}

func (f *fakeControl) LaneState(_ context.Context, lane entity.Lane) (entity.LaneState, error) {
	if f.err != nil {
		return entity.LaneState{}, f.err
	}
	if s, ok := f.states[lane]; ok {
		return s, nil
	}
	return entity.DefaultLaneState(lane, time.Time{}), nil
}

func (f *fakeControl) SetLaneState(_ context.Context, s entity.LaneState) error {
	if f.states == nil {
		f.states = map[entity.Lane]entity.LaneState{}
	}
	f.states[s.Lane] = s
	return nil
}

func (f *fakeControl) ReplayMode(context.Context) (entity.ReplayMode, error) {
	return entity.ReplayMode{}, nil
}

func (f *fakeControl) SetReplayMode(context.Context, entity.ReplayMode, string) error { return nil }

type fakeBreaker struct {
	allow     bool
	state     int
	successes int
	failures  int
}

func (f *fakeBreaker) Allow() bool { return f.allow }
func (f *fakeBreaker) Success()    { f.successes++ }
func (f *fakeBreaker) Failure()    { f.failures++ }
func (f *fakeBreaker) State() int  { return f.state }
