package relay

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/infrastructure/observability"
)

// fakeOutbox records what the relay asked for and what it acked.
type fakeOutbox struct {
	mu sync.Mutex

	pending  map[int][]domain.OutboxRecord
	fresh    map[int][]domain.OutboxRecord
	acked    []string
	fetchLog []string
	inflight map[string]bool
}

func newFakeOutbox() *fakeOutbox {
	return &fakeOutbox{
		pending:  make(map[int][]domain.OutboxRecord),
		fresh:    make(map[int][]domain.OutboxRecord),
		inflight: make(map[string]bool),
	}
}

func (f *fakeOutbox) track(records []domain.OutboxRecord) {
	for _, record := range records {
		f.inflight[record.StreamID] = true
	}
}

func (f *fakeOutbox) PendingCount(context.Context, int) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return int64(len(f.inflight)), nil
}

func (f *fakeOutbox) StreamExists(_ context.Context, bucket int) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.pending[bucket]) > 0 || len(f.fresh[bucket]) > 0, nil
}

func (f *fakeOutbox) EnsureGroup(context.Context, int) error { return nil }

func (f *fakeOutbox) Claim(_ context.Context, bucket, _ int, _ time.Duration) ([]domain.OutboxRecord, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.fetchLog = append(f.fetchLog, "claim")
	records := f.pending[bucket]
	f.pending[bucket] = nil
	f.track(records)
	return records, nil
}

func (f *fakeOutbox) Read(_ context.Context, bucket, _ int) ([]domain.OutboxRecord, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.fetchLog = append(f.fetchLog, "read")
	records := f.fresh[bucket]
	f.fresh[bucket] = nil
	f.track(records)
	return records, nil
}

func (f *fakeOutbox) Ack(_ context.Context, _ int, streamID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.acked = append(f.acked, streamID)
	delete(f.inflight, streamID)
	return nil
}

func (f *fakeOutbox) TrimWatermark(context.Context, int) (string, bool, error) {
	return "", false, nil
}

func (f *fakeOutbox) Trim(context.Context, int, string) (int64, error) { return 0, nil }

func (f *fakeOutbox) OutboxKey(bucket int) string {
	return "cart:v2:" + domain.BucketTag(bucket) + ":outbox"
}

// fakePublisher records published events and can be made to fail.
type fakePublisher struct {
	mu sync.Mutex

	published   []domain.CloudEvent
	headers     []map[string]string
	deadLetters []string
	failures    int
	err         error
}

func (f *fakePublisher) Publish(_ context.Context, event domain.CloudEvent, headers map[string]string) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	if f.failures > 0 {
		f.failures--
		f.published = append(f.published, event)
		return errors.New("kafka is unreachable")
	}
	if f.err != nil {
		f.published = append(f.published, event)
		return f.err
	}
	f.published = append(f.published, event)
	f.headers = append(f.headers, headers)
	return nil
}

func (f *fakePublisher) PublishDeadLetter(_ context.Context, key string, _ []byte, _ map[string]string) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.deadLetters = append(f.deadLetters, key)
	return nil
}

func (f *fakePublisher) Ready(context.Context) error { return nil }
func (f *fakePublisher) Close() error                { return nil }

func newTestWorker(outbox *fakeOutbox, publisher *fakePublisher) *Worker {
	return New(outbox, publisher, Config{
		MaxPublishAttempts: 3,
		PublishBackoff:     time.Millisecond,
		OperationTimeout:   time.Second,
		DataSchema:         "/contracts/events/cart/v1/schemas/cart-abandoned.schema.json",
	}, observability.NewMetrics(), zap.NewNop())
}

func abandonedRecord(bucket int, streamID string) domain.OutboxRecord {
	return domain.OutboxRecord{
		StreamID:  streamID,
		Bucket:    bucket,
		EventType: domain.EventTypeCartAbandoned,
		Payload: domain.AbandonedPayload{
			CartID:           "c1:TOKEN",
			UserID:           "0198e098-94f4-7f8f-9e16-ae42dfbd93c1",
			AbandonmentCycle: 1,
			OccurredAt:       domain.Millis(1780000000000),
		},
	}
}

