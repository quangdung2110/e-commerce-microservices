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

// RateLimit applies a rule keyed by the client IP. It guards the internal
// listener, whose caller is a service rather than an end user.
func RateLimit(limiter port.RateLimiter, scope string, rule port.RateLimitRule) gin.HandlerFunc {
	if !rule.Enabled() {
		return func(c *gin.Context) { c.Next() }
	}

	return func(c *gin.Context) {
		result, err := limiter.Allow(c.Request.Context(), scope, c.ClientIP(), rule)
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
