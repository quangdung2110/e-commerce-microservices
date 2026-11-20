package cartredis

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"cart-service/internal/domain"
)

const testBucket = 17

type outboxHarness struct {
	t      *testing.T
	server *miniredis.Miniredis
	client redis.UniversalClient
	outbox *Outbox
	keys   Keys
}

func newOutboxHarness(t *testing.T, consumer string) *outboxHarness {
	t.Helper()

	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	t.Cleanup(func() { _ = client.Close() })

	return &outboxHarness{
		t:      t,
		server: server,
		client: client,
		outbox: NewOutbox(client, consumer),
	}
}

// append writes an entry in the same shape mark_abandoned.lua produces.
func (h *outboxHarness) append(streamID string) string {
	h.t.Helper()

	id, err := h.client.XAdd(context.Background(), &redis.XAddArgs{
		Stream: h.keys.Outbox(testBucket),
		ID:     streamID,
		Values: map[string]any{
			"event_type":     domain.EventTypeCartAbandoned,
			"payload":        `{"cart_id":"c1:TOKEN","user_id":"user-1","abandonment_cycle":1,"occurred_at_ms":"1780000000000"}`,
			"occurred_at_ms": "1780000000000",
		},
	}).Result()
	if err != nil {
		h.t.Fatalf("XADD: %v", err)
	}
	return id
}

func TestStreamExistsIsFalseUntilTheFirstEntry(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	exists, err := h.outbox.StreamExists(ctx, testBucket)
	if err != nil {
		t.Fatalf("StreamExists: %v", err)
	}
	if exists {
		t.Fatal("an outbox stream existed before anything was written to it")
	}

	h.append("1780000000000-0")
	exists, err = h.outbox.StreamExists(ctx, testBucket)
	if err != nil {
		t.Fatalf("StreamExists: %v", err)
	}
	if !exists {
		t.Fatal("the stream is missing after an XADD")
	}
}

func TestEnsureGroupIsIdempotent(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()
	h.append("1780000000000-0")

	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("first EnsureGroup: %v", err)
	}
	// A second caller losing the race must not be an error.
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("second EnsureGroup: %v", err)
	}
}

// A group created after entries already exist has to start at 0, or the
// entries written before the relay ever ran would never be delivered.
func TestEnsureGroupSeesEntriesWrittenBeforeIt(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	h.append("1780000000000-0")
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}

	records, err := h.outbox.Read(ctx, testBucket, 10)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if len(records) != 1 {
		t.Fatalf("read %d records, want the one written before the group existed", len(records))
	}
}

func TestReadDecodesTheRecordWrittenByTheScript(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	streamID := h.append("1780000000000-0")
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}

	records, err := h.outbox.Read(ctx, testBucket, 10)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if len(records) != 1 {
		t.Fatalf("read %d records, want 1", len(records))
	}

	record := records[0]
	if record.StreamID != streamID {
		t.Fatalf("stream id = %q, want %q", record.StreamID, streamID)
	}
	if record.Bucket != testBucket {
		t.Fatalf("bucket = %d, want %d", record.Bucket, testBucket)
	}
	if record.EventType != domain.EventTypeCartAbandoned {
		t.Fatalf("event type = %q", record.EventType)
	}
	if record.Payload.CartID != "c1:TOKEN" || record.Payload.AbandonmentCycle != 1 {
		t.Fatalf("payload = %+v", record.Payload)
	}
	if record.Payload.OccurredAt != domain.Millis(1780000000000) {
		t.Fatalf("occurred_at = %d", record.Payload.OccurredAt)
	}
}

// This is the crash-before-XACK case: the entry was delivered to a relay that
// died, so it stays pending and a successor must be able to take it over.
func TestClaimRecoversAnUnackedEntryFromADeadRelay(t *testing.T) {
	dead := newOutboxHarness(t, "relay-dead")
	ctx := context.Background()

	dead.append("1780000000000-0")
	if err := dead.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}
	if _, err := dead.outbox.Read(ctx, testBucket, 10); err != nil {
		t.Fatalf("Read: %v", err)
	}

	// A fresh relay process against the same Redis. It reads nothing new.
	successor := NewOutbox(dead.client, "relay-successor")
	fresh, err := successor.Read(ctx, testBucket, 10)
	if err != nil {
		t.Fatalf("successor Read: %v", err)
	}
	if len(fresh) != 0 {
		t.Fatalf("the successor read %d new entries, want 0", len(fresh))
	}

	claimed, err := successor.Claim(ctx, testBucket, 10, 0)
	if err != nil {
		t.Fatalf("Claim: %v", err)
	}
	if len(claimed) != 1 {
		t.Fatalf("claimed %d entries, want the one the dead relay never acked", len(claimed))
	}
	if claimed[0].Payload.CartID != "c1:TOKEN" {
		t.Fatalf("claimed payload = %+v", claimed[0].Payload)
	}
}

// An acked entry must never come back, or every restart would republish the
// whole stream.
func TestAckRemovesAnEntryFromThePendingList(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	streamID := h.append("1780000000000-0")
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}
	if _, err := h.outbox.Read(ctx, testBucket, 10); err != nil {
		t.Fatalf("Read: %v", err)
	}
	if err := h.outbox.Ack(ctx, testBucket, streamID); err != nil {
		t.Fatalf("Ack: %v", err)
	}

	claimed, err := h.outbox.Claim(ctx, testBucket, 10, 0)
	if err != nil {
		t.Fatalf("Claim: %v", err)
	}
	if len(claimed) != 0 {
		t.Fatalf("claimed %d acked entries", len(claimed))
	}
}

