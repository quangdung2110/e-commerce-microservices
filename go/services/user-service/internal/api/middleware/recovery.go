package middleware

import (
	"net/http"
	"runtime/debug"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"user-service/internal/api/dto/response"
)

// RecoveryMiddleware creates a panic recovery middleware
func RecoveryMiddleware(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				logger.Error("panic recovered",
					zap.Any("error", err),
					zap.String("stack", string(debug.Stack())),
				)

				c.AbortWithStatusJSON(http.StatusInternalServerError, response.NewErrorResponse(
					response.ErrCodeInternalError,
					"An unexpected error occurred",
				))
			}
		}()

		c.Next()
	}
}
