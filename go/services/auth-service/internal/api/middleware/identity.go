package middleware

import (
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// identityContextKey is where the resolved identity lives inside the gin context.
const identityContextKey = "auth.identity"

// headerUserSessionID is the session id gateway-auth forwards from the sid claim
// when it is configured to do so. It is optional: when it is absent, Auth falls
// back to the refresh cookie to recognise the caller's current session.
const headerUserSessionID = "X-User-Session-Id"

// Identity is the caller as established by the API Gateway. These headers are
// only trustworthy because the gateway strips any client-supplied copy before
// forwarding, so the service must never be reachable directly from the edge.
type Identity struct {
	AccountID uuid.UUID
	Roles     []vo.Role
	TokenJti  string
	SessionID *uuid.UUID
}

// HasRole reports whether the gateway forwarded the role.
func (i Identity) HasRole(role vo.Role) bool { return vo.HasRole(i.Roles, role) }

// GatewayIdentity requires a verified identity from the gateway.
func GatewayIdentity() gin.HandlerFunc {
	return func(c *gin.Context) {
		accountID, err := uuid.Parse(strings.TrimSpace(c.GetHeader(constants.HeaderUserID)))
		if err != nil {
			httpx.WriteProblem(c, apperr.ErrUnauthorized)
			return
		}

		identity := Identity{
			AccountID: accountID,
			Roles:     parseRoles(c.GetHeader(constants.HeaderUserRoles)),
			TokenJti:  strings.TrimSpace(c.GetHeader(constants.HeaderTokenJti)),
		}
		if raw := strings.TrimSpace(c.GetHeader(headerUserSessionID)); raw != "" {
			if sessionID, err := uuid.Parse(raw); err == nil {
				identity.SessionID = &sessionID
			}
		}

		c.Set(identityContextKey, identity)
		c.Next()
	}
}

// RequireAdmin rejects a caller the gateway did not mark as an administrator.
func RequireAdmin() gin.HandlerFunc {
	return func(c *gin.Context) {
		identity, ok := IdentityFrom(c)
		if !ok {
			httpx.WriteProblem(c, apperr.ErrUnauthorized)
			return
		}
		if !identity.HasRole(vo.RoleAdmin) {
			httpx.WriteProblem(c, apperr.ErrForbidden)
			return
		}
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

// parseRoles reads the comma-separated role list, dropping anything that is not
// part of the role enum.
func parseRoles(header string) []vo.Role {
	if header == "" {
		return nil
	}

	parts := strings.Split(header, ",")
	roles := make([]vo.Role, 0, len(parts))
	for _, part := range parts {
		role, err := vo.ParseRole(strings.ToUpper(strings.TrimSpace(part)))
		if err != nil {
			continue
		}
		roles = append(roles, role)
	}
	return roles
}
