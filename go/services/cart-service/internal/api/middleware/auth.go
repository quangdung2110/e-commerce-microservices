package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"cart-service/internal/api/dto/response"
)

const UserIDHeader = "X-User-Id"
const UserIDKey = "user_id"

// Auth extracts user ID from header (injected by API Gateway).
func Auth() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID := c.GetHeader(UserIDHeader)
		if userID == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized,
				response.NewErrorResponse(
					response.ErrUnauthorized,
					"Missing X-User-Id header",
					GetRequestID(c),
				),
			)
			return
		}
		c.Set(UserIDKey, userID)
		c.Next()
	}
}

// GetUserID retrieves the user ID from context.
func GetUserID(c *gin.Context) string {
	if uid, exists := c.Get(UserIDKey); exists {
		return uid.(string)
	}
	return ""
}
