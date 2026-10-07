package pii

import (
	"strings"
	"testing"
)

func TestKeyringHashesAreVersionedAndDomainSeparated(t *testing.T) {
	prev, err := ParsePreviousKeys("1:old-key-old-key-old-key-old-key-1")
	if err != nil {
		t.Fatal(err)
	}
	k, err := NewKeyring(2, []byte("new-key-new-key-new-key-new-key-22"), prev)
	if err != nil {
		t.Fatal(err)
	}
	if k.ActiveVersion() != 2 {
		t.Fatalf("active = %d", k.ActiveVersion())
	}
	subj := k.SubjectHashes("/services/order", "orders/1")
	if len(subj) != 2 || subj[0].Version != 2 || subj[1].Version != 1 {
		t.Fatalf("subject hashes not active-first: %+v", subj)
	}
	if subj[0].Hash == subj[1].Hash {
		t.Fatal("different keys must give different hashes")
	}
	if len(subj[0].Hash) != 64 {
		t.Fatalf("hash length %d", len(subj[0].Hash))
	}
	again := k.SubjectHashes("/services/order", "orders/1")
	if again[0] != subj[0] {
		t.Fatal("hash must be deterministic")
	}
	if other := k.SubjectHashes("/services/review", "orders/1"); other[0].Hash == subj[0].Hash {
		t.Fatal("source is part of the subject key")
	}
	user := k.UserHashes("6F1C0A2E-0000-4000-8000-000000000001")
	if user[0].Hash == subj[0].Hash {
		t.Fatal("user and subject domains must differ")
	}
	if lower := k.UserHashes("6f1c0a2e-0000-4000-8000-000000000001"); lower[0] != user[0] {
		t.Fatal("user id hashing is case-insensitive")
	}
	if strings.Contains(subj[0].Hash, "orders") {
		t.Fatal("raw subject leaked")
	}
}

func TestOrderingKeyRoundTrip(t *testing.T) {
	k, err := NewKeyring(3, []byte("key-key-key-key-key-key-key-key-33"), nil)
	if err != nil {
		t.Fatal(err)
	}
	key := k.OrderingKey("/services/order", "orders/1000234")
	if strings.Contains(key, "1000234") {
		t.Fatalf("ordering key carries the raw subject: %s", key)
	}
	src, h, ok := ParseOrderingKey(key)
	if !ok || src != "/services/order" || h.Version != 3 || h != k.SubjectHashes("/services/order", "orders/1000234")[0] {
		t.Fatalf("round trip failed: %q %+v %v", src, h, ok)
	}
	for _, bad := range []string{"", "x", "a:b", "/s:x:abc", "/s:1:" + strings.Repeat("a", 63)} {
		if _, _, ok := ParseOrderingKey(bad); ok {
			t.Fatalf("%q should not parse", bad)
		}
	}
}

func TestKeyringRejectsBadInput(t *testing.T) {
	if _, err := NewKeyring(0, []byte("k"), nil); err == nil {
		t.Fatal("version 0 must be rejected")
	}
	if _, err := NewKeyring(1, nil, nil); err == nil {
		t.Fatal("empty key must be rejected")
	}
	if _, err := NewKeyring(1, []byte("k"), map[int16][]byte{1: []byte("x")}); err == nil {
		t.Fatal("previous must not redefine the active version")
	}
	for _, raw := range []string{"1", "x:key", "1:", "1:a,1:b"} {
		if _, err := ParsePreviousKeys(raw); err == nil {
			t.Fatalf("%q should be rejected", raw)
		}
	}
	got, err := ParsePreviousKeys(" 1:a , 2:b ")
	if err != nil || len(got) != 2 || string(got[2]) != "b" {
		t.Fatalf("parse: %v %v", got, err)
	}
}
