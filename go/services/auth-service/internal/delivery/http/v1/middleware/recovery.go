package v1_middleware

import (
	"github.com/gin-gonic/gin"
	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"go.uber.org/zap"
)

// RecoveryMiddleware handles panics
func RecoveryMiddleware(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				logger.Error("Panic recovered",
					zap.Any("error", err),
					zap.String("path", c.Request.URL.Path),
					zap.String("method", c.Request.Method),
				)

				v1.ErrorResponse(c, serr.ErrInternal)
				c.Abort()
			}
		}()

		c.Next()
	}
}
