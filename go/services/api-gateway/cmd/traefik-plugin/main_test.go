package main

import (
	"testing"
)

// TestJWKSCacheCreation tests JWKSCache creation
func TestJWKSCacheCreation(t *testing.T) {
	cache := NewJWKSCache("http://localhost:8081/.well-known/jwks.json", 10, nil)

	if cache == nil {
		t.Fatal("JWKSCache should not be nil")
	}

	if cache.ttl.Minutes() != 10 {
		t.Errorf("Expected TTL of 10 minutes, got %v", cache.ttl)
	}
}

// TestJWKSCacheOperations tests cache operations
func TestJWKSCacheOperations(t *testing.T) {
	cache := NewJWKSCache("http://localhost:8081/.well-known/jwks.json", 10, nil)

	// Test ClearCache
	cache.ClearCache()

	// Test InvalidateKey (should not panic)
	cache.InvalidateKey("test-kid")
}

// TestClaimsStruct tests Claims struct
func TestClaimsStruct(t *testing.T) {
	claims := &Claims{
		Subject:   "user123",
		Email:     "test@example.com",
		Roles:     []string{"admin", "user"},
		DeviceID:  "device-001",
		SessionID: "session-001",
		JTI:       "jti-001",
		IAT:       1234567890,
		EXP:       1234567890,
	}

	if claims.Subject != "user123" {
		t.Errorf("Expected Subject 'user123', got '%s'", claims.Subject)
	}

	if len(claims.Roles) != 2 {
		t.Errorf("Expected 2 roles, got %d", len(claims.Roles))
	}
}

// TestPluginConfigDefaults tests PluginConfig default values
func TestPluginConfigDefaults(t *testing.T) {
	cfg := CreateConfig()

	if cfg.AuthServiceURL != "" {
		t.Errorf("Expected empty AuthServiceURL, got '%s'", cfg.AuthServiceURL)
	}

	if cfg.JWKSCacheTTLMinutes != 0 {
		t.Errorf("Expected 0 JWKSCacheTTLMinutes, got %d", cfg.JWKSCacheTTLMinutes)
	}

	if len(cfg.PublicPaths) != 0 {
		t.Errorf("Expected empty PublicPaths, got %v", cfg.PublicPaths)
	}
}

// TestExtractBearerToken tests token extraction
func TestExtractBearerToken(t *testing.T) {
	tests := []struct {
		name       string
		authHeader string
		want       string
		wantErr    bool
	}{
		{
			name:       "Valid bearer token",
			authHeader: "Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9",
			want:       "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9",
			wantErr:    false,
		},
		{
			name:       "Empty header",
			authHeader: "",
			want:       "",
			wantErr:    true,
		},
		{
			name:       "Invalid format",
			authHeader: "InvalidToken",
			want:       "",
			wantErr:    true,
		},
		{
			name:       "Basic auth",
			authHeader: "Basic dXNlcjpwYXNz",
			want:       "",
			wantErr:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := extractBearerToken(tt.authHeader)
			if (err != nil) != tt.wantErr {
				t.Errorf("extractBearerToken() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("extractBearerToken() = %v, want %v", got, tt.want)
			}
		})
	}
}

// TestExtractKID tests KID extraction
func TestExtractKID(t *testing.T) {
	// Valid JWT header with kid - header.payload.signature
	// {"alg":"RS256","typ":"JWT","kid":"test-key-123"}
	// base64 encoded = eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InRlc3Qta2V5LTEyMyJ9
	header := "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InRlc3Qta2V5LTEyMyJ9"
	token := header + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature"

	kid, err := extractKID(token)
	if err != nil {
		t.Errorf("extractKID() error = %v", err)
	}
	if kid != "test-key-123" {
		t.Errorf("extractKID() = %v, want %v", kid, "test-key-123")
	}

	// Invalid token - not enough parts
	_, err = extractKID("invalid")
	if err == nil {
		t.Error("extractKID() should return error for invalid token")
	}
}

// TestParseRoles tests role parsing
func TestParseRoles(t *testing.T) {
	tests := []struct {
		name   string
		roles  string
		expect int
	}{
		{"Single role", "admin", 1},
		{"Multiple roles", "admin,user,moderator", 3},
		{"Empty string", "", 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := parseRoles(tt.roles)
			if len(got) != tt.expect {
				t.Errorf("parseRoles() = %v, want length %d", got, tt.expect)
			}
		})
	}
}

// TestGenerateTraceID tests trace ID generation
func TestGenerateTraceID(t *testing.T) {
	traceID := generateTraceID()

	if len(traceID) != 16 {
		t.Errorf("Expected trace ID length of 16, got %d", len(traceID))
	}

	// Generate another one and verify they're different
	traceID2 := generateTraceID()
	if traceID == traceID2 {
		t.Error("Generated trace IDs should be unique")
	}
}

// TestJWKSInvalidationEvent tests event parsing
func TestJWKSInvalidationEvent(t *testing.T) {
	event := JWKSInvalidationEvent{
		Type:      "key_rotation",
		Kid:       "key-123",
		Timestamp: "2026-05-16T10:00:00Z",
	}

	if event.Type != "key_rotation" {
		t.Errorf("Expected Type 'key_rotation', got '%s'", event.Type)
	}

	if event.Kid != "key-123" {
		t.Errorf("Expected Kid 'key-123', got '%s'", event.Kid)
	}
}

// Helper to base64URL encode
func base64URLEncode(data string) string {
	// Simple URL-safe base64 encoding for test
	encoded := ""
	for _, c := range data {
		switch c {
		case '+':
			encoded += "-"
		case '/':
			encoded += "_"
		case '=':
			// Skip padding
		default:
			encoded += string(c)
		}
	}
	return encoded
}

