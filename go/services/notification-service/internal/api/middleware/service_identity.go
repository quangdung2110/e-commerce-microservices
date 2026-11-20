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

// maxPrincipalLength bounds the header so it cannot bloat logs and audit rows.
const maxPrincipalLength = 128

// ServiceIdentity admits only the listed service identities on a group of the
// internal listener (status or ops, 11-privacy.md §5). The principal is stored
// in the request context for the audit trail.
//
// Who may reach the listener is decided by the mesh: Istio ambient terminates
// mTLS and enforces an AuthorizationPolicy per principal and path, and the
// application never sees the caller certificate. The header is therefore not
// proof of identity; this check is defence in depth, so a route reached through
// a misconfigured policy still refuses an unknown caller. A missing header is
// 401, an identity outside the list is 403, in every environment — the same
// model as Auth, User, Cart, Inventory and Order (decisions.md D-21).
func ServiceIdentity(allowed []string) gin.HandlerFunc {
	permitted := make(map[string]struct{}, len(allowed))
	for _, p := range allowed {
		if p = strings.TrimSpace(p); p != "" {
			permitted[p] = struct{}{}
		}
	}
	return func(c *gin.Context) {
		principal := strings.TrimSpace(c.GetHeader(constants.HeaderServiceIdentity))
		if principal == "" || len(principal) > maxPrincipalLength {
			httpx.WriteProblem(c, apperr.New(apperr.CodeUnauthorized, "A service identity is required."))
			return
		}
		if _, ok := permitted[principal]; !ok {
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

// PrincipalFrom returns the principal admitted by ServiceIdentity.
func PrincipalFrom(c *gin.Context) string {
	return reqctx.Principal(c.Request.Context())
}
