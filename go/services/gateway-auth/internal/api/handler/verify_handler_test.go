package handler

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/gateway-auth/internal/domain"
	"github.com/ecommerce/gateway-auth/internal/domain/service"
)

type stubVerifier struct {
	identity service.Identity
	err      error
}

func (s stubVerifier) Verify(context.Context, string) (service.Identity, error) {
	return s.identity, s.err
}

func serve(t *testing.T, verifier TokenVerifier) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/verify", nil)
	req.Header.Set("Authorization", "Bearer x")
	req.Header.Set("X-Request-Id", "req-test-0001")
	req.Header.Set("X-Forwarded-Uri", "/api/v1/auth/sessions?cursor=secret")
	rec := httptest.NewRecorder()
	NewVerifyHandler(verifier, time.Second, zap.NewNop()).ServeHTTP(rec, req)
	return rec
}

func TestVerifyHandler_SuccessReturnsExactlyTheIdentityHeaders(t *testing.T) {
	rec := serve(t, stubVerifier{identity: service.Identity{
		Subject: "sub-1", Roles: []string{"USER", "ADMIN"}, SessionID: "sid-1", TokenID: "jti-1",
	}})

	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "sub-1", rec.Header().Get("X-User-Id"))
	assert.Equal(t, "USER,ADMIN", rec.Header().Get("X-User-Roles"))
	assert.Equal(t, "sid-1", rec.Header().Get("X-User-Session-Id"))
	assert.Equal(t, "jti-1", rec.Header().Get("X-Token-Jti"))
	assert.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
}

func TestVerifyHandler_MapsOutcomesToProblems(t *testing.T) {
	cases := []struct {
		err    error
		status int
		code   string
	}{
		{domain.ErrMissingCredentials, http.StatusUnauthorized, "UNAUTHORIZED"},
		{errors.Join(domain.ErrInvalidToken, errors.New("expired")), http.StatusUnauthorized, "TOKEN_INVALID"},
		{domain.ErrRevokedToken, http.StatusUnauthorized, "TOKEN_REVOKED"},
		{errors.Join(domain.ErrDependencyUnavailable, errors.New("redis")), http.StatusServiceUnavailable, "DEPENDENCY_UNAVAILABLE"},
		{context.DeadlineExceeded, http.StatusServiceUnavailable, "DEPENDENCY_UNAVAILABLE"},
	}
	for _, tc := range cases {
		rec := serve(t, stubVerifier{err: tc.err})

		assert.Equal(t, tc.status, rec.Code, tc.code)
		assert.Equal(t, "application/problem+json", rec.Header().Get("Content-Type"))
		assert.Empty(t, rec.Header().Get("X-User-Id"), "no identity on failure")

		var body map[string]any
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
		assert.Equal(t, tc.code, body["code"])
		assert.Equal(t, "req-test-0001", body["trace_id"])
		assert.Equal(t, "/api/v1/auth/sessions", body["instance"], "query string is never echoed")
	}
}
