package middleware

import (
	"github.com/gin-gonic/gin"
)

// Tracing middleware for distributed tracing.
// OpenTelemetry instrumentation can be added here when the service
// is deployed with a tracing backend (Jaeger, Zipkin, etc.).
func Tracing() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()
	}
}
