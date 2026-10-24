package cartredis

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
)

const (
	testIdempotencyKey = "idempotency-key-000001"
	testUserID         = "0198e098-94f4-7f8f-9e16-ae42dfbd93c1"
)

type harness struct {
	t      *testing.T
	server *miniredis.Miniredis
	client redis.UniversalClient
	store  *Store
	keys   Keys
	now    time.Time
}

// testRedisAddrEnv points the suite at a real Redis instead of miniredis.
//
// miniredis runs Lua on gopher-lua, not on the Lua and cjson that ship inside
// Redis. The scripts are the whole correctness story here, so the same suite
// has to be runnable against the real thing:
//
//	CART_TEST_REDIS_ADDR=localhost:6379 go test ./internal/infrastructure/cartredis/...
const testRedisAddrEnv = "CART_TEST_REDIS_ADDR"

func newHarness(t *testing.T) *harness {
	t.Helper()

	var server *miniredis.Miniredis
	addr := os.Getenv(testRedisAddrEnv)
	if addr == "" {
		server = miniredis.RunT(t)
		addr = server.Addr()
	}

	client := redis.NewClient(&redis.Options{Addr: addr})
	t.Cleanup(func() { _ = client.Close() })

	if server == nil {
		// Each test needs a clean keyspace, and a shared real Redis does not
		// get one for free.
		if err := client.FlushDB(context.Background()).Err(); err != nil {
			t.Fatalf("flush the test database: %v", err)
		}
	}

	h := &harness{
		t:      t,
		server: server,
		client: client,
		keys:   Keys{},
		now:    time.UnixMilli(1780000000000),
	}
	h.store = NewStore(client, Options{
		AbandonedAfter: 30 * 24 * time.Hour,
		ActiveTTL:      37 * 24 * time.Hour,
		AbandonedTTL:   7 * 24 * time.Hour,
		IdempotencyTTL: 24 * time.Hour,
	})
	h.store.now = func() time.Time { return h.now }
	return h
}

// advance moves the store's clock forward. The scripts take now_ms as an
// argument, so moving the clock is enough to exercise every expiry rule
// without waiting. Against miniredis the fake TTL clock moves too; against a
// real Redis the multi-day TTLs simply stay unexpired, which does not affect
// any assertion here.
func (h *harness) advance(d time.Duration) {
	h.now = h.now.Add(d)
	if h.server != nil {
		h.server.FastForward(d)
	}
}

func (h *harness) identity() domain.Identity {
	identity, err := domain.NewIdentity(testUserID)
	if err != nil {
		h.t.Fatalf("NewIdentity: %v", err)
	}
	return identity
}

func (h *harness) snapshot(_ ...int64) *domain.StoredItem {
	item := domain.ProductSnapshot{
		SkuID:             "SKU-TSHIRT-RED-M",
		ProductID:         "PRODUCT-TSHIRT",
		ProductName:       "Ao thun do",
		VariantAttributes: map[string]string{"color": "red", "size": "M"},
		VariantName:       "Màu sắc: Đỏ · Kích thước: M",
		ImageKey:          "products/tshirt/red.png",
		Price:             domain.Money{AmountMinor: 125000, Currency: "VND"},
		IsAvailable:       true,
	}.ToStoredItem(
		domain.MillisOf(h.now),
		domain.MillisOf(h.now.Add(45*time.Second)),
	)
	return &item
}

func (h *harness) upsert(skuID string, delta int, snapshot *domain.StoredItem, key string) (port.UpsertResult, error) {
	return h.store.Upsert(context.Background(), port.UpsertCommand{
		Identity:       h.identity(),
		SkuID:          skuID,
		Delta:          delta,
		Snapshot:       snapshot,
		Fingerprint:    "fingerprint-" + skuID,
		IdempotencyKey: key,
	})
}

func (h *harness) storedItem(skuID string) domain.StoredItem {
	h.t.Helper()

	raw, err := h.client.HGet(context.Background(), h.keys.Items(h.identity()), skuID).Result()
	if err != nil {
		h.t.Fatalf("HGET %s: %v", skuID, err)
	}
	var item domain.StoredItem
	if err := json.Unmarshal([]byte(raw), &item); err != nil {
		h.t.Fatalf("decode stored item: %v", err)
	}
	return item
}

func requireCode(t *testing.T, err error, want domain.Code) {
	t.Helper()

	var domainErr *domain.Error
	if !errors.As(err, &domainErr) {
		t.Fatalf("expected a domain error, got %v", err)
	}
	if domainErr.Code != want {
		t.Fatalf("error code = %s, want %s", domainErr.Code, want)
	}
}

