package middleware

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
)

// ServiceSignature admits a request on the internal listener only when it
// carries X-Service-Signature: t=<unix seconds>,v1=<hex>, where v1 is
// HMAC-SHA256 under key of SignaturePayload(t, method, path, caller) and t is
// within tolerance of now (mesh ADR-004).
//
// The mesh, once installed, proves the caller with mTLS. Until then every
// container on the network reaches this port and X-Service-Identity is only a
// claim, while the responses carry OTPs and reset links; the signature is what
// a container without the key cannot produce. The time bound limits a captured
// request to a short replay window on the same delivery. ServiceIdentity must
// run first: the caller name is part of the signed payload. An empty key
// disables the check, which configuration accepts in development only.
func ServiceSignature(key []byte, tolerance time.Duration, now func() time.Time) gin.HandlerFunc {
	return func(c *gin.Context) {
		if len(key) == 0 {
			c.Next()
			return
		}
		caller, _ := ServiceIdentityFrom(c)
		timestamp, signature, ok := parseSignature(c.GetHeader(constants.HeaderServiceSignature))
		if !ok {
			httpx.WriteProblem(c, apperr.ErrUnauthorized.WithDetail("A valid service request signature is required."))
			return
		}
		age := now().Sub(time.Unix(timestamp, 0))
		if age > tolerance || age < -tolerance {
			httpx.WriteProblem(c, apperr.ErrUnauthorized.WithDetail("The service request signature has expired."))
			return
		}
		expected := SignRequest(key, timestamp, c.Request.Method, c.Request.URL.EscapedPath(), caller)
		if !hmac.Equal(signature, expected) {
			httpx.WriteProblem(c, apperr.ErrUnauthorized.WithDetail("A valid service request signature is required."))
			return
		}
		c.Next()
	}
}

// SignaturePayload is the string both ends sign. Notification builds the same
// one in internal/infrastructure/auth; the shared test vector keeps them equal.
func SignaturePayload(timestamp int64, method, path, caller string) string {
	return strconv.FormatInt(timestamp, 10) + "\n" + method + "\n" + path + "\n" + caller
}

// SignRequest returns the raw HMAC-SHA256 of SignaturePayload.
func SignRequest(key []byte, timestamp int64, method, path, caller string) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(SignaturePayload(timestamp, method, path, caller)))
	return mac.Sum(nil)
}

func parseSignature(header string) (int64, []byte, bool) {
	var timestamp int64
	var signature []byte
	for _, part := range strings.Split(header, ",") {
		name, value, found := strings.Cut(strings.TrimSpace(part), "=")
		if !found {
			return 0, nil, false
		}
		switch name {
		case "t":
			parsed, err := strconv.ParseInt(value, 10, 64)
			if err != nil {
				return 0, nil, false
			}
			timestamp = parsed
		case "v1":
			decoded, err := hex.DecodeString(value)
			if err != nil || len(decoded) != sha256.Size {
				return 0, nil, false
			}
			signature = decoded
		}
	}
	return timestamp, signature, timestamp != 0 && signature != nil
}
