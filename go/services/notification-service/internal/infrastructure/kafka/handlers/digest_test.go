package handlers

import (
	"encoding/json"
	"testing"
)

// The sequence ledger compares digests, so a producer re-serialising the
// same command (key order, whitespace) must not look like a collision, while
// any value change must.
func TestPayloadDigestIsCanonical(t *testing.T) {
	a := payloadDigest(json.RawMessage(`{"template":"ORDER_CONFIRMED","template_data":{"total_minor":398000,"b":[1,2]},"user_id":"u"}`))
	b := payloadDigest(json.RawMessage(`{ "user_id" : "u", "template_data" : { "b" : [1, 2], "total_minor" : 398000 }, "template" : "ORDER_CONFIRMED" }`))
	if a != b || len(a) != 64 {
		t.Fatalf("key order / whitespace must not change the digest: %s vs %s", a, b)
	}
	for name, raw := range map[string]string{
		"value":      `{"template":"ORDER_CONFIRMED","template_data":{"total_minor":398001,"b":[1,2]},"user_id":"u"}`,
		"array":      `{"template":"ORDER_CONFIRMED","template_data":{"total_minor":398000,"b":[2,1]},"user_id":"u"}`,
		"big number": `{"template":"ORDER_CONFIRMED","template_data":{"total_minor":398000.0,"b":[1,2]},"user_id":"u"}`,
	} {
		if payloadDigest(json.RawMessage(raw)) == a {
			t.Fatalf("%s change must change the digest", name)
		}
	}
	if payloadDigest(json.RawMessage(`not json`)) == "" {
		t.Fatal("non-JSON data is hashed as raw bytes")
	}
}
