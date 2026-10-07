package middleware

import (
	"github.com/gin-gonic/gin"

	"github.com/ecommerce/user-service/internal/api/httpx"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
)

// ServiceIdentity guards the internal checkout API. Its response carries a
// recipient name, a phone number and a street address, so only a peer whose
// client certificate the configured CA signed may pass, and optionally only one
// specific common name.
//
// TLS termination itself is done by the server: this middleware checks the
// verified peer chain the handshake produced. It never trusts a header.
func ServiceIdentity(allowedCommonName string) gin.HandlerFunc {
	return func(c *gin.Context) {
		state := c.Request.TLS
		if state == nil || len(state.VerifiedChains) == 0 {
			httpx.WriteProblem(c, apperr.ErrUnauthorized.WithDetail("A verified service certificate is required."))
			return
		}
		if allowedCommonName == "" {
			c.Next()
			return
		}

		for _, chain := range state.VerifiedChains {
			if len(chain) == 0 {
				continue
			}
			if chain[0].Subject.CommonName == allowedCommonName {
				c.Next()
				return
			}
		}
		httpx.WriteProblem(c, apperr.ErrForbidden.WithDetail("This service identity may not resolve address snapshots."))
	}
}
