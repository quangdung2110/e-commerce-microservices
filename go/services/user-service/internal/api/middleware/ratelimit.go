package middleware

import (
	"github.com/gin-gonic/gin"

	"github.com/ecommerce/user-service/internal/api/httpx"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// RateLimitByIdentity applies one fixed-window rule keyed by the authenticated
// caller, which is the right subject here: every route of this API belongs to
// one owner, and a shared egress IP would otherwise let one noisy client spend
// everybody's budget.
//
// It fails closed: when Redis cannot answer, the request is rejected rather
// than let through unlimited.
func RateLimitByIdentity(limiter port.RateLimiter, scope string, rule port.RateLimitRule) gin.HandlerFunc {
	if !rule.Enabled() {
		return func(c *gin.Context) { c.Next() }
	}

	return func(c *gin.Context) {
		identity, ok := IdentityFrom(c)
		if !ok {
			httpx.WriteProblem(c, apperr.ErrUnauthorized)
			return
		}

		result, err := limiter.Allow(c.Request.Context(), scope, identity.UserID.String(), rule)
		if err != nil {
			httpx.WriteProblem(c, err)
			return
		}
		if !result.Allowed {
			httpx.WriteProblem(c, apperr.ErrRateLimited.WithRetryAfter(result.RetryAfter))
			return
		}
		c.Next()
	}
}

// RateLimitByServiceUser applies a rule to one (calling service, user) pair on
// the internal listener. It must run after ServiceIdentity.
//
// A client IP is the wrong subject there: behind the mesh waypoint every pod of
// the caller arrives from the same few addresses, so a per-IP budget is one
// bucket shared by all of Checkout and turns ordinary traffic into 429s. The
// user id taken from the path keeps one noisy user from spending the budget of
// the others, which is the abuse this limiter exists for; admission of the
// caller itself is the mesh's job.
func RateLimitByServiceUser(limiter port.RateLimiter, scope, userParam string, rule port.RateLimitRule) gin.HandlerFunc {
	if !rule.Enabled() {
		return func(c *gin.Context) { c.Next() }
	}

	return func(c *gin.Context) {
		caller, ok := ServiceIdentityFrom(c)
		if !ok {
			httpx.WriteProblem(c, apperr.ErrUnauthorized)
			return
		}

		subject := caller + ":" + c.Param(userParam)
		result, err := limiter.Allow(c.Request.Context(), scope, subject, rule)
		if err != nil {
			httpx.WriteProblem(c, err)
			return
		}
		if !result.Allowed {
			httpx.WriteProblem(c, apperr.ErrRateLimited.WithRetryAfter(result.RetryAfter))
			return
		}
		c.Next()
	}
}
