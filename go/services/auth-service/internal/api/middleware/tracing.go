package middleware

import (
	"github.com/gin-gonic/gin"
)

func Tracing() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = generateTraceID()
		}
		c.Set("trace_id", traceID)
		c.Header("X-Trace-ID", traceID)
		c.Next()
	}
}

func generateTraceID() string {
	return "placeholder-trace-id"
}
