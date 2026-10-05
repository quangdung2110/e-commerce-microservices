package track

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperrors "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	tracksvc "github.com/ecommerce/notification-service/internal/domain/service/track"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var now = time.Date(2026, 9, 22, 8, 0, 0, 0, time.UTC)

func cfg() config.TrackConfig {
	return config.TrackConfig{
		WorkerBatch: 50, WorkerPoll: time.Second,
		UnmatchedRetry: 30 * time.Second, UnmatchedAlertAfter: 3, UnmatchedGiveUp: 24 * time.Hour,
	}
}

func TestPlanUnmatched(t *testing.T) {
	c := cfg()
	received := now.Add(-time.Minute)

	cases := []struct {
		name        string
		prevAttempt int
		receivedAt  time.Time
		wantAttempt int
		wantNext    time.Time
		wantAlert   bool
		wantGiveUp  bool
	}{
		{"first miss", 0, received, 1, now.Add(30 * time.Second), false, false},
		{"second miss", 1, received, 2, now.Add(60 * time.Second), false, false},
		{"third miss alerts", 2, received, 3, now.Add(90 * time.Second), true, false},
		{"linear backoff caps at 10x", 25, received, 26, now.Add(300 * time.Second), true, false},
		{"older than give-up", 5, now.Add(-24 * time.Hour), 6, now.Add(180 * time.Second), true, true},
		{"just inside give-up", 5, now.Add(-24*time.Hour + time.Second), 6, now.Add(180 * time.Second), true, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p := PlanUnmatched(tc.prevAttempt, tc.receivedAt, now, c)
			require.Equal(t, tc.wantAttempt, p.Attempt)
			require.Equal(t, tc.wantNext, p.NextAttemptAt)
			require.Equal(t, tc.wantAlert, p.Alert)
			require.Equal(t, tc.wantGiveUp, p.GiveUp)
		})
	}

	// Zero config falls back to the documented defaults (30 s, 3, 24 h).
	p := PlanUnmatched(0, received, now, config.TrackConfig{})
	require.Equal(t, now.Add(30*time.Second), p.NextAttemptAt)
	require.False(t, p.Alert)
	require.False(t, p.GiveUp)
	require.True(t, PlanUnmatched(2, received, now, config.TrackConfig{}).Alert)
}

// ---- worker loop with fakes ---------------------------------------------

type fakeEvents struct {
	port.ProviderEventRepository
	ready    []entity.ProviderEvent
	statuses map[int64]entity.ProviderEventStatus
	resched  map[int64]time.Time
	attempts map[int64]int
	claimErr error
}

func (f *fakeEvents) Claim(_ context.Context, batch int, _ time.Time) ([]entity.ProviderEvent, error) {
	if f.claimErr != nil {
		return nil, f.claimErr
	}
	if len(f.ready) > batch {
		out := f.ready[:batch]
		f.ready = f.ready[batch:]
		return out, nil
	}
	out := f.ready
	f.ready = nil
	return out, nil
}

func (f *fakeEvents) SetStatus(_ context.Context, id int64, _ time.Time, st entity.ProviderEventStatus) error {
	f.statuses[id] = st
	return nil
}

func (f *fakeEvents) Reschedule(_ context.Context, id int64, _ time.Time, attempt int, next time.Time) error {
	f.attempts[id] = attempt
	f.resched[id] = next
	return nil
}

func (f *fakeEvents) OldestUnmatchedAge(_ context.Context, _ time.Time) (time.Duration, error) {
	return 42 * time.Second, nil
}

type fakeDeliveries struct {
	port.DeliveryRepository
	err error
}

func (f *fakeDeliveries) FindByProviderMessage(_ context.Context, _ entity.Provider, _ string) (*entity.Delivery, error) {
	if f.err != nil {
		return nil, f.err
	}
	return nil, apperrors.ErrNotFound
}

type fakeTx struct {
	port.Tx
	events     *fakeEvents
	deliveries *fakeDeliveries
}

func (f *fakeTx) ProviderEvents() port.ProviderEventRepository { return f.events }
func (f *fakeTx) Deliveries() port.DeliveryRepository          { return f.deliveries }

