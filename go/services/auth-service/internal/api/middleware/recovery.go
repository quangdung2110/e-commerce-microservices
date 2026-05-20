package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
					"code":    "INTERNAL_ERROR",
					"message": "An internal error occurred",
				})
			}
		}()
		c.Next()
	}
}
