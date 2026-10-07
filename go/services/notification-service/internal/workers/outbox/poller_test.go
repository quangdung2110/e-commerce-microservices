package outbox

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var now = time.Date(2026, 9, 22, 8, 0, 0, 0, time.UTC)

type fakeOutbox struct {
	port.OutboxRepository
	rows      []entity.OutboxEvent
	published map[uuid.UUID]time.Time
}

func (f *fakeOutbox) FetchUnpublished(_ context.Context, limit int) ([]entity.OutboxEvent, error) {
	var out []entity.OutboxEvent
	for _, r := range f.rows {
		if _, ok := f.published[r.ID]; ok {
			continue
		}
		out = append(out, r)
		if len(out) == limit {
			break
		}
	}
	return out, nil
}

func (f *fakeOutbox) MarkPublished(_ context.Context, ids []uuid.UUID, at time.Time) error {
	for _, id := range ids {
		f.published[id] = at
	}
	return nil
}

func (f *fakeOutbox) Stats(_ context.Context, _ time.Time) (int64, time.Duration, error) {
	return int64(len(f.rows) - len(f.published)), 7 * time.Second, nil
}

type fakeTx struct {
	port.Tx
	outbox *fakeOutbox
}

func (f *fakeTx) Outbox() port.OutboxRepository { return f.outbox }

type fakeStore struct {
	port.Store
	tx *fakeTx
}

func (f *fakeStore) WithinTx(ctx context.Context, fn func(context.Context, port.Tx) error) error {
	snapshot := make(map[uuid.UUID]time.Time, len(f.tx.outbox.published))
	for k, v := range f.tx.outbox.published {
		snapshot[k] = v
	}
	if err := fn(ctx, f.tx); err != nil {
		f.tx.outbox.published = snapshot // rollback
		return err
	}
	return nil
}

func (f *fakeStore) Outbox() port.OutboxRepository { return f.tx.outbox }

type fakePublisher struct {
	calls   []string // "topic|key|id"
	failIdx int      // fail on the n-th call (1-based); 0 = never
	n       int
}

func (p *fakePublisher) Publish(_ context.Context, topic, key string, ev *entity.OutboxEvent) error {
	p.n++
	if p.failIdx != 0 && p.n == p.failIdx {
		return errors.New("broker unavailable")
	}
	p.calls = append(p.calls, topic+"|"+key+"|"+ev.ID.String())
	return nil
}

func rows(n int) []entity.OutboxEvent {
	out := make([]entity.OutboxEvent, n)
	for i := range out {
		out[i] = entity.OutboxEvent{ID: uuid.New(), AggregateType: "notification", AggregateID: "user-" + string(rune('a'+i)), Type: entity.EventTypeDelivered, Payload: []byte(`{}`), CreatedAt: now}
	}
	return out
}

func newWorker(publisher string, batch int, store *fakeStore, pub port.EventPublisher) (*Worker, *telemetry.Metrics) {
	m := telemetry.New()
	cfg := config.OutboxConfig{Publisher: publisher, PollInterval: 10 * time.Millisecond, Batch: batch}
	return New(cfg, "t.notification.outcomes.v1", 10*time.Millisecond, store, pub, clock.NewFake(now), m, nil), m
}

func TestPublishOnce_PublishesAndMarks(t *testing.T) {
	store := &fakeStore{tx: &fakeTx{outbox: &fakeOutbox{rows: rows(3), published: map[uuid.UUID]time.Time{}}}}
	pub := &fakePublisher{}
	w, _ := newWorker(PublisherPoller, 2, store, pub)

	n, err := w.PublishOnce(context.Background())
	require.NoError(t, err)
	require.Equal(t, 2, n, "bounded by batch")
	require.Len(t, store.tx.outbox.published, 2)
	require.Equal(t, "t.notification.outcomes.v1|user-a|"+store.tx.outbox.rows[0].ID.String(), pub.calls[0])
	require.Equal(t, now, store.tx.outbox.published[store.tx.outbox.rows[0].ID])

	n, err = w.PublishOnce(context.Background())
	require.NoError(t, err)
	require.Equal(t, 1, n)
	n, err = w.PublishOnce(context.Background())
	require.NoError(t, err)
	require.Equal(t, 0, n)
}

func TestPublishOnce_FailureRollsBackAndRepublishesSameID(t *testing.T) {
	store := &fakeStore{tx: &fakeTx{outbox: &fakeOutbox{rows: rows(2), published: map[uuid.UUID]time.Time{}}}}
	pub := &fakePublisher{failIdx: 2}
	w, _ := newWorker(PublisherPoller, 10, store, pub)

	_, err := w.PublishOnce(context.Background())
	require.Error(t, err)
	require.Empty(t, store.tx.outbox.published, "batch rolled back")

	// Next tick republishes both rows with the same ids (layer D).
	n, err := w.PublishOnce(context.Background())
	require.NoError(t, err)
	require.Equal(t, 2, n)
	first := store.tx.outbox.rows[0].ID.String()
	require.Contains(t, pub.calls[0], first)
	require.Contains(t, pub.calls[1], first)
}

func TestRun_DebeziumModeOnlyExportsGauges(t *testing.T) {
	store := &fakeStore{tx: &fakeTx{outbox: &fakeOutbox{rows: rows(4), published: map[uuid.UUID]time.Time{}}}}
	pub := &fakePublisher{}
	w, m := newWorker(PublisherDebezium, 10, store, pub)
	require.False(t, w.PublishEnabled())

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
	defer cancel()
	err := w.Run(ctx)
	require.ErrorIs(t, err, context.DeadlineExceeded)
	require.Empty(t, pub.calls, "debezium mode never publishes")
	require.Empty(t, store.tx.outbox.published)

	mfs, err := m.Registry.Gather()
	require.NoError(t, err)
	got := map[string]float64{}
	for _, mf := range mfs {
		if len(mf.GetMetric()) > 0 && mf.GetMetric()[0].GetGauge() != nil {
			got[mf.GetName()] = mf.GetMetric()[0].GetGauge().GetValue()
		}
	}
	require.Equal(t, 4.0, got["notification_outbox_unpublished_count"])
	require.Equal(t, 7.0, got["notification_outbox_oldest_age_seconds"])
}

func TestRun_PollerModePublishesUntilCancelled(t *testing.T) {
	store := &fakeStore{tx: &fakeTx{outbox: &fakeOutbox{rows: rows(5), published: map[uuid.UUID]time.Time{}}}}
	pub := &fakePublisher{}
	w, _ := newWorker(PublisherPoller, 2, store, pub)

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	_ = w.Run(ctx)
	require.Len(t, store.tx.outbox.published, 5)

	// Poller mode without a publisher is a wiring bug.
	w2, _ := newWorker(PublisherPoller, 2, store, nil)
	require.Error(t, w2.Run(context.Background()))
}