// ── upsert_item.lua ──────────────────────────────────────────────────────

func TestUpsertRequiresASnapshotForANewSku(t *testing.T) {
	h := newHarness(t)

	_, err := h.upsert("SKU-TSHIRT-RED-M", 1, nil, testIdempotencyKey)
	requireCode(t, err, domain.CodeProductSnapshotRequired)

	// A refused write must leave no trace, including no idempotency record.
	if h.client.Exists(context.Background(), h.keys.Items(h.identity())).Val() != 0 {
		t.Fatal("a refused write created the items hash")
	}
	if h.client.Exists(context.Background(), h.keys.Idempotency(h.identity(), "adjust", testIdempotencyKey)).Val() != 0 {
		t.Fatal("a refused write stored an idempotency record")
	}
}

func TestUpsertCreatesAndThenIncrements(t *testing.T) {
	h := newHarness(t)

	created, err := h.upsert("SKU-TSHIRT-RED-M", 2, h.snapshot(), testIdempotencyKey)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if !created.Created || created.Quantity != 2 {
		t.Fatalf("create = %+v, want created=true quantity=2", created)
	}

	// A second click reuses the stored snapshot: no new Product call, and the
	// script applies the delta from Redis state.
	updated, err := h.upsert("SKU-TSHIRT-RED-M", 3, nil, "idempotency-key-000002")
	if err != nil {
		t.Fatalf("increment: %v", err)
	}
	if updated.Created || updated.Quantity != 5 {
		t.Fatalf("increment = %+v, want created=false quantity=5", updated)
	}
}

// Three rapid clicks must all land. This is the reason the contract carries a
// delta instead of an absolute quantity.
func TestConcurrentDeltasDoNotLoseUpdates(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	for i := 0; i < 3; i++ {
		if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, nil, "idempotency-key-00000"+string(rune('2'+i))); err != nil {
			t.Fatalf("increment %d: %v", i, err)
		}
	}

	if got := h.storedItem("SKU-TSHIRT-RED-M").Quantity; got != 4 {
		t.Fatalf("quantity = %d, want 4", got)
	}
}

func TestUpsertKeepsHistoricalFieldsImmutable(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	original := h.storedItem("SKU-TSHIRT-RED-M")

	// Time passes, the price changes, and a refreshed snapshot arrives.
	h.advance(time.Hour)
	refreshed := h.snapshot()
	refreshed.UnitPriceAtAdd = domain.Money{AmountMinor: 999000, Currency: "VND"}
	if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, refreshed, "idempotency-key-000002"); err != nil {
		t.Fatalf("refresh: %v", err)
	}

	updated := h.storedItem("SKU-TSHIRT-RED-M")
	if updated.UnitPriceAtAdd != original.UnitPriceAtAdd {
		t.Fatalf("unit_price_at_add changed: %+v -> %+v", original.UnitPriceAtAdd, updated.UnitPriceAtAdd)
	}
	if updated.AddedAt != original.AddedAt {
		t.Fatalf("added_at changed: %d -> %d", original.AddedAt, updated.AddedAt)
	}
	if updated.UpdatedAt == original.UpdatedAt {
		t.Fatal("updated_at did not move")
	}
}

// An expired validation is only a problem when the quantity goes up. Reducing
// an item never needs fresh catalog data.
func TestStaleValidationOnlyBlocksAPositiveDelta(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 5, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	h.advance(2 * time.Minute)

	_, err := h.upsert("SKU-TSHIRT-RED-M", 1, nil, "idempotency-key-000002")
	requireCode(t, err, domain.CodeProductSnapshotRequired)

	decreased, err := h.upsert("SKU-TSHIRT-RED-M", -2, nil, "idempotency-key-000003")
	if err != nil {
		t.Fatalf("decrement on a stale validation: %v", err)
	}
	if decreased.Quantity != 3 {
		t.Fatalf("quantity = %d, want 3", decreased.Quantity)
	}
}

