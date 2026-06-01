package outbox_relay

import (
	"testing"

	"cart-service/internal/common/keybuilder"
)

func TestExtractCartIDFromPayload(t *testing.T) {
	tests := []struct {
		name     string
		payload  string
		wantCart string
		wantUser string
	}{
		{
			name:     "valid payload with cart_id and user_id",
			payload:  `{"cart_id":"{cart}:user123","user_id":"user123","sku_id":"sku001","quantity":2}`,
			wantCart: "{cart}:user123",
			wantUser: "user123",
		},
		{
			name:     "valid payload with only cart_id",
			payload:  `{"cart_id":"{cart}:user456","quantity":5}`,
			wantCart: "{cart}:user456",
			wantUser: "",
		},
		{
			name:     "empty payload",
			payload:  `{}`,
			wantCart: "",
			wantUser: "",
		},
		{
			name:     "invalid json",
			payload:  `{invalid}`,
			wantCart: "",
			wantUser: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			gotCart := extractCartIDFromPayload(tt.payload)
			if gotCart != tt.wantCart {
				t.Errorf("extractCartIDFromPayload() cart = %q, want %q", gotCart, tt.wantCart)
			}
			gotUser := extractUserIDFromPayload(tt.payload)
			if gotUser != tt.wantUser {
				t.Errorf("extractUserIDFromPayload() = %q, want %q", gotUser, tt.wantUser)
			}
		})
	}
}

func TestExtractCorrelationID(t *testing.T) {
	tests := []struct {
		name       string
		payload    string
		wantCorrID string
	}{
		{
			name:       "with correlation_id",
			payload:    `{"cart_id":"{cart}:user1","correlation_id":"req-123"}`,
			wantCorrID: "req-123",
		},
		{
			name:       "without correlation_id",
			payload:    `{"cart_id":"{cart}:user1"}`,
			wantCorrID: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := extractCorrelationID(tt.payload)
			if got != tt.wantCorrID {
				t.Errorf("extractCorrelationID() = %q, want %q", got, tt.wantCorrID)
			}
		})
	}
}

func TestDeterministicEventID(t *testing.T) {
	id1 := deterministicEventID("1234567890-0", "{cart}:user1")
	id2 := deterministicEventID("1234567890-0", "{cart}:user1")
	id3 := deterministicEventID("1234567890-1", "{cart}:user1")

	if id1 != id2 {
		t.Errorf("same inputs should produce same ID: %q != %q", id1, id2)
	}

	if id1 == id3 {
		t.Errorf("different inputs should produce different ID: %q == %q", id1, id3)
	}

	if len(id1) != 32 {
		t.Errorf("event ID should be 32 characters, got %d", len(id1))
	}
}

func TestParseDuration(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"5s", "5s"},
		{"10s", "10s"},
		{"", "5s"},
		{"invalid", "5s"},
	}

	for _, tt := range tests {
		t.Run(tt.input, func(t *testing.T) {
			got := parseDuration(tt.input)
			if got.String() != tt.expected {
				t.Errorf("parseDuration(%q) = %v, want %v", tt.input, got, tt.expected)
			}
		})
	}
}

func TestContainsString(t *testing.T) {
	if !containsString("BUSYGROUP Consumer Group name already exists", "BUSYGROUP Consumer Group name already exists") {
		t.Error("containsString should return true for exact match")
	}
	if containsString("hello world", "xyz") {
		t.Error("containsString should return false for non-match")
	}
}

func TestExtractPayloadMap(t *testing.T) {
	payload := `{"cart_id":"{cart}:user1","sku_id":"sku001","quantity":2}`
	m := extractPayloadMap(payload)

	if m["cart_id"] != "{cart}:user1" {
		t.Errorf("cart_id = %v, want {cart}:user1", m["cart_id"])
	}
	if m["sku_id"] != "sku001" {
		t.Errorf("sku_id = %v, want sku001", m["sku_id"])
	}
	if m["quantity"] != float64(2) {
		t.Errorf("quantity = %v, want 2", m["quantity"])
	}

	empty := extractPayloadMap("{invalid")
	if len(empty) != 0 {
		t.Errorf("invalid JSON should return empty map")
	}
}

func TestWorkerPool(t *testing.T) {
	kb := keybuilder.New(4)
	_ = kb

	pool := &WorkerPool{
		workers: make([]*Worker, 4),
	}

	if pool.WorkerCount() != 4 {
		t.Errorf("WorkerCount() = %d, want 4", pool.WorkerCount())
	}
}

func TestStreamShardKeyWithKB(t *testing.T) {
	kb := keybuilder.New(4)
	key := StreamShardKeyWithKB(2, kb)
	expected := "{cart}:events:2"
	if key != expected {
		t.Errorf("StreamShardKeyWithKB(2) = %q, want %q", key, expected)
	}
}

func TestShardKeyForUserID(t *testing.T) {
	kb := keybuilder.New(4)
	key := ShardKeyForUserID("user123", 4, kb)
	if key == "" {
		t.Error("ShardKeyForUserID should not return empty string")
	}
}
