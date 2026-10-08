package middleware

import (
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/user-service/internal/api/httpx"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
)

// HeaderServiceIdentity is the attribution header service callers send, equal to
// the caller's ServiceAccount name (docs/mesh/runtime-contract.md §6).
const HeaderServiceIdentity = "X-Service-Identity"

// ctxServiceIdentity is the gin key under which the admitted caller is kept for
// the rate limiter and the access log.
const ctxServiceIdentity = "service_identity"

// ServiceIdentity admits only the listed service identities on the internal
// listener, whose response carries a recipient name, a phone number and a
// street address.
//
// Who may reach the listener is decided by the mesh: Istio ambient terminates
// mTLS and enforces an AuthorizationPolicy per principal and path, and the
// application never sees the caller certificate. The header is therefore not
// proof of identity; this check is defence in depth, so a route reached through
// a misconfigured policy still refuses an unknown caller. A missing header is
// 401, an identity outside the list is 403, matching Cart, Inventory and Order.
func ServiceIdentity(allowed []string) gin.HandlerFunc {
	permitted := make(map[string]struct{}, len(allowed))
	for _, name := range allowed {
		if name = strings.TrimSpace(name); name != "" {
			permitted[name] = struct{}{}
		}
	}

	return func(c *gin.Context) {
		caller := strings.TrimSpace(c.GetHeader(HeaderServiceIdentity))
		if caller == "" {
			httpx.WriteProblem(c, apperr.ErrUnauthorized.WithDetail("A service identity is required."))
			return
		}
		if _, ok := permitted[caller]; !ok {
			httpx.WriteProblem(c, apperr.ErrForbidden.WithDetail("This service identity may not resolve address snapshots."))
			return
		}
		c.Set(ctxServiceIdentity, caller)
		c.Next()
	}
}

// ServiceIdentityFrom returns the caller admitted by ServiceIdentity.
func ServiceIdentityFrom(c *gin.Context) (string, bool) {
	caller, ok := c.Get(ctxServiceIdentity)
	if !ok {
		return "", false
	}
	name, ok := caller.(string)
	return name, ok
}
