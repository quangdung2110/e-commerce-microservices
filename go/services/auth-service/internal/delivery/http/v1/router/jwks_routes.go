package v1_router

import (
	"github.com/gin-gonic/gin"

	v1_handler "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/handler"
)

// RegisterJwksRoutes registers JWKS-related routes
func RegisterJwksRoutes(
	router *gin.Engine,
	jwksHandler *v1_handler.JwksHandler,
) {
	// Public JWKS endpoint - RFC 7517 standard location
	router.GET("/.well-known/jwks.json", jwksHandler.GetJwks)

	// API v1 public keys endpoint (alternative)
	// Note: This is optional, mainly for clients that need explicit endpoints
}

// RegisterJwksAPIRoutes registers JWKS-related routes under API group
func RegisterJwksAPIRoutes(
	router *gin.RouterGroup,
	jwksHandler *v1_handler.JwksHandler,
) {
	// Public keys endpoint - available without authentication
	router.GET("/keys", jwksHandler.GetPublicKeys)
}
