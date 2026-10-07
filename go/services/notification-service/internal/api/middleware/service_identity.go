package middleware

import (
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/common/constants"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
)

// ServiceIdentityConfig tells the middleware how a peer proves who it is.
type ServiceIdentityConfig struct {
	// MutualTLS: the principal is the CN of the verified client certificate
	// (the TLS server was built with RequireAndVerifyClientCert).
	MutualTLS bool
	// Development: without mTLS, accept the unauthenticated X-Service-Identity
	// header. Only true when app.env is development (13-development.md §7);
	// every other environment without mTLS answers 401.
	Development bool
}

// ServiceIdentity resolves the peer principal for the internal listener and
// checks it against the allowlist of the endpoint group (status or ops,
// 11-privacy.md §5). The principal is stored in the request context for the
// audit trail.
func ServiceIdentity(cfg ServiceIdentityConfig, allowed []string) gin.HandlerFunc {
	allow := make(map[string]struct{}, len(allowed))
	for _, p := range allowed {
		if p = strings.TrimSpace(p); p != "" {
			allow[p] = struct{}{}
		}
	}
	return func(c *gin.Context) {
		principal, ok := resolvePrincipal(c, cfg)
		if !ok {
			httpx.WriteProblem(c, apperr.New(apperr.CodeUnauthorized, "A verified service identity is required."))
			return
		}
		if _, permitted := allow[principal]; !permitted {
			logging.FromContext(c.Request.Context()).Info("service identity not allowlisted",
				zap.String("principal", principal))
			httpx.WriteProblem(c, apperr.New(apperr.CodeForbidden, "This service identity may not call this endpoint."))
			return
		}
		ctx := reqctx.WithPrincipal(c.Request.Context(), principal)
		scoped := logging.FromContext(ctx).With(zap.String("principal", principal))
		c.Request = c.Request.WithContext(logging.WithContext(ctx, scoped))
		c.Next()
	}
}

func resolvePrincipal(c *gin.Context, cfg ServiceIdentityConfig) (string, bool) {
	if cfg.MutualTLS {
		state := c.Request.TLS
		if state == nil {
			return "", false
		}
		for _, chain := range state.VerifiedChains {
			if len(chain) > 0 && chain[0].Subject.CommonName != "" {
				return chain[0].Subject.CommonName, true
			}
		}
		// Some proxies terminate TLS and hand over the peer chain unverified;
		// fall back to the leaf certificate only when the server verified it.
		if len(state.PeerCertificates) > 0 && len(state.VerifiedChains) > 0 {
			return state.PeerCertificates[0].Subject.CommonName, true
		}
		return "", false
	}
	if !cfg.Development {
		return "", false
	}
	principal := strings.TrimSpace(c.GetHeader(constants.HeaderServiceIdentity))
	if principal == "" || len(principal) > 128 {
		return "", false
	}
	return principal, true
}

// PrincipalFrom returns the verified principal of the request.
func PrincipalFrom(c *gin.Context) string {
	return reqctx.Principal(c.Request.Context())
}
