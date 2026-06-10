package middleware

import (
	"net/http"
	"runtime/debug"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"cart-service/internal/api/dto/response"
)

// Recovery recovers from panics and returns 500.
func Recovery(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if r := recover(); r != nil {
				logger.Error("panic recovered",
					zap.Any("error", r),
					zap.String("stack", string(debug.Stack())),
					zap.String("path", c.Request.URL.Path),
				)

				c.AbortWithStatusJSON(http.StatusInternalServerError,
					response.NewErrorResponse(
						response.ErrInternal,
						"Internal server error",
						GetRequestID(c),
					),
				)
			}
		}()
		c.Next()
	}
}
