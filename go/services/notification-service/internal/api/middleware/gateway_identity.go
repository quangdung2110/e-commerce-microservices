package middleware

import (
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/common/constants"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
)

// identityContextKey is where the resolved identity lives inside the gin
// context.
const identityContextKey = "notification.identity"

// Identity is the caller as established by the API Gateway after JWT
// verification. The header is trustworthy only because the gateway strips a
// client-supplied copy, so the public listener must never be reachable from
// the edge directly (10-interfaces.md §5).
type Identity struct {
	UserID uuid.UUID
}

// GatewayIdentity requires a UUID X-User-Id; anything else is 401.
func GatewayIdentity() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID, err := uuid.Parse(strings.TrimSpace(c.GetHeader(constants.HeaderUserID)))
		if err != nil || userID == uuid.Nil {
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