func TestUpsertClampsAtTheQuantityCeiling(t *testing.T) {
	h := newHarness(t)

	snapshot := h.snapshot()
	if _, err := h.upsert("SKU-TSHIRT-RED-M", 100, snapshot, testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	result, err := h.upsert("SKU-TSHIRT-RED-M", 50, h.snapshot(), "idempotency-key-000002")
	if err != nil {
		t.Fatalf("increment past the ceiling: %v", err)
	}
	if result.Quantity != domain.MaxQuantityPerItem {
		t.Fatalf("quantity = %d, want %d", result.Quantity, domain.MaxQuantityPerItem)
	}
}

func TestUpsertRejectsANonPositiveResult(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 2, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	_, err := h.upsert("SKU-TSHIRT-RED-M", -2, nil, "idempotency-key-000002")
	requireCode(t, err, domain.CodeQuantityInvalid)
}

func TestUpsertAllowsQuantityWithoutDisplayStock(t *testing.T) {
	h := newHarness(t)

	result, err := h.upsert("SKU-TSHIRT-RED-M", 5, h.snapshot(), testIdempotencyKey)
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if result.Quantity != 5 {
		t.Fatalf("quantity = %d, want 5", result.Quantity)
	}

	raw, err := h.client.HGet(context.Background(), h.keys.Items(h.identity()), "SKU-TSHIRT-RED-M").Result()
	if err != nil {
		t.Fatalf("read stored item: %v", err)
	}
	var legacy map[string]any
	if err := json.Unmarshal([]byte(raw), &legacy); err != nil {
		t.Fatalf("decode stored item: %v", err)
	}
	legacy["available_quantity_snapshot"] = 0
	legacyRaw, err := json.Marshal(legacy)
	if err != nil {
		t.Fatalf("encode legacy item: %v", err)
	}
	if err := h.client.HSet(context.Background(), h.keys.Items(h.identity()), "SKU-TSHIRT-RED-M", legacyRaw).Err(); err != nil {
		t.Fatalf("store legacy item: %v", err)
	}

	result, err = h.upsert("SKU-TSHIRT-RED-M", 1, nil, "idempotency-key-000002")
	if err != nil {
		t.Fatalf("update legacy item: %v", err)
	}
	if result.Quantity != 6 {
		t.Fatalf("quantity after update = %d, want 6", result.Quantity)
	}
	raw, err = h.client.HGet(context.Background(), h.keys.Items(h.identity()), "SKU-TSHIRT-RED-M").Result()
	if err != nil {
		t.Fatalf("read rewritten item: %v", err)
	}
	if strings.Contains(raw, "available_quantity_snapshot") {
		t.Fatalf("stored item still contains legacy stock data: %s", raw)
	}
}

func TestUpsertRejectsAnUnavailableSku(t *testing.T) {
	h := newHarness(t)

	snapshot := h.snapshot()
	snapshot.IsAvailable = false
	_, err := h.upsert("SKU-TSHIRT-RED-M", 1, snapshot, testIdempotencyKey)
	requireCode(t, err, domain.CodeProductNotAvailable)
}

func TestUpsertRejectsANegativeDeltaOnAMissingSku(t *testing.T) {
	h := newHarness(t)

	_, err := h.upsert("SKU-NOT-IN-CART", -1, nil, testIdempotencyKey)
	requireCode(t, err, domain.CodeItemNotFound)
}

func TestUpsertEnforcesTheCartSkuCap(t *testing.T) {
	h := newHarness(t)

	for i := 0; i < domain.MaxSKUsPerCart; i++ {
		sku := "SKU-" + itoa(i)
		if _, err := h.upsert(sku, 1, h.snapshot(), "idempotency-key-"+itoa(i)); err != nil {
			t.Fatalf("create %s: %v", sku, err)
		}
	}

	_, err := h.upsert("SKU-ONE-TOO-MANY", 1, h.snapshot(), "idempotency-key-overflow")
	requireCode(t, err, domain.CodeCartFull)
}

// ── idempotency ──────────────────────────────────────────────────────────

// A duplicate key must replay the stored outcome, never apply the delta twice.
func TestDuplicateIdempotencyKeyReplaysWithoutReapplying(t *testing.T) {
	h := newHarness(t)

	first, err := h.upsert("SKU-TSHIRT-RED-M", 3, h.snapshot(), testIdempotencyKey)
	if err != nil {
		t.Fatalf("first call: %v", err)
	}

	second, err := h.upsert("SKU-TSHIRT-RED-M", 3, h.snapshot(), testIdempotencyKey)
	if err != nil {
		t.Fatalf("replay: %v", err)
	}

	if second != first {
		t.Fatalf("replay returned %+v, want %+v", second, first)
	}
	if got := h.storedItem("SKU-TSHIRT-RED-M").Quantity; got != 3 {
		t.Fatalf("quantity = %d, want 3: the delta was applied twice", got)
	}
}

func TestSameIdempotencyKeyWithADifferentPayloadConflicts(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("first call: %v", err)
	}

	_, err := h.store.Upsert(context.Background(), port.UpsertCommand{
		Identity:       h.identity(),
		SkuID:          "SKU-TSHIRT-RED-M",
		Delta:          9,
		Snapshot:       h.snapshot(),
		Fingerprint:    "a-completely-different-fingerprint",
		IdempotencyKey: testIdempotencyKey,
	})
	requireCode(t, err, domain.CodeIdempotencyConflict)
}

