package v1_handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/jwks"
)

// JwksHandler handles JWKS-related HTTP requests
type JwksHandler struct {
	jwksService *jwks.Service
	logger      *zap.Logger
}

// NewJwksHandler creates new JWKS handler
func NewJwksHandler(
	jwksService *jwks.Service,
	logger *zap.Logger,
) *JwksHandler {
	return &JwksHandler{
		jwksService: jwksService,
		logger:      logger,
	}
}

// GetJwks returns the JSON Web Key Set (JWKS)
// @Summary Get JWKS
// @Description Returns the public keys for token verification
// @Tags jwks
// @Produce json
// @Success 200 {object} entity.Jwks
// @Failure 500 {object} v1.ErrorResponse
// @Router /.well-known/jwks.json [get]
func (h *JwksHandler) GetJwks(c *gin.Context) {
	jwks, err := h.jwksService.GetJWKS(c.Request.Context())
	if err != nil {
		h.logger.Error("Failed to get JWKS", zap.Error(err))
		v1.SendErrorResponse(c, http.StatusInternalServerError, "JWKS_UNAVAILABLE", "Failed to retrieve JWKS", err)
		return
	}

	// Return JWKS in standard RFC 7517 format
	c.JSON(http.StatusOK, jwks)
}

// GetPublicKeys returns all active public keys for token verification
// @Summary Get public keys
// @Description Returns all active public keys for token verification
// @Tags jwks
// @Produce json
// @Success 200 {object} array
// @Failure 500 {object} v1.ErrorResponse
// @Router /api/v1/auth/keys [get]
func (h *JwksHandler) GetPublicKeys(c *gin.Context) {
	keys, err := h.jwksService.GetPublicKeys(c.Request.Context())
	if err != nil {
		h.logger.Error("Failed to get public keys", zap.Error(err))
		v1.SendErrorResponse(c, http.StatusInternalServerError, "KEYS_UNAVAILABLE", "Failed to retrieve public keys", err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, keys)
}
