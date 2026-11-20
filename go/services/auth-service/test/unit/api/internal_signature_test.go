package api_test

import (
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/middleware"
	"github.com/ecommerce/auth-service/internal/api/router"
	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

const signingKey = "auth-internal-signing-key-for-unit-tests-0001"

// The same vector is asserted by Notification's auth client test
// (internal/infrastructure/auth/signature_test.go); both ends must agree on it.
// Reproduce with: printf '<payload>' | openssl dgst -sha256 -hmac shared-test-vector-key
func TestSignatureMatchesTheSharedTestVector(t *testing.T) {
	const path = "/internal/v1/auth/notification-deliveries/0b9c6f1e-4a5d-4c1b-9f53-2f1f0d1c2b3a/content"
	require.Equal(t, "1700000000\nPOST\n"+path+"\nnotification-service",
		middleware.SignaturePayload(1700000000, http.MethodPost, path, "notification-service"))
	signature := middleware.SignRequest([]byte("shared-test-vector-key"), 1700000000, http.MethodPost, path, "notification-service")
	require.Equal(t, "f2b005edf2c963a6a7b01adfbc51a78126c126ffbd463e60bb6c38e97b8369bb", hex.EncodeToString(signature))
}

// signedApp is the internal engine with a signing key.
func signedApp(t *testing.T) (*gin.Engine, *stubAccountRepo) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	cfg := &config.Config{}
	cfg.App = config.AppConfig{Name: "auth-service", Env: "test"}
	cfg.Security.RateLimit.InternalPerResource = config.RateLimitRule{Limit: 120, Window: time.Minute}
	cfg.Internal = config.InternalConfig{
		AllowedCallers:     []string{notification},
		SigningKey:         signingKey,
		SignatureTolerance: time.Minute,
	}
	accounts := newStubAccountRepo()
	engine := router.NewInternal(router.InternalDeps{
		Config:   cfg,
		Logger:   zap.NewNop(),
		Limiter:  &stubLimiter{},
		Delivery: handler.NewDeliveryHandler(service.NewDeliveryService(stubDeliveryStore{})),
		Contact:  handler.NewContactHandler(service.NewContactService(accounts)),
		Health:   handler.NewHealthHandler(time.Second),
	})
	return engine, accounts
}

func signedRequest(method, path, caller, header string) *http.Request {
	req := httptest.NewRequest(method, path, nil)
	req.Header.Set(constants.HeaderServiceIdentity, caller)
	if header != "" {
		req.Header.Set(constants.HeaderServiceSignature, header)
	}
	return req
}

func signatureHeader(key string, at time.Time, method, path, caller string) string {
	mac := middleware.SignRequest([]byte(key), at.Unix(), method, path, caller)
	return fmt.Sprintf("t=%d,v1=%s", at.Unix(), hex.EncodeToString(mac))
}

// Without a mesh X-Service-Identity is only a claim: with a signing key the
// internal API answers only a request signed with that key, recently, for this
// method, path and caller (mesh ADR-004).
func TestInternalAPIRequiresAValidSignatureWhenAKeyIsConfigured(t *testing.T) {
	engine, accounts := signedApp(t)
	app := &internalApp{accounts: accounts}
	account := app.verifiedAccount(t)
	path := contactPath(account.ID.String())
	now := time.Now()

	cases := map[string]string{
		"no signature":    "",
		"garbage":         "t=abc,v1=zz",
		"wrong key":       signatureHeader("another-key-another-key-another-key", now, http.MethodGet, path, notification),
		"other path":      signatureHeader(signingKey, now, http.MethodGet, contactPath(uuid.NewString()), notification),
		"other method":    signatureHeader(signingKey, now, http.MethodPost, path, notification),
		"other caller":    signatureHeader(signingKey, now, http.MethodGet, path, "order-service"),
		"expired":         signatureHeader(signingKey, now.Add(-2*time.Minute), http.MethodGet, path, notification),
		"from the future": signatureHeader(signingKey, now.Add(2*time.Minute), http.MethodGet, path, notification),
		"timestamp only":  fmt.Sprintf("t=%d", now.Unix()),
		"signature only":  "v1=" + hex.EncodeToString(make([]byte, 32)),
	}
	for name, header := range cases {
		t.Run(name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			engine.ServeHTTP(rec, signedRequest(http.MethodGet, path, notification, header))
			require.Equal(t, http.StatusUnauthorized, rec.Code, rec.Body.String())
			require.Equal(t, apperr.CodeUnauthorized, problemCode(t, rec))
			require.NotContains(t, rec.Body.String(), "@")
		})
	}

	rec := httptest.NewRecorder()
	engine.ServeHTTP(rec, signedRequest(http.MethodGet, path, notification,
		signatureHeader(signingKey, now, http.MethodGet, path, notification)))
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
}

// The delivery content route, the one that returns OTPs and reset links, is
// behind the same check.
func TestDeliveryContentRequiresAValidSignature(t *testing.T) {
	engine, _ := signedApp(t)
	path := "/internal/v1/auth/notification-deliveries/" + uuid.NewString() + "/content"

	rec := httptest.NewRecorder()
	engine.ServeHTTP(rec, signedRequest(http.MethodPost, path, notification, ""))
	require.Equal(t, http.StatusUnauthorized, rec.Code)

	rec = httptest.NewRecorder()
	engine.ServeHTTP(rec, signedRequest(http.MethodPost, path, notification,
		signatureHeader(signingKey, time.Now(), http.MethodPost, path, notification)))
	require.Equal(t, http.StatusNotFound, rec.Code, "signed: reaches the handler; the stub store holds no delivery")
}
