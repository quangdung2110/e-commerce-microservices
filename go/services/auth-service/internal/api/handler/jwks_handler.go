package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/domain/service"
)

type JWKSHandler struct {
	jwksService *service.JWKSService
	logger      *zap.Logger
}

func NewJWKSHandler(jwksService *service.JWKSService, logger *zap.Logger) *JWKSHandler {
	return &JWKSHandler{
		jwksService: jwksService,
		logger:      logger.With(zap.String("handler", "JWKSHandler")),
	}
}

// GetJWKS handles GET /.well-known/jwks.json
func (h *JWKSHandler) GetJWKS(c *gin.Context) {
	jwks, err := h.jwksService.GetJWKS(c.Request.Context())
	if err != nil {
		h.logger.Error("Failed to get JWKS", zap.Error(err))
		c.JSON(http.StatusInternalServerError, gin.H{
			"error": "Failed to get JWKS",
		})
		return
	}

	c.Header("Content-Type", "application/json")
	c.Header("Cache-Control", "public, max-age=300") // Cache for 5 minutes
	c.JSON(http.StatusOK, jwks)
}