func TestUnavailableItemRefreshesAfterValidationExpires(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	item := h.storedItem("SKU-TSHIRT-RED-M")
	item.IsAvailable = false
	item.CatalogValidationExpiresAt = domain.MillisOf(h.now.Add(-time.Millisecond))
	raw, err := json.Marshal(item)
	if err != nil {
		t.Fatalf("encode unavailable item: %v", err)
	}
	if err := h.client.HSet(ctx, h.keys.Items(h.identity()), "SKU-TSHIRT-RED-M", raw).Err(); err != nil {
		t.Fatalf("store unavailable item: %v", err)
	}

	_, err = h.upsert("SKU-TSHIRT-RED-M", 1, nil, "idempotency-key-refresh-01")
	requireCode(t, err, domain.CodeProductSnapshotRequired)

	result, err := h.upsert("SKU-TSHIRT-RED-M", 1, h.snapshot(), "idempotency-key-refresh-01")
	if err != nil {
		t.Fatalf("refresh unavailable item: %v", err)
	}
	if result.Quantity != 2 || !h.storedItem("SKU-TSHIRT-RED-M").IsAvailable {
		t.Fatalf("refreshed item = %+v, want quantity 2 and available", h.storedItem("SKU-TSHIRT-RED-M"))
	}
}

func TestSameIdempotencyKeyCanBeUsedForDifferentOperations(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("upsert: %v", err)
	}

	result, err := h.store.Remove(context.Background(), port.RemoveCommand{
		Identity:       h.identity(),
		SkuIDs:         []string{"SKU-TSHIRT-RED-M"},
		TouchActivity:  true,
		Fingerprint:    "delete-fingerprint",
		IdempotencyKey: testIdempotencyKey,
	})
	if err != nil {
		t.Fatalf("delete with the same raw key: %v", err)
	}
	if result.DeletedCount != 1 {
		t.Fatalf("deleted_count = %d, want 1", result.DeletedCount)
	}
}

// ── remove_items.lua ─────────────────────────────────────────────────────

func TestRemoveIsIdempotentForAbsentSkus(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}

	result, err := h.store.Remove(context.Background(), port.RemoveCommand{
		Identity:       h.identity(),
		SkuIDs:         []string{"SKU-A", "SKU-NEVER-ADDED"},
		TouchActivity:  true,
		Fingerprint:    "fingerprint-delete",
		IdempotencyKey: "idempotency-key-d00001",
	})
	if err != nil {
		t.Fatalf("remove: %v", err)
	}
	if result.DeletedCount != 1 {
		t.Fatalf("deleted_count = %d, want 1", result.DeletedCount)
	}
}

// Emptying a cart must clear the state and the scheduler entry together, or
// the marker would keep scanning a member whose cart no longer exists.
func TestRemovingTheLastItemClearsTheCartAndTheSchedule(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	if h.client.ZCard(context.Background(), h.keys.Index(identity.Bucket)).Val() != 1 {
		t.Fatal("the write did not schedule an abandonment deadline")
	}

	if _, err := h.store.Remove(context.Background(), port.RemoveCommand{
		Identity:       identity,
		SkuIDs:         []string{"SKU-A"},
		TouchActivity:  true,
		Fingerprint:    "fingerprint-delete",
		IdempotencyKey: "idempotency-key-d00001",
	}); err != nil {
		t.Fatalf("remove: %v", err)
	}

	ctx := context.Background()
	if h.client.Exists(ctx, h.keys.Items(identity)).Val() != 0 {
		t.Fatal("the items hash survived an emptied cart")
	}
	if h.client.Exists(ctx, h.keys.Meta(identity)).Val() != 0 {
		t.Fatal("the meta hash survived an emptied cart")
	}
	if h.client.ZCard(ctx, h.keys.Index(identity.Bucket)).Val() != 0 {
		t.Fatal("the abandonment schedule survived an emptied cart")
	}
}

