package v1_router

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	v1_handler "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/handler"
)

// RegisterAuditRoutes registers audit-related routes
func RegisterAuditRoutes(
	router *gin.RouterGroup,
	auditHandler *v1_handler.AuditHandler,
	logger *zap.Logger,
) {
	auditGroup := router.Group("/audit")

	// User audit routes (requires authentication via X-User-Id header)
	{
		// Get my login history - user can view their own login history
		auditGroup.GET("/my-login-history", auditHandler.GetMyLoginHistory)
	}

	// Admin audit routes (requires ADMIN role)
	adminGroup := auditGroup.Group("/admin")
	{
		// Get admin history logs with advanced filtering
		adminGroup.GET("/history-logs", auditHandler.GetAdminHistoryLogs)

		// Get critical security events
		adminGroup.GET("/critical-events", auditHandler.GetCriticalSecurityEvents)
	}
}
