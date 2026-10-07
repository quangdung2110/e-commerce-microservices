package trimmer

import (
	"context"
	"errors"
	"testing"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/infrastructure/observability"
)

type fakeOutbox struct {
	streams      map[int]bool
	watermark    map[int]string
	safe         map[int]bool
	watermarkErr error

	trimmed map[int]string
	probed  []int
	trimErr error
}

func newFakeOutbox() *fakeOutbox {
	return &fakeOutbox{
		streams:   make(map[int]bool),
		watermark: make(map[int]string),
		safe:      make(map[int]bool),
		trimmed:   make(map[int]string),
	}
}

func (f *fakeOutbox) StreamExists(_ context.Context, bucket int) (bool, error) {
	f.probed = append(f.probed, bucket)
	return f.streams[bucket], nil
}

func (f *fakeOutbox) EnsureGroup(context.Context, int) error { return nil }

func (f *fakeOutbox) Claim(context.Context, int, int, time.Duration) ([]domain.OutboxRecord, error) {
	return nil, nil
}

func (f *fakeOutbox) Read(context.Context, int, int) ([]domain.OutboxRecord, error) {
	return nil, nil
}

func (f *fakeOutbox) Ack(context.Context, int, string) error { return nil }

func (f *fakeOutbox) TrimWatermark(_ context.Context, bucket int) (string, bool, error) {
	if f.watermarkErr != nil {
		return "", false, f.watermarkErr
	}
	return f.watermark[bucket], f.safe[bucket], nil
}

func (f *fakeOutbox) Trim(_ context.Context, bucket int, minID string) (int64, error) {
	if f.trimErr != nil {
		return 0, f.trimErr
	}
	f.trimmed[bucket] = minID
	return 3, nil
}

func (f *fakeOutbox) OutboxKey(bucket int) string {
	return "cart:v2:" + domain.BucketTag(bucket) + ":outbox"
}

func newTestWorker(outbox *fakeOutbox) *Worker {
	return New(outbox, Config{OperationTimeout: time.Second}, observability.NewMetrics(), zap.NewNop())
}

func TestTrimmerTrimsAtTheReportedWatermark(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.streams[5] = true
	outbox.safe[5] = true
	outbox.watermark[5] = "1780000000000-0"

	newTestWorker(outbox).pass(context.Background())

	if outbox.trimmed[5] != "1780000000000-0" {
		t.Fatalf("trimmed at %q, want the reported watermark", outbox.trimmed[5])
	}
}

// The watermark is the whole safety mechanism. If the store says trimming is
// unsafe, no XTRIM may be issued at all.
func TestTrimmerSkipsWhenTrimmingIsUnsafe(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.streams[5] = true
	outbox.safe[5] = false
	outbox.watermark[5] = "1780000000000-0"

	newTestWorker(outbox).pass(context.Background())

	if len(outbox.trimmed) != 0 {
		t.Fatalf("trimmed %v despite an unsafe watermark", outbox.trimmed)
	}
}

// Streams are created lazily, so most buckets are empty and must be skipped
// without further Redis calls.
func TestTrimmerSkipsBucketsWithNoStream(t *testing.T) {
	outbox := newFakeOutbox()

	newTestWorker(outbox).pass(context.Background())

	if len(outbox.probed) != domain.BucketCount {
		t.Fatalf("probed %d buckets, want %d", len(outbox.probed), domain.BucketCount)
	}
	if len(outbox.trimmed) != 0 {
		t.Fatalf("trimmed %v with no streams present", outbox.trimmed)
	}
}

func TestTrimmerVisitsEveryBucket(t *testing.T) {
	outbox := newFakeOutbox()
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		outbox.streams[bucket] = true
		outbox.safe[bucket] = true
		outbox.watermark[bucket] = "1-0"
	}

	newTestWorker(outbox).pass(context.Background())

	if len(outbox.trimmed) != domain.BucketCount {
		t.Fatalf("trimmed %d buckets, want %d", len(outbox.trimmed), domain.BucketCount)
	}
}

func TestTrimmerSurvivesAFailedBucket(t *testing.T) {
	outbox := newFakeOutbox()
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		outbox.streams[bucket] = true
	}
	outbox.watermarkErr = errors.New("redis is unavailable")

	newTestWorker(outbox).pass(context.Background())

	if len(outbox.probed) != domain.BucketCount {
		t.Fatalf("probed %d buckets after an error, want %d", len(outbox.probed), domain.BucketCount)
	}
}

func TestTrimmerStopsOnContextCancellation(t *testing.T) {
	outbox := newFakeOutbox()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	newTestWorker(outbox).pass(ctx)

	if len(outbox.probed) != 0 {
		t.Fatalf("probed %d buckets after cancellation", len(outbox.probed))
	}
}
