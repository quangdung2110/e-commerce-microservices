package middleware

import (
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/api/httpx"
	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
)

// identityContextKey is where the resolved identity lives inside the gin
// context.
const identityContextKey = "user.identity"

// Identity is the caller as established by the API Gateway.
//
// The header is only trustworthy because the gateway strips any client-supplied
// copy before forwarding it, so this service must never be reachable directly
// from the edge. Every `/me` route resolves its resources from this value and
// never from a user id in the path or the body (docs/user/domain.md §6).
type Identity struct {
	UserID uuid.UUID
}

// GatewayIdentity requires a verified identity from the gateway.
func GatewayIdentity() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID, err := uuid.Parse(strings.TrimSpace(c.GetHeader(constants.HeaderUserID)))
		if err != nil {
			httpx.WriteProblem(c, apperr.ErrUnauthorized)
			return
		}

		c.Set(identityContextKey, Identity{UserID: userID})
		c.Next()
	}
}

// IdentityFrom returns the identity established for this request.
func IdentityFrom(c *gin.Context) (Identity, bool) {
	value, exists := c.Get(identityContextKey)
	if !exists {
		return Identity{}, false
	}
	identity, ok := value.(Identity)
	return identity, ok
}
