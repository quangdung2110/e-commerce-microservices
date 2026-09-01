package marker

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/observability"
)

type fakeStore struct {
	mu sync.Mutex

	due       map[int][]port.DueCart
	dueErr    error
	marked    []port.MarkAbandonedCommand
	results   map[string]port.MarkAbandonedResult
	markErr   error
	scanned   []int
	scanLimit int
}

func newFakeStore() *fakeStore {
	return &fakeStore{
		due:     make(map[int][]port.DueCart),
		results: make(map[string]port.MarkAbandonedResult),
	}
}

func (f *fakeStore) LoadItems(context.Context, domain.Identity) ([]domain.Item, error) {
	return nil, nil
}

func (f *fakeStore) Upsert(context.Context, port.UpsertCommand) (port.UpsertResult, error) {
	return port.UpsertResult{}, nil
}

func (f *fakeStore) Remove(context.Context, port.RemoveCommand) (port.RemoveResult, error) {
	return port.RemoveResult{}, nil
}

func (f *fakeStore) MarkAbandoned(_ context.Context, cmd port.MarkAbandonedCommand) (port.MarkAbandonedResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.marked = append(f.marked, cmd)
	if f.markErr != nil {
		return port.MarkAbandonedResult{}, f.markErr
	}
	if result, ok := f.results[cmd.Identity.UserToken]; ok {
		return result, nil
	}
	return port.MarkAbandonedResult{Marked: true, AbandonmentCycle: 1, StreamID: "1-0"}, nil
}

func (f *fakeStore) DueCarts(_ context.Context, bucket int, _ time.Time, limit int) ([]port.DueCart, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.scanned = append(f.scanned, bucket)
	f.scanLimit = limit
	if f.dueErr != nil {
		return nil, f.dueErr
	}
	return f.due[bucket], nil
}

func (f *fakeStore) DropIndexMember(context.Context, int, string) error { return nil }
func (f *fakeStore) Ping(context.Context) error                         { return nil }

func newTestWorker(store *fakeStore) *Worker {
	worker := New(store, Config{BatchSize: 200, OperationTimeout: time.Second},
		observability.NewMetrics(), zap.NewNop())
	worker.now = func() time.Time { return time.UnixMilli(1780000000000) }
	return worker
}

// Every bucket has to be visited, or carts in the buckets that are skipped are
// never marked and never notified.
func TestMarkerScansEveryBucket(t *testing.T) {
	store := newFakeStore()

	newTestWorker(store).pass(context.Background())

	if len(store.scanned) != domain.BucketCount {
		t.Fatalf("scanned %d buckets, want %d", len(store.scanned), domain.BucketCount)
	}
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		if store.scanned[bucket] != bucket {
			t.Fatalf("visit %d was bucket %d; buckets must be walked round-robin", bucket, store.scanned[bucket])
		}
	}
	if store.scanLimit != 200 {
		t.Fatalf("scan limit = %d, want the configured batch size", store.scanLimit)
	}
}

// The deadline the marker read has to be handed to the script verbatim: it is
// the only thing that lets the script detect a user write that raced it.
func TestMarkerPassesTheObservedDeadlineToTheScript(t *testing.T) {
	store := newFakeStore()
	store.due[7] = []port.DueCart{{UserToken: "TOKEN-A", Deadline: 1779000000000}}

	newTestWorker(store).pass(context.Background())

	if len(store.marked) != 1 {
		t.Fatalf("marked %d carts, want 1", len(store.marked))
	}
	cmd := store.marked[0]
	if cmd.ExpectedDeadline != 1779000000000 {
		t.Fatalf("expected deadline = %d, want the score the marker read", cmd.ExpectedDeadline)
	}
	if cmd.Identity.Bucket != 7 || cmd.Identity.UserToken != "TOKEN-A" {
		t.Fatalf("identity = %+v", cmd.Identity)
	}
	// The marker only ever sees a token; the script reads the user id out of
	// the meta hash.
	if cmd.Identity.UserID != "" {
		t.Fatalf("marker invented a user id: %q", cmd.Identity.UserID)
	}
	if cmd.Identity.CartID != "c1:TOKEN-A" {
		t.Fatalf("cart id = %q", cmd.Identity.CartID)
	}
}

func TestMarkerProcessesEveryDueCartInABucket(t *testing.T) {
	store := newFakeStore()
	store.due[3] = []port.DueCart{
		{UserToken: "TOKEN-A", Deadline: 1779000000000},
		{UserToken: "TOKEN-B", Deadline: 1779000000001},
		{UserToken: "TOKEN-C", Deadline: 1779000000002},
	}

	newTestWorker(store).pass(context.Background())

	if len(store.marked) != 3 {
		t.Fatalf("marked %d carts, want 3", len(store.marked))
	}
}

// A refused transition is an expected outcome, not a failure, and the loop has
// to keep going.
func TestMarkerContinuesPastARefusedTransition(t *testing.T) {
	store := newFakeStore()
	store.due[3] = []port.DueCart{
		{UserToken: "TOKEN-STALE", Deadline: 1779000000000},
		{UserToken: "TOKEN-OK", Deadline: 1779000000001},
	}
	store.results["TOKEN-STALE"] = port.MarkAbandonedResult{Marked: false, Reason: "STALE_DEADLINE"}

	newTestWorker(store).pass(context.Background())

	if len(store.marked) != 2 {
		t.Fatalf("marked %d carts; a refusal stopped the loop", len(store.marked))
	}
}

// A Redis failure in one bucket must not stop the pass over the other 63.
func TestMarkerSurvivesAFailedBucket(t *testing.T) {
	store := newFakeStore()
	store.dueErr = errors.New("redis is unavailable")

	newTestWorker(store).pass(context.Background())

	if len(store.scanned) != domain.BucketCount {
		t.Fatalf("scanned %d buckets after an error, want %d", len(store.scanned), domain.BucketCount)
	}
}

func TestMarkerSurvivesAFailedTransition(t *testing.T) {
	store := newFakeStore()
	store.due[3] = []port.DueCart{
		{UserToken: "TOKEN-A", Deadline: 1779000000000},
		{UserToken: "TOKEN-B", Deadline: 1779000000001},
	}
	store.markErr = errors.New("redis is unavailable")

	newTestWorker(store).pass(context.Background())

	if len(store.marked) != 2 {
		t.Fatalf("attempted %d transitions after an error, want 2", len(store.marked))
	}
}

// A cancelled context must stop the pass promptly rather than walking all 64
// buckets during a shutdown.
func TestMarkerStopsOnContextCancellation(t *testing.T) {
	store := newFakeStore()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	newTestWorker(store).pass(ctx)

	if len(store.scanned) != 0 {
		t.Fatalf("scanned %d buckets after cancellation", len(store.scanned))
	}
}