type fakeStore struct {
	port.Store
	tx        *fakeTx
	rollbacks int
}

func (f *fakeStore) WithinTx(ctx context.Context, fn func(context.Context, port.Tx) error) error {
	if err := fn(ctx, f.tx); err != nil {
		f.rollbacks++
		return err
	}
	return nil
}

func (f *fakeStore) ProviderEvents() port.ProviderEventRepository { return f.tx.events }

func newWorker(t *testing.T, events []entity.ProviderEvent, findErr error) (*Worker, *fakeStore, *telemetry.Metrics) {
	t.Helper()
	store := &fakeStore{tx: &fakeTx{
		events:     &fakeEvents{ready: events, statuses: map[int64]entity.ProviderEventStatus{}, resched: map[int64]time.Time{}, attempts: map[int64]int{}},
		deliveries: &fakeDeliveries{err: findErr},
	}}
	m := telemetry.New()
	clk := clock.NewFake(now)
	svc := tracksvc.New(cfg(), clk, m, nil)
	return New(cfg(), store, svc, clk, m, nil), store, m
}

func ev(id int64, typ entity.ProviderEventType, attempt int, receivedAt time.Time) entity.ProviderEvent {
	mid := "msg-" + string(rune('a'+id))
	return entity.ProviderEvent{ID: id, Provider: entity.ProviderSES, ProviderMessageID: &mid, EventType: typ, Attempt: attempt, ReceivedAt: receivedAt, Status: entity.ProviderEventPending}
}

func TestRunOnce_ProcessedUnmatchedAndGiveUp(t *testing.T) {
	events := []entity.ProviderEvent{
		ev(1, entity.ProviderEventOther, 0, now),                       // ignored → PROCESSED
		ev(2, entity.ProviderEventDelivery, 0, now.Add(-time.Minute)),  // unmatched → reschedule attempt 1
		ev(3, entity.ProviderEventDelivery, 4, now.Add(-time.Minute)),  // unmatched → attempt 5, alert
		ev(4, entity.ProviderEventDelivery, 9, now.Add(-25*time.Hour)), // past give-up → IGNORED
	}
	w, store, _ := newWorker(t, events, nil)
	n, err := w.RunOnce(context.Background())
	require.NoError(t, err)
	require.Equal(t, 4, n)
	require.Equal(t, 0, store.rollbacks)

	st := store.tx.events.statuses
	require.Equal(t, entity.ProviderEventProcessed, st[1])
	require.Equal(t, entity.ProviderEventUnmatched, st[2])
	require.Equal(t, entity.ProviderEventUnmatched, st[3])
	require.Equal(t, entity.ProviderEventIgnored, st[4])

	require.Equal(t, 1, store.tx.events.attempts[2])
	require.Equal(t, now.Add(30*time.Second), store.tx.events.resched[2])
	require.Equal(t, 5, store.tx.events.attempts[3])
	require.Equal(t, now.Add(150*time.Second), store.tx.events.resched[3])
	_, rescheduled := store.tx.events.resched[4]
	require.False(t, rescheduled, "given-up rows are not rescheduled")
}

func TestRunOnce_ErrorRollsBackBatch(t *testing.T) {
	events := []entity.ProviderEvent{ev(1, entity.ProviderEventDelivery, 0, now)}
	w, store, _ := newWorker(t, events, errors.New("db down"))
	_, err := w.RunOnce(context.Background())
	require.Error(t, err)
	require.Equal(t, 1, store.rollbacks)
	require.Empty(t, store.tx.events.statuses)
}

func TestRun_StopsOnCancelAndExportsGauge(t *testing.T) {
	w, _, m := newWorker(t, nil, nil)
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(50 * time.Millisecond)
		cancel()
	}()
	err := w.Run(ctx)
	require.ErrorIs(t, err, context.Canceled)

	// Gauge exported from OldestUnmatchedAge at least once.
	mfs, err := m.Registry.Gather()
	require.NoError(t, err)
	found := false
	for _, mf := range mfs {
		if mf.GetName() == "notification_provider_event_unmatched_age_seconds" {
			found = true
			require.Equal(t, 42.0, mf.GetMetric()[0].GetGauge().GetValue())
		}
	}
	require.True(t, found)
}
