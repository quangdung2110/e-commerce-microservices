package keybuilder

import (
	"testing"
)

func TestCartKeyBuilder_BuildCartKey(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name     string
		userID   string
		want     string
		wantErr  bool
	}{
		{
			name:    "valid user ID",
			userID:  "12345",
			want:    "{cart:user:12345}",
			wantErr: false,
		},
		{
			name:    "valid user ID with alphanumeric",
			userID:  "user-abc-123",
			want:    "{cart:user:user-abc-123}",
			wantErr: false,
		},
		{
			name:    "empty user ID",
			userID:  "",
			want:    "",
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := builder.BuildCartKey(tt.userID)
			if (err != nil) != tt.wantErr {
				t.Errorf("BuildCartKey() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("BuildCartKey() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestCartKeyBuilder_BuildOutboxStreamKey(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name    string
		userID  string
		want    string
		wantErr bool
	}{
		{
			name:    "valid user ID",
			userID:  "12345",
			want:    "{cart:user:12345}:outbox",
			wantErr: false,
		},
		{
			name:    "empty user ID",
			userID:  "",
			want:    "",
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := builder.BuildOutboxStreamKey(tt.userID)
			if (err != nil) != tt.wantErr {
				t.Errorf("BuildOutboxStreamKey() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("BuildOutboxStreamKey() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestCartKeyBuilder_BuildNegativeCacheKey(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name    string
		userID  string
		want    string
		wantErr bool
	}{
		{
			name:    "valid user ID",
			userID:  "12345",
			want:    "{cart:user:12345}:notfound",
			wantErr: false,
		},
		{
			name:    "empty user ID",
			userID:  "",
			want:    "",
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := builder.BuildNegativeCacheKey(tt.userID)
			if (err != nil) != tt.wantErr {
				t.Errorf("BuildNegativeCacheKey() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("BuildNegativeCacheKey() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestCartKeyBuilder_BuildIdempotencyKey(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name    string
		orderID string
		want    string
	}{
		{
			name:    "valid order ID",
			orderID: "10001",
			want:    "idem:10001:PROCESSING",
		},
		{
			name:    "UUID order ID",
			orderID: "123e4567-e89b-12d3-a456-426614174000",
			want:    "idem:123e4567-e89b-12d3-a456-426614174000:PROCESSING",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := builder.BuildIdempotencyKey(tt.orderID)
			if got != tt.want {
				t.Errorf("BuildIdempotencyKey() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestCartKeyBuilder_ExtractUserIDFromCartID(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name    string
		cartID  string
		want    string
		wantErr bool
	}{
		{
			name:    "valid cart ID",
			cartID:  "{cart:user:12345}",
			want:    "12345",
			wantErr: false,
		},
		{
			name:    "valid cart ID with alphanumeric",
			cartID:  "{cart:user:user-abc-123}",
			want:    "user-abc-123",
			wantErr: false,
		},
		{
			name:    "invalid cart ID format",
			cartID:  "invalid-key",
			want:    "",
			wantErr: true,
		},
		{
			name:    "empty cart ID",
			cartID:  "",
			want:    "",
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := builder.ExtractUserIDFromCartID(tt.cartID)
			if (err != nil) != tt.wantErr {
				t.Errorf("ExtractUserIDFromCartID() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("ExtractUserIDFromCartID() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestCartKeyBuilder_IsValidCartKey(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name   string
		cartID string
		want   bool
	}{
		{
			name:   "valid cart ID",
			cartID: "{cart:user:12345}",
			want:   true,
		},
		{
			name:   "valid cart ID with alphanumeric",
			cartID: "{cart:user:user-abc-123}",
			want:   true,
		},
		{
			name:   "invalid - missing braces",
			cartID: "cart:user:12345",
			want:   false,
		},
		{
			name:   "invalid - wrong format",
			cartID: "{cart:product:12345}",
			want:   false,
		},
		{
			name:   "invalid - nested braces",
			cartID: "{cart:user:{nested}}",
			want:   false,
		},
		{
			name:   "invalid - empty",
			cartID: "",
			want:   false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := builder.IsValidCartKey(tt.cartID)
			if got != tt.want {
				t.Errorf("IsValidCartKey(%q) = %v, want %v", tt.cartID, got, tt.want)
			}
		})
	}
}

func TestCartKeyBuilder_IsSameHashSlot(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name  string
		key1  string
		key2  string
		match bool
	}{
		{
			name:  "same hash tag",
			key1:  "{cart:user:12345}",
			key2:  "{cart:user:12345}:outbox",
			match: true,
		},
		{
			name:  "different user IDs",
			key1:  "{cart:user:12345}",
			key2:  "{cart:user:67890}",
			match: false,
		},
		{
			name:  "no hash tag",
			key1:  "outbox_publish_intents",
			key2:  "{cart:user:12345}",
			match: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := builder.IsSameHashSlot(tt.key1, tt.key2)
			if got != tt.match {
				t.Errorf("IsSameHashSlot(%q, %q) = %v, want %v", tt.key1, tt.key2, got, tt.match)
			}
		})
	}
}

func TestCartKeyBuilder_ValidateKeyPair(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name    string
		key1    string
		key2    string
		wantErr bool
	}{
		{
			name:    "valid pair",
			key1:    "{cart:user:12345}",
			key2:    "{cart:user:12345}:outbox",
			wantErr: false,
		},
		{
			name:    "CROSSSLOT error - different users",
			key1:    "{cart:user:12345}",
			key2:    "{cart:user:67890}",
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := builder.ValidateKeyPair(tt.key1, tt.key2)
			if (err != nil) != tt.wantErr {
				t.Errorf("ValidateKeyPair() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestCartKeyBuilder_IsValidSkuID(t *testing.T) {
	builder := NewCartKeyBuilder()

	tests := []struct {
		name  string
		skuID string
		want  bool
	}{
		{
			name:  "valid SKU ID",
			skuID: "sku-1001",
			want:  true,
		},
		{
			name:  "valid SKU ID with underscore",
			skuID: "sku_1001",
			want:  true,
		},
		{
			name:  "valid SKU ID with only numbers",
			skuID: "1001",
			want:  true,
		},
		{
			name:  "invalid - special characters",
			skuID: "sku:1001",
			want:  false,
		},
		{
			name:  "invalid - spaces",
			skuID: "sku 1001",
			want:  false,
		},
		{
			name:  "empty",
			skuID: "",
			want:  false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := builder.IsValidSkuID(tt.skuID)
			if got != tt.want {
				t.Errorf("IsValidSkuID(%q) = %v, want %v", tt.skuID, got, tt.want)
			}
		})
	}
}

func TestExtractHashTag(t *testing.T) {
	tests := []struct {
		name string
		key  string
		want string
	}{
		{
			name: "valid hash tag",
			key:  "{cart:user:12345}",
			want: "{cart:user:12345}",
		},
		{
			name: "no hash tag",
			key:  "outbox_publish_intents",
			want: "",
		},
		{
			name: "nested braces",
			key:  "{cart:user:{id}}",
			want: "{cart:user:{id}", // Returns up to first closing brace
		},
		{
			name: "empty",
			key:  "",
			want: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := extractHashTag(tt.key)
			if got != tt.want {
				t.Errorf("extractHashTag(%q) = %v, want %v", tt.key, got, tt.want)
			}
		})
	}
}
