package domain

import (
	"strings"
	"testing"
)

func TestNewIdentityIsDeterministic(t *testing.T) {
	first, err := NewIdentity("0198e098-94f4-7f8f-9e16-ae42dfbd93c1")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}
	second, err := NewIdentity("0198e098-94f4-7f8f-9e16-ae42dfbd93c1")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}

	if first != second {
		t.Fatalf("identity is not deterministic: %+v vs %+v", first, second)
	}
}

// A cart must never change bucket, so canonicalisation has to absorb the
// cosmetic differences an upstream service might introduce.
func TestNewIdentityCanonicalisesUserID(t *testing.T) {
	plain, err := NewIdentity("0198E098-94F4-7F8F-9E16-AE42DFBD93C1")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}
	padded, err := NewIdentity("  0198e098-94f4-7f8f-9e16-ae42dfbd93c1  ")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}

	if plain.UserToken != padded.UserToken {
		t.Fatalf("token differs across spellings: %q vs %q", plain.UserToken, padded.UserToken)
	}
	if plain.Bucket != padded.Bucket {
		t.Fatalf("bucket differs across spellings: %d vs %d", plain.Bucket, padded.Bucket)
	}
}

func TestNewIdentityRejectsEmptyUserID(t *testing.T) {
	for _, userID := range []string{"", "   ", "\t"} {
		if _, err := NewIdentity(userID); err == nil {
			t.Fatalf("expected an error for user id %q", userID)
		}
	}
}

// The token is what ends up in Redis keys, in the ZSET and inside cart_id, so
// it must be opaque and must not leak the user id.
func TestUserTokenIsOpaqueAndFixedLength(t *testing.T) {
	userID := "0198e098-94f4-7f8f-9e16-ae42dfbd93c1"
	identity, err := NewIdentity(userID)
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}

	if len(identity.UserToken) != userTokenLength {
		t.Fatalf("token length = %d, want %d", len(identity.UserToken), userTokenLength)
	}
	if strings.Contains(identity.UserToken, userID) {
		t.Fatal("token leaks the raw user id")
	}
	if identity.CartID != "c1:"+identity.UserToken {
		t.Fatalf("cart id = %q, want c1:<token>", identity.CartID)
	}
	// base64url must not produce a character that would need escaping in a
	// Redis key or a Kafka header.
	if strings.ContainsAny(identity.UserToken, "+/= :{}") {
		t.Fatalf("token %q contains an unsafe character", identity.UserToken)
	}
}

func TestBucketOfStaysInRange(t *testing.T) {
	seen := make(map[int]struct{})
	for i := 0; i < 5000; i++ {
		bucket := BucketOf(string(rune('a'+i%26)) + "-user-" + string(rune('0'+i%10)) + "-" + itoa(i))
		if bucket < 0 || bucket >= BucketCount {
			t.Fatalf("bucket %d is outside [0,%d)", bucket, BucketCount)
		}
		seen[bucket] = struct{}{}
	}

	// A routing function that only ever reaches a handful of buckets would
	// create hotspots without failing any other assertion.
	if len(seen) != BucketCount {
		t.Fatalf("only %d of %d buckets were reached", len(seen), BucketCount)
	}
}

func TestBucketTagIsZeroPadded(t *testing.T) {
	cases := map[int]string{0: "{b00}", 7: "{b07}", 17: "{b17}", 63: "{b63}"}
	for bucket, want := range cases {
		if got := BucketTag(bucket); got != want {
			t.Fatalf("BucketTag(%d) = %q, want %q", bucket, got, want)
		}
	}
}

func TestIdentityFromTokenRebuildsCartID(t *testing.T) {
	identity := IdentityFromToken(17, "abcdefghijklmnopqrstuv")
	if identity.CartID != "c1:abcdefghijklmnopqrstuv" {
		t.Fatalf("cart id = %q", identity.CartID)
	}
	if identity.Bucket != 17 {
		t.Fatalf("bucket = %d, want 17", identity.Bucket)
	}
	// The marker only ever sees a token, never a user id.
	if identity.UserID != "" {
		t.Fatalf("user id = %q, want empty", identity.UserID)
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
