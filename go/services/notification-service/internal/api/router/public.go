// Package router maps the two OpenAPI contracts onto gin engines. Paths,
// methods and status codes follow contracts/http/notification/v1/openapi.yaml
// (public, port 8080) and contracts/http/notification/internal/v1/openapi.yaml
// (internal, port 8081) exactly.
package router

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/handler"
	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/api/middleware"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
)

// PublicDeps is everything the public listener needs.
type PublicDeps struct {
	Config *config.Config
	Logger *zap.Logger
	Inbox  *handler.InboxHandler
}

// NewPublic builds the public engine: the inbox of the caller identified by
// the gateway. There is no path parameter naming a user, so a client cannot
// address somebody else's inbox at all (10-interfaces.md §5).
func NewPublic(deps PublicDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger, deps.Config.Server.TrustedProxies)

	inbox := engine.Group("/api/v1/notifications/inbox", middleware.GatewayIdentity())
	inbox.GET("", middleware.Operation("listInbox"), deps.Inbox.List)
	inbox.GET("/unread-count", middleware.Operation("getUnreadCount"), deps.Inbox.UnreadCount)
	inbox.POST("/mark-all-read", middleware.Operation("markAllInboxRead"), deps.Inbox.MarkAllRead)
	inbox.PATCH("/:item_id", middleware.Operation("updateInboxItem"), deps.Inbox.Update)
	inbox.DELETE("/:item_id", middleware.Operation("archiveInboxItem"), deps.Inbox.Archive)

	return engine
}

// newEngine builds an engine with the shared middleware stack and problem
// bodies for the responses gin would otherwise render as plain text.
func newEngine(cfg *config.Config, logger *zap.Logger, trustedProxies []string) *gin.Engine {
	if cfg.App.IsProduction() {
		gin.SetMode(gin.ReleaseMode)
	} else if gin.Mode() == gin.DebugMode {
		gin.SetMode(gin.TestMode)
	}
	if logger == nil {
		logger = zap.NewNop()
	}

	engine := gin.New()
	engine.RedirectTrailingSlash = false
	engine.RedirectFixedPath = false
	// gin trusts every proxy by default; the public listener sits behind the
	// gateway only, the internal one behind the mesh.
	if err := engine.SetTrustedProxies(trustedProxies); err != nil {
		logger.Warn("could not apply the trusted proxy list", zap.Error(err))
	}

	engine.Use(middleware.RequestID(logger), middleware.Recovery(), middleware.AccessLog())

	engine.HandleMethodNotAllowed = true
	engine.NoRoute(func(c *gin.Context) {
		httpx.WriteProblem(c, apperr.ErrNotFound)
	})
	engine.NoMethod(func(c *gin.Context) {
		httpx.WriteProblem(c, apperr.New(apperr.CodeMethodNotAllowed, "method not allowed"))
	})
	return engine
}