// ORDER_PAID cleanup must not look like user activity: paying for part of a
// cart is not browsing, so the abandonment deadline must not move.
func TestOrderPaidCleanupDoesNotTouchActivity(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()
	ctx := context.Background()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create A: %v", err)
	}
	if _, err := h.upsert("SKU-B", 1, h.snapshot(20), "idempotency-key-b00001"); err != nil {
		t.Fatalf("create B: %v", err)
	}
	deadlineBefore := h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val()

	h.advance(time.Hour)
	if _, err := h.store.Remove(ctx, port.RemoveCommand{
		Identity:      identity,
		SkuIDs:        []string{"SKU-A"},
		TouchActivity: false,
	}); err != nil {
		t.Fatalf("order paid cleanup: %v", err)
	}

	deadlineAfter := h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val()
	if deadlineAfter != deadlineBefore {
		t.Fatalf("the abandonment deadline moved: %f -> %f", deadlineBefore, deadlineAfter)
	}
	if h.client.HExists(ctx, h.keys.Items(identity), "SKU-A").Val() {
		t.Fatal("the purchased sku is still in the cart")
	}
	if !h.client.HExists(ctx, h.keys.Items(identity), "SKU-B").Val() {
		t.Fatal("an unpurchased sku was removed")
	}
}

// A redelivered ORDER_PAID must be harmless. HDEL per SKU is what makes that
// true without an idempotency record.
func TestOrderPaidCleanupIsIdempotentOnRedelivery(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create A: %v", err)
	}
	if _, err := h.upsert("SKU-B", 1, h.snapshot(20), "idempotency-key-b00001"); err != nil {
		t.Fatalf("create B: %v", err)
	}

	cmd := port.RemoveCommand{Identity: h.identity(), SkuIDs: []string{"SKU-A"}}
	first, err := h.store.Remove(context.Background(), cmd)
	if err != nil {
		t.Fatalf("first delivery: %v", err)
	}
	second, err := h.store.Remove(context.Background(), cmd)
	if err != nil {
		t.Fatalf("redelivery: %v", err)
	}

	if first.DeletedCount != 1 || second.DeletedCount != 0 {
		t.Fatalf("deleted counts = %d then %d, want 1 then 0", first.DeletedCount, second.DeletedCount)
	}
}

// ── mark_abandoned.lua ───────────────────────────────────────────────────

func TestMarkAbandonedTransitionsAndEmitsAtomically(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()
	ctx := context.Background()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	deadline := int64(h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val())

	h.advance(31 * 24 * time.Hour)
	result, err := h.store.MarkAbandoned(ctx, port.MarkAbandonedCommand{
		Identity:         identity,
		ExpectedDeadline: deadline,
	})
	if err != nil {
		t.Fatalf("mark abandoned: %v", err)
	}
	if !result.Marked {
		t.Fatalf("cart was not marked: %+v", result)
	}
	if result.AbandonmentCycle != 1 {
		t.Fatalf("abandonment_cycle = %d, want 1", result.AbandonmentCycle)
	}

	if status := h.client.HGet(ctx, h.keys.Meta(identity), "status").Val(); status != string(domain.StatusAbandoned) {
		t.Fatalf("status = %q, want ABANDONED", status)
	}
	if h.client.ZCard(ctx, h.keys.Index(identity.Bucket)).Val() != 0 {
		t.Fatal("the deadline stayed in the index after the transition")
	}

	// The state change and the outbox entry share one Lua invocation, so the
	// stream must hold exactly one entry for this cycle.
	entries := h.client.XRange(ctx, h.keys.Outbox(identity.Bucket), "-", "+").Val()
	if len(entries) != 1 {
		t.Fatalf("outbox holds %d entries, want 1", len(entries))
	}
	if entries[0].ID != result.StreamID {
		t.Fatalf("stream id = %q, want %q", entries[0].ID, result.StreamID)
	}
	if entries[0].Values["event_type"] != domain.EventTypeCartAbandoned {
		t.Fatalf("event_type = %v", entries[0].Values["event_type"])
	}

	payload, err := domain.DecodeAbandonedPayload(entries[0].Values["payload"].(string))
	if err != nil {
		t.Fatalf("decode payload: %v", err)
	}
	if payload.CartID != identity.CartID || payload.UserID != testUserID {
		t.Fatalf("payload identity = %+v", payload)
	}
	if payload.AbandonmentCycle != 1 {
		t.Fatalf("payload cycle = %d, want 1", payload.AbandonmentCycle)
	}
	// The event carries internal identifiers only.
	if payload.OccurredAt == 0 {
		t.Fatal("payload has no occurred_at")
	}
}

