package middleware

import (
	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/httpx"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// RateLimit applies one fixed-window rule keyed by the client IP.
//
// It fails closed: when Redis cannot answer, the request is rejected rather than
// let through unlimited, because these routes are the brute-force surface of the
// whole platform.
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

// RateLimitByServiceResource applies a rule to one (calling service, resource)
// pair on the internal listener, where the resource is the path parameter named
// by resourceParam: a delivery id or a user id. It must run after
// ServiceIdentity.
//
// A client IP is the wrong subject there: behind the mesh waypoint every pod of
// the caller arrives from the same few addresses, so a per-IP budget is one
// bucket shared by all of Notification and turns ordinary traffic into 429s.
// The resource id keeps one retry storm on a single delivery or user from
// spending the budget of the others; admission of the caller itself is the
// mesh's job. It fails closed when Redis cannot answer.
func RateLimitByServiceResource(limiter port.RateLimiter, scope, resourceParam string, rule port.RateLimitRule) gin.HandlerFunc {
	if !rule.Enabled() {
		return func(c *gin.Context) { c.Next() }
	}

	return func(c *gin.Context) {
		caller, ok := ServiceIdentityFrom(c)
		if !ok {
			httpx.WriteProblem(c, apperr.ErrUnauthorized)
			return
		}

		result, err := limiter.Allow(c.Request.Context(), scope, caller+":"+c.Param(resourceParam), rule)
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

// RateLimitByIdentity applies a rule keyed by the authenticated account, so one
// noisy account cannot spend the budget of a shared egress IP.
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

		result, err := limiter.Allow(c.Request.Context(), scope, identity.AccountID.String(), rule)
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
