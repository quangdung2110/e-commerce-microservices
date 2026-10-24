package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// The same vector is asserted by Auth (test/unit/api/internal_signature_test.go):
// both ends must build the same payload and HMAC.
func TestSignatureMatchesTheSharedTestVector(t *testing.T) {
	const path = "/internal/v1/auth/notification-deliveries/0b9c6f1e-4a5d-4c1b-9f53-2f1f0d1c2b3a/content"
	assert.Equal(t, "1700000000\nPOST\n"+path+"\nnotification-service",
		signaturePayload(1700000000, http.MethodPost, path, "notification-service"))
	assert.Equal(t, "t=1700000000,v1=f2b005edf2c963a6a7b01adfbc51a78126c126ffbd463e60bb6c38e97b8369bb",
		signatureHeader([]byte("shared-test-vector-key"), time.Unix(1700000000, 0), http.MethodPost, path, "notification-service"))
}

// With a signing key every call to Auth is signed for its own method and path at
// the client clock (mesh ADR-004).
func TestCallsToAuthAreSignedWithTheSharedKey(t *testing.T) {
	deliveryID := uuid.New()
	now := time.Date(2026, 10, 22, 12, 0, 0, 0, time.UTC)
	var seen string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.Header.Get(constants.HeaderServiceSignature)
		assert.Equal(t, signatureHeader([]byte("notification-signing-key-for-tests-0001"), now, r.Method, r.URL.EscapedPath(), "notification-service"), seen)
		_, _ = w.Write([]byte(`{"delivery_id":"` + deliveryID.String() + `","recipient_email":"otp@example.com","template":"PASSWORD_RESET","expires_at":"2026-01-01T00:15:00Z","template_data":{}}`))
	}))
	defer srv.Close()

	cfg := testConfig(srv.URL)
	cfg.SigningKey = "notification-signing-key-for-tests-0001"
	c, err := NewClient(cfg, 15*time.Minute, nil, telemetry.New(), nil, clock.NewFake(now))
	require.NoError(t, err)

	_, err = c.Content(context.Background(), entity.LaneCritical, deliveryID)
	require.NoError(t, err)
	assert.Contains(t, seen, "t="+strconv.FormatInt(now.Unix(), 10)+",v1=")
}

// Without a key (development) no signature header is sent.
func TestNoSignatureWithoutAKey(t *testing.T) {
	deliveryID := uuid.New()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Empty(t, r.Header.Get(constants.HeaderServiceSignature))
		_, _ = w.Write([]byte(`{"delivery_id":"` + deliveryID.String() + `","recipient_email":"otp@example.com","template":"PASSWORD_RESET","expires_at":"2026-01-01T00:15:00Z","template_data":{}}`))
	}))
	defer srv.Close()
	c, _ := newClient(t, srv, clock.System{})

	_, err := c.Content(context.Background(), entity.LaneCritical, deliveryID)
	require.NoError(t, err)
}
