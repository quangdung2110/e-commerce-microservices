// Package router maps the OpenAPI contract onto gin routes. Paths, methods and
// status codes follow contracts/http/auth/v1/openapi.yaml exactly.
package router

import (
	obs "github.com/ecommerce/observability"
	obsgin "github.com/ecommerce/observability/gin"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/api/middleware"
	"github.com/ecommerce/auth-service/internal/common/config"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var httpMetrics = obs.NewHTTPMetrics()

// PublicDeps is everything the public listener needs.
type PublicDeps struct {
	Config       *config.Config
	Logger       *zap.Logger
	Limiter      port.RateLimiter
	Registration *handler.RegistrationHandler
	Session      *handler.SessionHandler
	Password     *handler.PasswordHandler
	Account      *handler.AccountHandler
	Jwks         *handler.JwksHandler
	Health       *handler.HealthHandler
}

// NewPublic builds the public HTTP engine.
func NewPublic(deps PublicDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger)

	limits := deps.Config.Security.RateLimit
	anonymousOtp := rule(limits.RegistrationPerIP)

	engine.GET("/health/live", deps.Health.Live)
	engine.GET("/health/ready", deps.Health.Ready)
	engine.GET("/health/startup", deps.Health.Startup)
	engine.GET("/.well-known/jwks.json", deps.Jwks.Keys)

	api := engine.Group("/api/v1/auth")

	// Anonymous routes carry the brute-force surface, so each one has its own
	// per-IP budget.
	api.POST("/registrations",
		middleware.RateLimit(deps.Limiter, "registration:ip", rule(limits.RegistrationPerIP)),
		deps.Registration.Register)

	api.POST("/email-verification-requests",
		middleware.RateLimit(deps.Limiter, "verification-request:ip", anonymousOtp),
		deps.Registration.RequestVerification)

	api.POST("/email-verifications",
		middleware.RateLimit(deps.Limiter, "verification:ip", anonymousOtp),
		deps.Registration.VerifyEmail)

	api.POST("/sessions",
		middleware.RateLimit(deps.Limiter, "login:ip", rule(limits.LoginPerIP)),
		deps.Session.Login)

	api.POST("/sessions/refresh",
		middleware.RateLimit(deps.Limiter, "refresh:ip", rule(limits.RefreshPerIP)),
		deps.Session.Refresh)

	api.POST("/password-reset-requests",
		middleware.RateLimit(deps.Limiter, "password-reset-request:ip", rule(limits.PasswordResetPerIP)),
		deps.Password.RequestReset)

	api.POST("/password-resets",
		middleware.RateLimit(deps.Limiter, "password-reset:ip", rule(limits.PasswordResetPerIP)),
		deps.Password.CompleteReset)

	// Authenticated routes rely on the identity the gateway verified.
	authenticated := api.Group("", middleware.GatewayIdentity())

	authenticated.GET("/sessions", deps.Session.ListSessions)
	authenticated.DELETE("/sessions", deps.Session.RevokeAllSessions)
	authenticated.DELETE("/sessions/:session_id", deps.Session.RevokeSession)
	authenticated.PUT("/password", deps.Password.ChangePassword)

	authenticated.POST("/email-change-requests",
		middleware.RateLimitByIdentity(deps.Limiter, "email-change:account", rule(limits.OtpPerAccount)),
		deps.Account.RequestEmailChange)

	authenticated.POST("/email-changes/confirmations",
		middleware.RateLimitByIdentity(deps.Limiter, "email-change-confirm:account", rule(limits.OtpPerAccount)),
		deps.Account.ConfirmEmailChange)

	authenticated.POST("/accounts/me/deletion-requests", deps.Account.RequestOwnDeletion)

	authenticated.POST("/admin/accounts/:account_id/deletion-requests",
		middleware.RequireAdmin(),
		deps.Account.RequestAdminDeletion)

	return engine
}

// newEngine builds an engine with the shared middleware stack and the problem
// responses gin would otherwise render as plain text.
func newEngine(cfg *config.Config, logger *zap.Logger) *gin.Engine {
	if cfg.App.IsProduction() {
		gin.SetMode(gin.ReleaseMode)
	}

	engine := gin.New()
	engine.RedirectTrailingSlash = false
	// gin trusts every proxy by default, which would let a client forge its own
	// X-Forwarded-For and defeat per-IP rate limiting.
	if err := engine.SetTrustedProxies(cfg.Security.TrustedProxies); err != nil {
		logger.Warn("could not apply the trusted proxy list", zap.Error(err))
	}

	engine.Use(obsgin.Middleware(httpMetrics), middleware.Correlation(logger), middleware.Recovery(), middleware.AccessLog())

	engine.GET("/metrics", gin.WrapH(httpMetrics.Handler()))
	engine.HandleMethodNotAllowed = true
	engine.NoRoute(func(c *gin.Context) {
		httpx.WriteProblem(c, apperr.ErrNotFound)
	})
	engine.NoMethod(func(c *gin.Context) {
		httpx.WriteProblem(c, apperr.ErrMethodNotAllowed)
	})

	return engine
}

// rule converts a configured limit into the domain type.
func rule(r config.RateLimitRule) port.RateLimitRule {
	return port.RateLimitRule{Limit: r.Limit, Window: r.Window}
}
