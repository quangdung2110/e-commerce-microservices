// Package handler serves the ForwardAuth and health endpoints.
package handler

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/gateway-auth/internal/domain"
	"github.com/ecommerce/gateway-auth/internal/domain/service"
)

// Identity headers returned to Traefik (runtime-contract section 6.1). Traefik
// copies exactly these onto the upstream request via authResponseHeaders.
const (
	HeaderUserID      = "X-User-Id"
	HeaderUserRoles   = "X-User-Roles"
	HeaderSessionID   = "X-User-Session-Id"
	HeaderTokenJti    = "X-Token-Jti"
	headerRequestID   = "X-Request-Id"
	headerForwardURI  = "X-Forwarded-Uri"
	problemTypePrefix = "https://contracts.ecommerce.local/problems/"
)

// TokenVerifier is the domain operation the handler needs.
type TokenVerifier interface {
	Verify(ctx context.Context, authorization string) (service.Identity, error)
}

// VerifyHandler answers Traefik ForwardAuth: 2xx with identity headers lets the
// request through, any other status is returned to the client as is.
type VerifyHandler struct {
	verifier TokenVerifier
	deadline time.Duration
	logger   *zap.Logger
}

func NewVerifyHandler(verifier TokenVerifier, deadline time.Duration, logger *zap.Logger) *VerifyHandler {
	return &VerifyHandler{verifier: verifier, deadline: deadline, logger: logger}
}

func (h *VerifyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), h.deadline)
	defer cancel()

	requestID := r.Header.Get(headerRequestID)
	if requestID == "" {
		requestID = uuid.NewString()
	}
	w.Header().Set("Cache-Control", "no-store")

	identity, err := h.verifier.Verify(ctx, r.Header.Get("Authorization"))
	if err == nil {
		w.Header().Set(HeaderUserID, identity.Subject)
		w.Header().Set(HeaderUserRoles, strings.Join(identity.Roles, ","))
		w.Header().Set(HeaderSessionID, identity.SessionID)
		w.Header().Set(HeaderTokenJti, identity.TokenID)
		w.WriteHeader(http.StatusOK)
		return
	}

	// A context that ran out while waiting on JWKS/Redis is a dependency
	// problem, not a bad token.
	if errors.Is(err, context.DeadlineExceeded) {
		err = errors.Join(domain.ErrDependencyUnavailable, err)
	}

	p := classify(err)
	p.Instance = instancePath(r)
	p.TraceID = requestID

	// Log the reason, never the token.
	h.logger.Info("verification rejected",
		zap.String("request_id", requestID),
		zap.String("code", p.Code),
		zap.Int("status", p.Status),
		zap.String("reason", err.Error()),
	)

	if p.Status == http.StatusUnauthorized {
		w.Header().Set("WWW-Authenticate", `Bearer realm="ecommerce-api"`)
	}
	if p.Status == http.StatusServiceUnavailable {
		w.Header().Set("Retry-After", "1")
	}
	writeProblem(w, p)
}

type problem struct {
	Type      string `json:"type"`
	Title     string `json:"title"`
	Status    int    `json:"status"`
	Detail    string `json:"detail"`
	Instance  string `json:"instance"`
	Code      string `json:"code"`
	TraceID   string `json:"trace_id"`
	Retryable bool   `json:"retryable"`
}

// classify maps a verification outcome to the problem+json vocabulary the
// frontend already understands (UNAUTHORIZED, TOKEN_INVALID, TOKEN_REVOKED,
// DEPENDENCY_UNAVAILABLE).
func classify(err error) problem {
	switch {
	case errors.Is(err, domain.ErrDependencyUnavailable):
		return problem{Type: problemTypePrefix + "dependency-unavailable", Title: "Service Unavailable",
			Status: http.StatusServiceUnavailable, Code: "DEPENDENCY_UNAVAILABLE",
			Detail: "Authentication could not be completed; retry later.", Retryable: true}
	case errors.Is(err, domain.ErrRevokedToken):
		return problem{Type: problemTypePrefix + "token-revoked", Title: "Unauthorized",
			Status: http.StatusUnauthorized, Code: "TOKEN_REVOKED",
			Detail: "The access token has been revoked."}
	case errors.Is(err, domain.ErrInvalidToken):
		return problem{Type: problemTypePrefix + "token-invalid", Title: "Unauthorized",
			Status: http.StatusUnauthorized, Code: "TOKEN_INVALID",
			Detail: "The access token is invalid or expired."}
	default:
		return problem{Type: problemTypePrefix + "unauthorized", Title: "Unauthorized",
			Status: http.StatusUnauthorized, Code: "UNAUTHORIZED",
			Detail: "A Bearer access token is required."}
	}
}

// instancePath is the original request path without its query string, which
// may carry sensitive values and must stay out of responses and logs.
func instancePath(r *http.Request) string {
	raw := r.Header.Get(headerForwardURI)
	if raw == "" {
		return r.URL.Path
	}
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Path
	}
	return ""
}

func writeProblem(w http.ResponseWriter, p problem) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(p.Status)
	_ = json.NewEncoder(w).Encode(p)
}
