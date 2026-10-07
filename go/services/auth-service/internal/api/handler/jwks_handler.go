package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// JwksHandler serves the public key set.
type JwksHandler struct {
	jwks         *service.JwksService
	cacheControl string
}

// NewJwksHandler wires the JWKS endpoint. cacheControl is what gateway caches
// see; a gateway must still refresh when it meets an unknown kid.
func NewJwksHandler(jwks *service.JwksService, cacheControl string) *JwksHandler {
	return &JwksHandler{jwks: jwks, cacheControl: cacheControl}
}

// Keys handles GET /.well-known/jwks.json.
func (h *JwksHandler) Keys(c *gin.Context) {
	keys, err := h.jwks.Keys(c.Request.Context())
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	if h.cacheControl != "" {
		c.Header(constants.HeaderCacheControl, h.cacheControl)
	}
	c.JSON(http.StatusOK, response.NewJWKS(keys))
}