// The published envelope is the contract Notification consumes. Every field
// here is pinned by the AsyncAPI document and the CloudEvents schema.
func TestRelayPublishesAContractShapedCloudEvent(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.fresh[17] = []domain.OutboxRecord{abandonedRecord(17, "1780000000000-0")}
	publisher := &fakePublisher{}

	newTestWorker(outbox, publisher).pass(context.Background())

	if len(publisher.published) != 1 {
		t.Fatalf("published %d events, want 1", len(publisher.published))
	}
	event := publisher.published[0]

	if event.SpecVersion != "1.0" {
		t.Fatalf("specversion = %q", event.SpecVersion)
	}
	if event.Type != "com.ecommerce.cart.abandoned.v1" {
		t.Fatalf("type = %q", event.Type)
	}
	if event.Source != "/services/cart" {
		t.Fatalf("source = %q", event.Source)
	}
	if event.Subject != "carts/c1:TOKEN" {
		t.Fatalf("subject = %q", event.Subject)
	}
	if event.DataContentType != "application/json" {
		t.Fatalf("datacontenttype = %q", event.DataContentType)
	}
	if event.DataSchema == "" {
		t.Fatal("dataschema is required by the shared CloudEvent schema")
	}
	// The Redis record carries milliseconds; the wire contract carries RFC 3339.
	if event.Time != "2026-05-28T20:26:40Z" {
		t.Fatalf("time = %q", event.Time)
	}
	if event.Data.OccurredAt != event.Time {
		t.Fatalf("data.occurred_at = %q, want %q", event.Data.OccurredAt, event.Time)
	}
	if event.Data.AbandonmentCycle != 1 {
		t.Fatalf("abandonment_cycle = %d", event.Data.AbandonmentCycle)
	}

	// id = cart-outbox:v2:<outbox_key>:<stream_id>
	wantID := "cart-outbox:v2:cart:v2:{b17}:outbox:1780000000000-0"
	if event.ID != wantID {
		t.Fatalf("id = %q, want %q", event.ID, wantID)
	}
	if len(event.ID) > 128 {
		t.Fatalf("id is %d characters, the schema caps it at 128", len(event.ID))
	}

	headers := publisher.headers[0]
	for _, required := range []string{"outbox_bucket", "stream_id", "cart_id"} {
		if headers[required] == "" {
			t.Fatalf("required header %q is missing", required)
		}
	}
	if headers["outbox_bucket"] != "17" || headers["stream_id"] != "1780000000000-0" {
		t.Fatalf("headers = %+v", headers)
	}
}

// The event carries internal identifiers only. Notification gets no PII.
func TestRelayEventCarriesNoPersonalData(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.fresh[17] = []domain.OutboxRecord{abandonedRecord(17, "1-0")}
	publisher := &fakePublisher{}

	newTestWorker(outbox, publisher).pass(context.Background())

	event := publisher.published[0]
	if event.Data.CartID == "" || event.Data.UserID == "" {
		t.Fatal("the payload lost an identifier it needs")
	}
	// The struct is closed, so this asserts the shape stays that way: only the
	// four contract fields exist on AbandonedData.
	if (domain.AbandonedData{
		CartID:           event.Data.CartID,
		UserID:           event.Data.UserID,
		AbandonmentCycle: event.Data.AbandonmentCycle,
		OccurredAt:       event.Data.OccurredAt,
	}) != event.Data {
		t.Fatal("the payload carries fields beyond the contract")
	}
}

// Nothing may be acked before Kafka confirms the write. Acking first would
// leave a cart abandoned with no event and no way to detect it.
func TestRelayDoesNotAckWhenKafkaFails(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.fresh[3] = []domain.OutboxRecord{abandonedRecord(3, "1-0")}
	publisher := &fakePublisher{err: errors.New("kafka is unreachable")}

	newTestWorker(outbox, publisher).pass(context.Background())

	if len(outbox.acked) != 0 {
		t.Fatalf("acked %v despite a kafka failure", outbox.acked)
	}
	if len(publisher.published) != 3 {
		t.Fatalf("attempted %d publishes, want 3 retries", len(publisher.published))
	}
}

// A retried entry must publish under the same CloudEvent id, or Notification
// cannot deduplicate the at-least-once delivery.
func TestRelayKeepsTheEventIDStableAcrossRetries(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.fresh[3] = []domain.OutboxRecord{abandonedRecord(3, "1780000000000-0")}
	publisher := &fakePublisher{failures: 2}

	newTestWorker(outbox, publisher).pass(context.Background())

	if len(publisher.published) != 3 {
		t.Fatalf("published %d times, want 3", len(publisher.published))
	}
	first := publisher.published[0].ID
	for i, event := range publisher.published {
		if event.ID != first {
			t.Fatalf("attempt %d used id %q, want %q", i, event.ID, first)
		}
	}
	if len(outbox.acked) != 1 {
		t.Fatalf("acked %v, want exactly one ack after the retry succeeded", outbox.acked)
	}
}

