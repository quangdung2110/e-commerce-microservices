package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"time"
)

// signaturePayload is what Auth verifies for X-Service-Signature
// (go/services/auth-service/internal/api/middleware/service_signature.go,
// mesh ADR-004): the Unix time, the method, the escaped path and the caller
// identity, one per line. The shared test vector in both repositories keeps the
// two ends equal.
func signaturePayload(timestamp int64, method, path, caller string) string {
	return strconv.FormatInt(timestamp, 10) + "\n" + method + "\n" + path + "\n" + caller
}

// signatureHeader returns "t=<unix seconds>,v1=<hex HMAC-SHA256>". Auth accepts
// it within a short window around its own clock, so a captured header is
// useless soon after and on any other path.
func signatureHeader(key []byte, now time.Time, method, path, caller string) string {
	timestamp := now.Unix()
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(signaturePayload(timestamp, method, path, caller)))
	return "t=" + strconv.FormatInt(timestamp, 10) + ",v1=" + hex.EncodeToString(mac.Sum(nil))
}