// A user write that lands just before the marker reads the deadline must win.
// The stale score is how the script detects that without a lock.
func TestMarkAbandonedRefusesAStaleDeadline(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()
	ctx := context.Background()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	staleDeadline := int64(h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val())

	// The user comes back and writes, which pushes the deadline out.
	h.advance(31 * 24 * time.Hour)
	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00002"); err != nil {
		t.Fatalf("second write: %v", err)
	}

	result, err := h.store.MarkAbandoned(ctx, port.MarkAbandonedCommand{
		Identity:         identity,
		ExpectedDeadline: staleDeadline,
	})
	if err != nil {
		t.Fatalf("mark abandoned: %v", err)
	}
	if result.Marked {
		t.Fatal("the marker won a race it should have lost")
	}
	if result.Reason != "STALE_DEADLINE" {
		t.Fatalf("reason = %q, want STALE_DEADLINE", result.Reason)
	}
	if h.client.Exists(ctx, h.keys.Outbox(identity.Bucket)).Val() != 0 {
		t.Fatal("a refused transition emitted an event")
	}
}

// A cart whose keys expired before the marker ran leaves a stale index member.
// Cleaning it up must not emit an event.
func TestMarkAbandonedCleansUpAnExpiredCartWithoutEmitting(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()
	ctx := context.Background()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	deadline := int64(h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val())

	h.client.Del(ctx, h.keys.Items(identity), h.keys.Meta(identity))
	h.advance(31 * 24 * time.Hour)

	result, err := h.store.MarkAbandoned(ctx, port.MarkAbandonedCommand{
		Identity:         identity,
		ExpectedDeadline: deadline,
	})
	if err != nil {
		t.Fatalf("mark abandoned: %v", err)
	}
	if result.Marked {
		t.Fatal("an expired cart was marked abandoned")
	}
	if result.Reason != "CART_MISSING" {
		t.Fatalf("reason = %q, want CART_MISSING", result.Reason)
	}
	if h.client.ZCard(ctx, h.keys.Index(identity.Bucket)).Val() != 0 {
		t.Fatal("the stale index member was not cleaned up")
	}
	if h.client.Exists(ctx, h.keys.Outbox(identity.Bucket)).Val() != 0 {
		t.Fatal("an expired cart emitted an event")
	}
}

// A user who returns after abandonment and then goes quiet again produces a
// second cycle, and the cycle number is what lets Notification tell them apart.
func TestReactivationStartsANewAbandonmentCycle(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()
	ctx := context.Background()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	deadline := int64(h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val())

	h.advance(31 * 24 * time.Hour)
	if _, err := h.store.MarkAbandoned(ctx, port.MarkAbandonedCommand{
		Identity: identity, ExpectedDeadline: deadline,
	}); err != nil {
		t.Fatalf("first transition: %v", err)
	}

	// The user comes back inside the retention window.
	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00002"); err != nil {
		t.Fatalf("reactivating write: %v", err)
	}
	if status := h.client.HGet(ctx, h.keys.Meta(identity), "status").Val(); status != string(domain.StatusActive) {
		t.Fatalf("status = %q, want ACTIVE after a write", status)
	}

	secondDeadline := int64(h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val())
	h.advance(31 * 24 * time.Hour)
	second, err := h.store.MarkAbandoned(ctx, port.MarkAbandonedCommand{
		Identity: identity, ExpectedDeadline: secondDeadline,
	})
	if err != nil {
		t.Fatalf("second transition: %v", err)
	}
	if second.AbandonmentCycle != 2 {
		t.Fatalf("abandonment_cycle = %d, want 2", second.AbandonmentCycle)
	}

	entries := h.client.XRange(ctx, h.keys.Outbox(identity.Bucket), "-", "+").Val()
	if len(entries) != 2 {
		t.Fatalf("outbox holds %d entries, want 2", len(entries))
	}
}

// ── reads ────────────────────────────────────────────────────────────────

// A Redis miss is an empty cart. There is no cold store to fall back to.
func TestLoadItemsReturnsEmptyForAMissingCart(t *testing.T) {
	h := newHarness(t)

	items, err := h.store.LoadItems(context.Background(), h.identity())
	if err != nil {
		t.Fatalf("LoadItems: %v", err)
	}
	if len(items) != 0 {
		t.Fatalf("got %d items for a cart that never existed", len(items))
	}
}

