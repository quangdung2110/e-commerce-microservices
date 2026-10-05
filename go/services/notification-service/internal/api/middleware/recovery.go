package middleware

import (
	"fmt"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/httpx"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
)

// Recovery turns a panic into a 500 problem instead of a dropped connection.
// Only the panic value and the stack are logged; the request body is never
// printed (it may carry template ids, subjects or ops reasons).
func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if recovered := recover(); recovered != nil {
				logging.FromContext(c.Request.Context()).Error("panic while handling a request",
					zap.String("panic", fmt.Sprint(recovered)),
					zap.StackSkip("stack", 3),
				)
				if c.Writer.Written() {
					c.Abort()
					return
				}
				httpx.WriteProblem(c, apperr.New(apperr.CodeInternal, "unexpected error"))
			}
		}()
		c.Next()
	}
}
