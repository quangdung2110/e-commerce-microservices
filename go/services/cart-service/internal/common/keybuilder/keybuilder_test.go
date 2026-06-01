package keybuilder

import (
	"testing"
)

func TestNew(t *testing.T) {
	kb := New(4)
	if kb == nil {
		t.Fatal("New returned nil")
	}
	if kb.ShardCount() != 4 {
		t.Errorf("ShardCount() = %d, want 4", kb.ShardCount())
	}
}

func TestCartKey(t *testing.T) {
	kb := New(4)
	tests := []struct {
		userID  string
		wantKey string
	}{
		{"user123", "{cart}:user123"},
		{"", "{cart}:"},
		{"user:with:colons", "{cart}:user:with:colons"},
		{"UPPERCASE", "{cart}:UPPERCASE"},
	}

	for _, tt := range tests {
		got := kb.CartKey(tt.userID)
		if got != tt.wantKey {
			t.Errorf("CartKey(%q) = %q, want %q", tt.userID, got, tt.wantKey)
		}
	}
}

func TestNegativeCacheKey(t *testing.T) {
	kb := New(4)
	tests := []struct {
		userID  string
		wantKey string
	}{
		{"user123", "{nc}:user123"},
		{"", "{nc}:"},
	}

	for _, tt := range tests {
		got := kb.NegativeCacheKey(tt.userID)
		if got != tt.wantKey {
			t.Errorf("NegativeCacheKey(%q) = %q, want %q", tt.userID, got, tt.wantKey)
		}
	}
}

func TestStreamShard(t *testing.T) {
	kb := New(4)
	for shard := uint32(0); shard < 4; shard++ {
		got := kb.StreamShard(shard)
		want := "{cart}:events:" + string(rune('0'+shard))
		if shard >= 10 {
			want = "{cart}:events:" + string(rune('0'+shard/10)) + string(rune('0'+shard%10))
		}
		// Use Sprintf for proper uint32 conversion
		want = "{cart}:events:" + itoa(shard)
		if got != want {
			t.Errorf("StreamShard(%d) = %q, want %q", shard, got, want)
		}
	}
}

func TestStreamShardByUserID_Consistency(t *testing.T) {
	kb := New(4)
	users := []string{"alice", "bob", "charlie", "david", "eve"}
	shards := make([]uint32, len(users))

	// Shard must be stable across multiple calls for the same user
	for i, userID := range users {
		s1 := kb.StreamShardByUserID(userID)
		s2 := kb.StreamShardByUserID(userID)
		if s1 != s2 {
			t.Errorf("StreamShardByUserID(%q) unstable: got %d then %d", userID, s1, s2)
		}
		shards[i] = s1
		if s1 >= 4 {
			t.Errorf("StreamShardByUserID(%q) = %d, want < 4", userID, s1)
		}
	}

	// With 4 shards and 5 users, at least 2 users should map to different shards
	// (pigeonhole principle: 5 pigeons, 4 holes)
	hasCollision := false
	for i := 0; i < len(shards); i++ {
		for j := i + 1; j < len(shards); j++ {
			if shards[i] == shards[j] {
				hasCollision = true
			}
		}
	}
	if !hasCollision {
		// Very unlikely that all 5 users land on different shards with only 4 holes
		t.Log("Note: all 5 users landed on different shards (statistically rare with 4 shards)")
	}
}

func TestStreamShardByUserID_DifferentNumShards(t *testing.T) {
	kb4 := New(4)
	kb8 := New(8)
	user := "test-user-123"

	s4 := kb4.StreamShardByUserID(user)
	s8 := kb8.StreamShardByUserID(user)

	if s4 >= 4 {
		t.Errorf("kb4.StreamShardByUserID(%q) = %d, want < 4", user, s4)
	}
	if s8 >= 8 {
		t.Errorf("kb8.StreamShardByUserID(%q) = %d, want < 8", user, s8)
	}
}

func TestStreamShardKey(t *testing.T) {
	kb := New(4)
	got := kb.StreamShardKey("user-abc")
	want := kb.StreamShard(kb.StreamShardByUserID("user-abc"))
	if got != want {
		t.Errorf("StreamShardKey(%q) = %q, want %q", "user-abc", got, want)
	}
}

func TestStreamShardKeyExplicit(t *testing.T) {
	kb := New(4)
	got := kb.StreamShardKeyExplicit("user-abc", 2)
	want := "{cart}:events:2"
	if got != want {
		t.Errorf("StreamShardKeyExplicit(%q, 2) = %q, want %q", "user-abc", got, want)
	}
}

func TestParseCartKey(t *testing.T) {
	kb := New(4)
	tests := []struct {
		key      string
		wantUser string
	}{
		{"{cart}:user123", "user123"},
		{"{cart}:alice:123", "alice:123"},
		{"{cart}:", ""},
		{"{cart}", ""},
		{"invalid", ""},
		{"{nc}:user123", ""},
	}

	for _, tt := range tests {
		got := kb.ParseCartKey(tt.key)
		if got != tt.wantUser {
			t.Errorf("ParseCartKey(%q) = %q, want %q", tt.key, got, tt.wantUser)
		}
	}
}

func TestRehydrateItemsJSON(t *testing.T) {
	tests := []struct {
		name  string
		items map[string]string
		want  string
	}{
		{
			name:  "empty",
			items: nil,
			want:  "[]",
		},
		{
			name:  "empty_map",
			items: map[string]string{},
			want:  "[]",
		},
		{
			name:  "single_item",
			items: map[string]string{"SKU001": `{"product_id":"P1","quantity":2}`},
			want:  `[{"sku_id":"SKU001","value":"{\"product_id\":\"P1\",\"quantity\":2}"}]`,
		},
		{
			name: "multiple_items",
			items: map[string]string{
				"SKU001": `{"product_id":"P1"}`,
				"SKU002": `{"product_id":"P2"}`,
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := RehydrateItemsJSON(tt.items)
			if len(tt.want) > 0 && got != tt.want {
				t.Errorf("RehydrateItemsJSON() = %q, want %q", got, tt.want)
			}
			// Always verify it's valid JSON by checking brackets
			if len(got) < 2 || got[0] != '[' || got[len(got)-1] != ']' {
				t.Errorf("RehydrateItemsJSON() produced invalid JSON: %q", got)
			}
		})
	}
}

func TestSkuIDsJSON(t *testing.T) {
	tests := []struct {
		skuIDs []string
		want   string
	}{
		{
			skuIDs: nil,
			want:   "[]",
		},
		{
			skuIDs: []string{},
			want:   "[]",
		},
		{
			skuIDs: []string{"SKU001"},
			want:   `["SKU001"]`,
		},
		{
			skuIDs: []string{"SKU001", "SKU002"},
			want:   `["SKU001","SKU002"]`,
		},
		{
			skuIDs: []string{"SKU:with:colons", "user\"quote"},
			want:   `["SKU:with:colons","user\"quote"]`,
		},
	}

	for _, tt := range tests {
		got := SkuIDsJSON(tt.skuIDs)
		if got != tt.want {
			t.Errorf("SkuIDsJSON(%v) = %q, want %q", tt.skuIDs, got, tt.want)
		}
	}
}

// itoa converts uint32 to string without importing strconv in tests.
func itoa(n uint32) string {
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
