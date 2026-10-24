package gin

import (
	"time"

	obs "github.com/ecommerce/observability"
	"github.com/gin-gonic/gin"
)

// Middleware must precede correlation/recovery middleware so failures retain a server span and status.
func Middleware(metrics *obs.HTTPMetrics) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.URL.Path == "/metrics" {
			c.Next()
			return
		}
		started := time.Now()
		r, span := obs.StartHTTP(c.Request)
		c.Request = r
		defer func() {
			route := c.FullPath()
			metrics.Observe(c.Request.Method, route, c.Writer.Status(), time.Since(started))
			obs.FinishHTTP(span, c.Request.Method, route, c.Writer.Status())
		}()
		c.Next()
	}
}
