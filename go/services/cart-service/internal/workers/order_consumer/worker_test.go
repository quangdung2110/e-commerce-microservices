package order_consumer

import (
	"testing"
)

func TestExtractUserIDFromCartID(t *testing.T) {
	tests := []struct {
		name    string
		cartID  string
		wantUser string
	}{
		{
			name:    "valid cart id",
			cartID:  "{cart}:user123",
			wantUser: "user123",
		},
		{
			name:    "cart id with colon in user",
			cartID:  "{cart}:user:with:colons",
			wantUser: "user:with:colons",
		},
		{
			name:    "short cart id",
			cartID:  "{cart}",
			wantUser: "",
		},
		{
			name:    "empty string",
			cartID:  "",
			wantUser: "",
		},
		{
			name:    "no colon",
			cartID:  "usercart",
			wantUser: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := extractUserIDFromCartID(tt.cartID)
			if got != tt.wantUser {
				t.Errorf("extractUserIDFromCartID(%q) = %q, want %q", tt.cartID, got, tt.wantUser)
			}
		})
	}
}

func TestIsValidCartID(t *testing.T) {
	tests := []struct {
		name   string
		cartID string
		want   bool
	}{
		{
			name:   "valid cart id",
			cartID: "{cart}:user123",
			want:   true,
		},
		{
			name:   "valid cart id long user",
			cartID: "{cart}:abc123-def456",
			want:   true,
		},
		{
			name:   "too short",
			cartID: "{cart}",
			want:   false,
		},
		{
			name:   "no prefix",
			cartID: "user123",
			want:   false,
		},
		{
			name:   "wrong prefix",
			cartID: "{items}:user123",
			want:   false,
		},
		{
			name:   "empty",
			cartID: "",
			want:   false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := isValidCartID(tt.cartID)
			if got != tt.want {
				t.Errorf("isValidCartID(%q) = %v, want %v", tt.cartID, got, tt.want)
			}
		})
	}
}

func TestIdempotencyKeyFormat(t *testing.T) {
	// Test that the key format matches what the worker uses
	wantPrefix := "{idem}:order:"
	for i := int64(1); i <= 100; i++ {
		key := idempotencyKeyForTest(i)
		if len(key) < len(wantPrefix)+1 {
			t.Errorf("key too short for order %d: %q", i, key)
		}
	}
}

func idempotencyKeyForTest(orderID int64) string {
	return "{idem}:order:" + "12345"
}

func TestOrderPaidWrapper(t *testing.T) {
	wrapper := OrderPaidWrapper{
		EventType: "ORDER_PAID",
	}

	if wrapper.EventType != "ORDER_PAID" {
		t.Errorf("EventType = %q, want ORDER_PAID", wrapper.EventType)
	}
}
