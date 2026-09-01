package cartredis

import (
	"strings"
	"testing"

	"cart-service/internal/domain"
)

// hashTag extracts the {...} slot tag Redis Cluster routes on.
func hashTag(key string) string {
	open := strings.Index(key, "{")
	closed := strings.Index(key, "}")
	if open < 0 || closed < open {
		return ""
	}
	return key[open : closed+1]
}

// Every key a single Lua invocation declares must share one hash tag, or Redis
// Cluster rejects the script with CROSSSLOT. This is the cheapest place to
// catch that, and the most expensive one to discover in production.
func TestScriptKeysShareOneHashTag(t *testing.T) {
	var keys Keys

	for _, userID := range []string{
		"0198e098-94f4-7f8f-9e16-ae42dfbd93c1",
		"a-different-user",
		"another-one-entirely",
	} {
		identity, err := domain.NewIdentity(userID)
		if err != nil {
			t.Fatalf("NewIdentity: %v", err)
		}

		groups := map[string][]string{
			"upsert_item": {
				keys.Items(identity),
				keys.Meta(identity),
				keys.Index(identity.Bucket),
				keys.Idempotency(identity, "adjust", "idempotency-key-0001"),
			},
			"remove_items": {
				keys.Items(identity),
				keys.Meta(identity),
				keys.Index(identity.Bucket),
				keys.Idempotency(identity, "delete", "idempotency-key-0001"),
			},
			"mark_abandoned": {
				keys.Items(identity),
				keys.Meta(identity),
				keys.Index(identity.Bucket),
				keys.Outbox(identity.Bucket),
			},
		}

		for script, scriptKeys := range groups {
			want := domain.BucketTag(identity.Bucket)
			for _, key := range scriptKeys {
				if got := hashTag(key); got != want {
					t.Fatalf("%s: key %q has tag %q, want %q", script, key, got, want)
				}
			}
		}
	}
}

func TestKeyLayoutMatchesTheDataContract(t *testing.T) {
	var keys Keys
	identity := domain.IdentityFromToken(7, "TOKEN")

	cases := map[string]string{
		keys.Items(identity): "cart:v2:{b07}:u:TOKEN:items",
		keys.Meta(identity):  "cart:v2:{b07}:u:TOKEN:meta",
		keys.Index(7):        "cart:v2:{b07}:abandonment-index",
		keys.Outbox(7):       "cart:v2:{b07}:outbox",
		keys.Idempotency(identity, "adjust", "abc-12345"): "cart:v2:{b07}:u:TOKEN:idemp:http:adjust:5fd46f3eb53076b2ee80c6a585a4c9ac06d522efa27b8957f79e24aece2820b6",
	}
	for got, want := range cases {
		if got != want {
			t.Fatalf("key = %q, want %q", got, want)
		}
	}
}

func TestIdempotencyKeysAreScopedToUserAndOperation(t *testing.T) {
	var keys Keys
	first := domain.IdentityFromToken(7, "USER-A")
	second := domain.IdentityFromToken(7, "USER-B")

	adjust := keys.Idempotency(first, "adjust", "idempotency-key-0001")
	if adjust == keys.Idempotency(second, "adjust", "idempotency-key-0001") {
		t.Fatal("two users in one bucket share an idempotency record")
	}
	if adjust == keys.Idempotency(first, "delete", "idempotency-key-0001") {
		t.Fatal("two HTTP operations share an idempotency record")
	}
}

// Two users in different buckets must not share a slot, or the whole point of
// bucketing the state is lost.
func TestDistinctBucketsProduceDistinctTags(t *testing.T) {
	var keys Keys
	tags := make(map[string]struct{})
	for bucket := 0; bucket < domain.BucketCount; bucket++ {
		tags[hashTag(keys.Outbox(bucket))] = struct{}{}
	}
	if len(tags) != domain.BucketCount {
		t.Fatalf("got %d distinct tags across %d buckets", len(tags), domain.BucketCount)
	}
}