// An entry a crashed relay left pending is older than anything a fresh read
// returns, so it has to go first or the stream is reordered.
func TestRelayDrainsPendingEntriesBeforeNewOnes(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.pending[5] = []domain.OutboxRecord{abandonedRecord(5, "1-0")}
	outbox.fresh[5] = []domain.OutboxRecord{abandonedRecord(5, "2-0")}
	publisher := &fakePublisher{}

	newTestWorker(outbox, publisher).pass(context.Background())

	if len(outbox.fetchLog) < 2 || outbox.fetchLog[0] != "claim" {
		t.Fatalf("fetch order = %v, want claim first", outbox.fetchLog)
	}
	if len(outbox.acked) != 2 || outbox.acked[0] != "1-0" || outbox.acked[1] != "2-0" {
		t.Fatalf("acked %v, want [1-0 2-0]", outbox.acked)
	}
}

// Stopping at the first failure keeps a later abandonment from overtaking an
// earlier one within the same bucket.
func TestRelayStopsABucketAtTheFirstFailure(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.fresh[5] = []domain.OutboxRecord{
		abandonedRecord(5, "1-0"),
		abandonedRecord(5, "2-0"),
	}
	publisher := &fakePublisher{err: errors.New("kafka is unreachable")}

	newTestWorker(outbox, publisher).pass(context.Background())

	// Three attempts for the first entry, and none for the second.
	if len(publisher.published) != 3 {
		t.Fatalf("published %d times, want 3", len(publisher.published))
	}
	for _, event := range publisher.published {
		if !strings.HasSuffix(event.ID, "1-0") {
			t.Fatalf("relay moved past a failed entry: %q", event.ID)
		}
	}
}

// A malformed entry can never succeed, so retrying it would block its bucket
// forever. It goes to the DLQ and is acked.
func TestRelayDeadLettersAMalformedEntry(t *testing.T) {
	outbox := newFakeOutbox()
	outbox.fresh[9] = []domain.OutboxRecord{{
		StreamID:  "1-0",
		Bucket:    9,
		EventType: domain.EventTypeCartAbandoned,
		// No payload: DecodeAbandonedPayload rejected it at read time.
	}}
	publisher := &fakePublisher{}

	newTestWorker(outbox, publisher).pass(context.Background())

	if len(publisher.published) != 0 {
		t.Fatal("a malformed entry was published to the cart topic")
	}
	if len(publisher.deadLetters) != 1 {
		t.Fatalf("dead letters = %v, want one", publisher.deadLetters)
	}
	if len(outbox.acked) != 1 {
		t.Fatalf("acked %v, want the malformed entry acked so it stops blocking", outbox.acked)
	}
}

// An entry with an event type Cart does not publish is equally poisonous.
func TestRelayDeadLettersAnUnknownEventType(t *testing.T) {
	outbox := newFakeOutbox()
	record := abandonedRecord(9, "1-0")
	record.EventType = "CART_ITEM_ADDED"
	outbox.fresh[9] = []domain.OutboxRecord{record}
	publisher := &fakePublisher{}

	newTestWorker(outbox, publisher).pass(context.Background())

	if len(publisher.published) != 0 {
		t.Fatal("an unknown event type reached the cart topic")
	}
	if len(publisher.deadLetters) != 1 {
		t.Fatalf("dead letters = %v, want one", publisher.deadLetters)
	}
}

func TestRelaySkipsBucketsWithNoStream(t *testing.T) {
	outbox := newFakeOutbox()
	publisher := &fakePublisher{}

	relayed := newTestWorker(outbox, publisher).pass(context.Background())

	if relayed != 0 {
		t.Fatalf("relayed %d entries from empty buckets", relayed)
	}
	if len(outbox.fetchLog) != 0 {
		t.Fatalf("fetched from a bucket with no stream: %v", outbox.fetchLog)
	}
}

func TestRelayVisitsEveryBucket(t *testing.T) {
	outbox := newFakeOutbox()
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		outbox.fresh[bucket] = []domain.OutboxRecord{abandonedRecord(bucket, "1-0")}
	}
	publisher := &fakePublisher{}

	relayed := newTestWorker(outbox, publisher).pass(context.Background())

	if relayed != domain.BucketCount {
		t.Fatalf("relayed %d entries, want one per bucket (%d)", relayed, domain.BucketCount)
	}
}

// streamOutbox models the parts of a Redis Stream consumer group that decide
// ordering: entries are delivered once through Read and then sit in the pending
// list, where Claim only returns them after they have been idle for minIdle.
// The simpler fakeOutbox above hands pending entries back unconditionally, so
// it cannot express that gap.
type streamOutbox struct {
	now     time.Time
	entries []*streamEntry
}

type streamEntry struct {
	record      domain.OutboxRecord
	delivered   bool
	deliveredAt time.Time
	acked       bool
}