// Reading a bucket whose group does not exist yet is normal during a lazy
// bootstrap, not an error.
func TestReadAndClaimToleratesAMissingGroup(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()
	h.append("1780000000000-0")

	records, err := h.outbox.Read(ctx, testBucket, 10)
	if err != nil {
		t.Fatalf("Read without a group: %v", err)
	}
	if len(records) != 0 {
		t.Fatalf("read %d records without a group", len(records))
	}

	claimed, err := h.outbox.Claim(ctx, testBucket, 10, 0)
	if err != nil {
		t.Fatalf("Claim without a group: %v", err)
	}
	if len(claimed) != 0 {
		t.Fatalf("claimed %d records without a group", len(claimed))
	}
}

// ── trimming ─────────────────────────────────────────────────────────────

// Without a consumer group there is no delivery watermark, so nothing can be
// declared safe to remove.
func TestTrimWatermarkIsUnsafeWithoutAGroup(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	h.append("1780000000000-0")

	_, safe, err := h.outbox.TrimWatermark(context.Background(), testBucket)
	if err != nil {
		t.Fatalf("TrimWatermark: %v", err)
	}
	if safe {
		t.Fatal("trimming was declared safe with no consumer group")
	}
}

// XACK frees the pending list, not the memory. Once everything is acked, the
// last delivered id is the safe watermark.
func TestTrimRemovesAckedEntriesOnly(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	h.append("1780000000000-0")
	h.append("1780000000001-0")
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}

	records, err := h.outbox.Read(ctx, testBucket, 10)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	for _, record := range records {
		if err := h.outbox.Ack(ctx, testBucket, record.StreamID); err != nil {
			t.Fatalf("Ack: %v", err)
		}
	}

	minID, safe, err := h.outbox.TrimWatermark(ctx, testBucket)
	if err != nil {
		t.Fatalf("TrimWatermark: %v", err)
	}
	if !safe {
		t.Fatal("trimming is not safe even though everything is acked")
	}
	if _, err := h.outbox.Trim(ctx, testBucket, minID); err != nil {
		t.Fatalf("Trim: %v", err)
	}

	remaining := h.client.XLen(ctx, h.keys.Outbox(testBucket)).Val()
	// MINID keeps the entry at the watermark itself, so one entry survives.
	if remaining != 1 {
		t.Fatalf("stream holds %d entries after a full trim, want 1", remaining)
	}
}

// The trimmer must never remove an entry the relay has not acked, or a Kafka
// outage would turn into permanent event loss.
func TestTrimKeepsTheOldestPendingEntry(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	oldest := h.append("1780000000000-0")
	h.append("1780000000001-0")
	h.append("1780000000002-0")
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}
	if _, err := h.outbox.Read(ctx, testBucket, 10); err != nil {
		t.Fatalf("Read: %v", err)
	}
	// Everything is delivered but nothing is acked: Kafka is down.

	minID, safe, err := h.outbox.TrimWatermark(ctx, testBucket)
	if err != nil {
		t.Fatalf("TrimWatermark: %v", err)
	}
	if !safe {
		t.Fatal("a stream with pending entries yielded no watermark")
	}
	if minID != oldest {
		t.Fatalf("watermark = %q, want the oldest pending entry %q", minID, oldest)
	}

	if _, err := h.outbox.Trim(ctx, testBucket, minID); err != nil {
		t.Fatalf("Trim: %v", err)
	}
	if remaining := h.client.XLen(ctx, h.keys.Outbox(testBucket)).Val(); remaining != 3 {
		t.Fatalf("stream holds %d entries, want all 3 pending entries kept", remaining)
	}
}

func TestOutboxKeyMatchesTheStreamItReads(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")

	if got := h.outbox.OutboxKey(testBucket); got != "cart:v2:{b17}:outbox" {
		t.Fatalf("OutboxKey = %q", got)
	}
}

func TestClaimHonoursMinIdle(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	h.append("1780000000000-0")
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}
	if _, err := h.outbox.Read(ctx, testBucket, 10); err != nil {
		t.Fatalf("Read: %v", err)
	}

	// An entry a healthy relay is still working on must not be stolen.
	claimed, err := h.outbox.Claim(ctx, testBucket, 10, time.Hour)
	if err != nil {
		t.Fatalf("Claim: %v", err)
	}
	if len(claimed) != 0 {
		t.Fatalf("claimed %d entries that were not idle long enough", len(claimed))
	}
}

// The relay reads new entries only while nothing is pending, so the count has
// to follow delivery and ack exactly, and a stream without a group has none.
func TestPendingCountFollowsDeliveryAndAck(t *testing.T) {
	h := newOutboxHarness(t, "relay-1")
	ctx := context.Background()

	count, err := h.outbox.PendingCount(ctx, testBucket)
	if err != nil || count != 0 {
		t.Fatalf("PendingCount before any stream = %d, %v; want 0, nil", count, err)
	}

	first := h.append("1780000000000-0")
	h.append("1780000000001-0")
	if err := h.outbox.EnsureGroup(ctx, testBucket); err != nil {
		t.Fatalf("EnsureGroup: %v", err)
	}
	if count, _ := h.outbox.PendingCount(ctx, testBucket); count != 0 {
		t.Fatalf("PendingCount before Read = %d; undelivered entries are not pending", count)
	}

	if _, err := h.outbox.Read(ctx, testBucket, 10); err != nil {
		t.Fatalf("Read: %v", err)
	}
	if count, _ := h.outbox.PendingCount(ctx, testBucket); count != 2 {
		t.Fatalf("PendingCount after Read = %d, want 2", count)
	}

	if err := h.outbox.Ack(ctx, testBucket, first); err != nil {
		t.Fatalf("Ack: %v", err)
	}
	if count, _ := h.outbox.PendingCount(ctx, testBucket); count != 1 {
		t.Fatalf("PendingCount after one Ack = %d, want 1", count)
	}
}