func TestLoadItemsSurvivesTheLuaJSONRoundTrip(t *testing.T) {
	h := newHarness(t)

	if _, err := h.upsert("SKU-TSHIRT-RED-M", 2, h.snapshot(20), testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	// A second write makes the script decode and re-encode the item JSON.
	if _, err := h.upsert("SKU-TSHIRT-RED-M", 1, nil, "idempotency-key-000002"); err != nil {
		t.Fatalf("increment: %v", err)
	}

	items, err := h.store.LoadItems(context.Background(), h.identity())
	if err != nil {
		t.Fatalf("LoadItems: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("got %d items, want 1", len(items))
	}

	item := items[0]
	if item.Quantity != 3 {
		t.Fatalf("quantity = %d, want 3", item.Quantity)
	}
	if item.UnitPriceAtAdd.AmountMinor != 125000 || item.UnitPriceAtAdd.Currency != "VND" {
		t.Fatalf("unit_price_at_add = %+v", item.UnitPriceAtAdd)
	}
	// Millisecond timestamps are stored as strings precisely so that cjson's
	// %.14g number formatting cannot round them.
	if item.AddedAt != domain.MillisOf(h.now) {
		t.Fatalf("added_at = %d, want %d", item.AddedAt, domain.MillisOf(h.now))
	}
	if item.VariantAttributes["color"] != "red" || item.VariantAttributes["size"] != "M" {
		t.Fatalf("variant_attributes = %+v", item.VariantAttributes)
	}
	if item.VariantName != "Màu sắc: Đỏ · Kích thước: M" {
		t.Fatalf("variant_name lost across Lua round trip: %q", item.VariantName)
	}
}

// An item with no variant attributes is the case where cjson would otherwise
// hand back an empty array instead of an object.
func TestLoadItemsHandlesAnItemWithoutVariantAttributes(t *testing.T) {
	h := newHarness(t)

	snapshot := h.snapshot(20)
	snapshot.VariantAttributes = nil
	if _, err := h.upsert("SKU-PLAIN", 1, snapshot, testIdempotencyKey); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := h.upsert("SKU-PLAIN", 1, nil, "idempotency-key-000002"); err != nil {
		t.Fatalf("increment: %v", err)
	}

	items, err := h.store.LoadItems(context.Background(), h.identity())
	if err != nil {
		t.Fatalf("LoadItems: %v", err)
	}
	if len(items) != 1 || items[0].Quantity != 2 {
		t.Fatalf("items = %+v", items)
	}
}

// ── purge_cart.lua (USER_DELETED) ────────────────────────────────────────

func TestPurgeCartRemovesTheWholeCartAndItsSchedule(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()
	ctx := context.Background()

	if _, err := h.upsert("SKU-A", 2, h.snapshot(), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := h.upsert("SKU-B", 1, h.snapshot(), "idempotency-key-b00001"); err != nil {
		t.Fatalf("create: %v", err)
	}

	result, err := h.store.PurgeCart(ctx, identity)
	if err != nil {
		t.Fatalf("PurgeCart: %v", err)
	}
	if !result.Purged {
		t.Fatal("a purge that deleted a cart reported nothing purged")
	}

	if h.client.Exists(ctx, h.keys.Items(identity)).Val() != 0 {
		t.Fatal("the items hash survived the purge")
	}
	if h.client.Exists(ctx, h.keys.Meta(identity)).Val() != 0 {
		t.Fatal("the meta hash survived the purge")
	}
	if h.client.ZCard(ctx, h.keys.Index(identity.Bucket)).Val() != 0 {
		t.Fatal("the abandonment schedule survived the purge")
	}
	if items, err := h.store.LoadItems(ctx, identity); err != nil || len(items) != 0 {
		t.Fatalf("a purged cart still reads %d items (err %v)", len(items), err)
	}
}

// The purge is the only script that deletes a whole cart, so it must touch
// exactly one user's keys.
func TestPurgeCartLeavesOtherCartsAlone(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	other, err := domain.NewIdentity("0198e098-94f4-7f8f-9e16-ae42dfbd93c2")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}
	if _, err := h.upsert("SKU-A", 1, h.snapshot(), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := h.store.Upsert(ctx, port.UpsertCommand{
		Identity:       other,
		SkuID:          "SKU-A",
		Delta:          3,
		Snapshot:       h.snapshot(),
		Fingerprint:    "fingerprint-other",
		IdempotencyKey: "idempotency-key-o00001",
	}); err != nil {
		t.Fatalf("create other: %v", err)
	}

	if _, err := h.store.PurgeCart(ctx, h.identity()); err != nil {
		t.Fatalf("PurgeCart: %v", err)
	}

	items, err := h.store.LoadItems(ctx, other)
	if err != nil || len(items) != 1 || items[0].Quantity != 3 {
		t.Fatalf("the other user's cart was damaged: %+v (err %v)", items, err)
	}
	if h.client.ZScore(ctx, h.keys.Index(other.Bucket), other.UserToken).Err() != nil {
		t.Fatal("the other user's abandonment schedule was removed")
	}
}

// Kafka redelivers USER_DELETED and a user may never have had a cart: both
// must succeed and report that nothing was left to purge.
func TestPurgeCartIsIdempotent(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	missing, err := h.store.PurgeCart(ctx, h.identity())
	if err != nil || missing.Purged {
		t.Fatalf("purging a user without a cart = %+v (err %v), want a quiet no-op", missing, err)
	}

	if _, err := h.upsert("SKU-A", 1, h.snapshot(), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	if first, err := h.store.PurgeCart(ctx, h.identity()); err != nil || !first.Purged {
		t.Fatalf("first purge = %+v (err %v)", first, err)
	}
	if again, err := h.store.PurgeCart(ctx, h.identity()); err != nil || again.Purged {
		t.Fatalf("redelivered purge = %+v (err %v), want a quiet no-op", again, err)
	}
}

// An ABANDONED cart is still a cart: its pending CART_ABANDONED entry is
// already durable, belongs to the relay, and must survive the purge.
func TestPurgeCartRemovesAnAbandonedCartButKeepsItsPendingOutboxEntry(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()
	ctx := context.Background()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}
	deadline := int64(h.client.ZScore(ctx, h.keys.Index(identity.Bucket), identity.UserToken).Val())
	h.advance(31 * 24 * time.Hour)
	if marked, err := h.store.MarkAbandoned(ctx, port.MarkAbandonedCommand{Identity: identity, ExpectedDeadline: deadline}); err != nil || !marked.Marked {
		t.Fatalf("mark abandoned = %+v (err %v)", marked, err)
	}

	if purged, err := h.store.PurgeCart(ctx, identity); err != nil || !purged.Purged {
		t.Fatalf("purge = %+v (err %v)", purged, err)
	}

	if h.client.Exists(ctx, h.keys.Items(identity), h.keys.Meta(identity)).Val() != 0 {
		t.Fatal("an abandoned cart survived the purge")
	}
	if got := h.client.XLen(ctx, h.keys.Outbox(identity.Bucket)).Val(); got != 1 {
		t.Fatalf("outbox holds %d entries, want the 1 pending CART_ABANDONED", got)
	}
}

// ── idempotency retention ────────────────────────────────────────────────

// CART_CART_IDEMPOTENCY_TTL is one knob for both HTTP writes; neither script
// may hard-code the retention.
func TestIdempotencyRecordsFollowTheConfiguredTTL(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()
	identity := h.identity()

	h.store = NewStore(h.client, Options{
		AbandonedAfter: 30 * 24 * time.Hour,
		ActiveTTL:      37 * 24 * time.Hour,
		AbandonedTTL:   7 * 24 * time.Hour,
		IdempotencyTTL: 2 * time.Hour,
	})
	h.store.now = func() time.Time { return h.now }

	if _, err := h.upsert("SKU-A", 1, h.snapshot(), testIdempotencyKey); err != nil {
		t.Fatalf("upsert: %v", err)
	}
	if _, err := h.store.Remove(ctx, port.RemoveCommand{
		Identity:       identity,
		SkuIDs:         []string{"SKU-NOT-IN-CART"},
		TouchActivity:  true,
		Fingerprint:    "fingerprint-delete",
		IdempotencyKey: testIdempotencyKey,
	}); err != nil {
		t.Fatalf("remove: %v", err)
	}

	for operation := range map[string]struct{}{"adjust": {}, "delete": {}} {
		ttl := h.client.TTL(ctx, h.keys.Idempotency(identity, operation, testIdempotencyKey)).Val()
		if ttl <= 0 || ttl > 2*time.Hour {
			t.Fatalf("%s record ttl = %s, want within (0, 2h]", operation, ttl)
		}
	}
}

// ── scheduler index ──────────────────────────────────────────────────────

func TestDueCartsOnlyReturnsOverdueMembers(t *testing.T) {
	h := newHarness(t)
	identity := h.identity()

	if _, err := h.upsert("SKU-A", 1, h.snapshot(20), "idempotency-key-a00001"); err != nil {
		t.Fatalf("create: %v", err)
	}

	due, err := h.store.DueCarts(context.Background(), identity.Bucket, h.now, 100)
	if err != nil {
		t.Fatalf("DueCarts: %v", err)
	}
	if len(due) != 0 {
		t.Fatalf("a fresh cart is already due: %+v", due)
	}

	h.advance(31 * 24 * time.Hour)
	due, err = h.store.DueCarts(context.Background(), identity.Bucket, h.now, 100)
	if err != nil {
		t.Fatalf("DueCarts: %v", err)
	}
	if len(due) != 1 || due[0].UserToken != identity.UserToken {
		t.Fatalf("due = %+v", due)
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var digits []byte
	for n > 0 {
		digits = append([]byte{byte('0' + n%10)}, digits...)
		n /= 10
	}
	return string(digits)
}