func (s *streamOutbox) add(record domain.OutboxRecord) {
	s.entries = append(s.entries, &streamEntry{record: record})
}

func (s *streamOutbox) StreamExists(context.Context, int) (bool, error) {
	return len(s.entries) > 0, nil
}

func (s *streamOutbox) EnsureGroup(context.Context, int) error { return nil }

func (s *streamOutbox) Claim(_ context.Context, _, count int, minIdle time.Duration) ([]domain.OutboxRecord, error) {
	var records []domain.OutboxRecord
	for _, entry := range s.entries {
		if len(records) == count {
			break
		}
		if entry.delivered && !entry.acked && s.now.Sub(entry.deliveredAt) >= minIdle {
			entry.deliveredAt = s.now
			records = append(records, entry.record)
		}
	}
	return records, nil
}

func (s *streamOutbox) Read(_ context.Context, _, count int) ([]domain.OutboxRecord, error) {
	var records []domain.OutboxRecord
	for _, entry := range s.entries {
		if len(records) == count {
			break
		}
		if !entry.delivered {
			entry.delivered = true
			entry.deliveredAt = s.now
			records = append(records, entry.record)
		}
	}
	return records, nil
}

func (s *streamOutbox) Ack(_ context.Context, _ int, streamID string) error {
	for _, entry := range s.entries {
		if entry.record.StreamID == streamID {
			entry.acked = true
		}
	}
	return nil
}

func (s *streamOutbox) TrimWatermark(context.Context, int) (string, bool, error) {
	return "", false, nil
}
func (s *streamOutbox) Trim(context.Context, int, string) (int64, error) { return 0, nil }
func (s *streamOutbox) OutboxKey(bucket int) string {
	return "cart:v2:" + domain.BucketTag(bucket) + ":outbox"
}

// switchPublisher fails while down is set and records the stream ids it
// accepted, in order.
type switchPublisher struct {
	down     bool
	accepted []string
}

func (p *switchPublisher) Publish(_ context.Context, event domain.CloudEvent, _ map[string]string) error {
	if p.down {
		return errors.New("kafka is unreachable")
	}
	p.accepted = append(p.accepted, event.ID[strings.LastIndex(event.ID, ":")+1:])
	return nil
}

func (p *switchPublisher) PublishDeadLetter(context.Context, string, []byte, map[string]string) error {
	return nil
}
func (p *switchPublisher) Ready(context.Context) error { return nil }
func (p *switchPublisher) Close() error                { return nil }

// An entry that failed to publish stays pending, and Claim will not hand it
// back until it has been idle for ClaimMinIdle. Read, meanwhile, keeps
// returning newer entries. Once Kafka recovers inside that window, a newer
// entry must still not be published ahead of the one that failed.
func TestRelayDoesNotLetNewEntriesOvertakeAFailedOne(t *testing.T) {
	const bucket = 5
	start := time.Unix(1780000000, 0)
	outbox := &streamOutbox{now: start}
	outbox.add(abandonedRecord(bucket, "1-0"))
	outbox.add(abandonedRecord(bucket, "2-0"))
	publisher := &switchPublisher{down: true}

	worker := New(outbox, publisher, Config{
		MaxPublishAttempts: 1,
		PublishBackoff:     time.Millisecond,
		OperationTimeout:   time.Second,
		ClaimMinIdle:       30 * time.Second,
		DataSchema:         "/contracts/events/cart/v1/schemas/cart-abandoned.schema.json",
	}, observability.NewMetrics(), zap.NewNop())

	// Kafka is down: 1-0 is read, fails and stays pending.
	worker.pass(context.Background())

	// Kafka recovers well inside ClaimMinIdle, and a newer entry arrives.
	publisher.down = false
	outbox.now = start.Add(time.Second)
	outbox.add(abandonedRecord(bucket, "3-0"))
	worker.pass(context.Background())

	// Later passes, once the pending entries have aged past ClaimMinIdle.
	outbox.now = start.Add(time.Minute)
	worker.pass(context.Background())

	want := []string{"1-0", "2-0", "3-0"}
	if len(publisher.accepted) != len(want) {
		t.Fatalf("published %v, want %v", publisher.accepted, want)
	}
	for i := range want {
		if publisher.accepted[i] != want[i] {
			t.Fatalf("published %v, want %v: a newer entry overtook one that failed earlier", publisher.accepted, want)
		}
	}
}

func (s *streamOutbox) PendingCount(context.Context, int) (int64, error) {
	var pending int64
	for _, entry := range s.entries {
		if entry.delivered && !entry.acked {
			pending++
		}
	}
	return pending, nil
}
