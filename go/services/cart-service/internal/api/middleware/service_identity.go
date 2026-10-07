package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"cart-service/internal/api/dto/response"
	"cart-service/internal/domain"
)

// HeaderServiceIdentity is the attribution header service callers send. The mesh authenticates the
// workload principal (docs/mesh/runtime-contract.md); Cart additionally checks this header against
// an allowlist as defence in depth, so a route reachable by a misconfigured policy still refuses an
// unknown caller.
const HeaderServiceIdentity = "X-Service-Identity"

// ServiceIdentity admits only the listed service identities. A missing header is 401, an identity
// outside the list is 403, matching Inventory and Order.
func ServiceIdentity(allowed []string) gin.HandlerFunc {
	permitted := make(map[string]struct{}, len(allowed))
	for _, name := range allowed {
		permitted[strings.TrimSpace(name)] = struct{}{}
	}
	return func(c *gin.Context) {
		caller := strings.TrimSpace(c.GetHeader(HeaderServiceIdentity))
		if caller == "" {
			AbortWithProblem(c, response.NewProblem(http.StatusUnauthorized, domain.CodeUnauthenticated,
				"missing service identity", c.Request.URL.Path, GetRequestID(c)))
			return
		}
		if _, ok := permitted[caller]; !ok {
			AbortWithProblem(c, response.NewProblem(http.StatusForbidden, domain.CodeForbidden,
				"this service identity may not read cart snapshots", c.Request.URL.Path, GetRequestID(c)))
			return
		}
		c.Next()
	}
}
