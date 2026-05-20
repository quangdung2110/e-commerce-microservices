package keybuilder

import "fmt"

const AuthPrefix = "auth:"

const (
	DenylistPattern         = "auth:denylist:%s"
	RevokedBeforePattern    = "auth:revoked_before:%s"
	GracePeriodPattern      = "auth:refresh_grace:%s"
	FailedLoginEmailPattern = "auth:failed_login:%s"
	RateLimitPattern        = "auth:rate_limit:%s:%s"
	OTPEmailPattern         = "auth:otp:register:%s"
	ResetTokenPattern       = "auth:reset_pw:%s"
	JWKSChannel             = "auth:jwks:update"
)

func Denylist(jti string) string {
	return fmt.Sprintf(DenylistPattern, jti)
}

func RevokedBefore(userID string) string {
	return fmt.Sprintf(RevokedBeforePattern, userID)
}

func GracePeriod(tokenHash string) string {
	return fmt.Sprintf(GracePeriodPattern, tokenHash)
}

func FailedLoginEmail(email string) string {
	return fmt.Sprintf(FailedLoginEmailPattern, email)
}

func RateLimit(action, identifier string) string {
	return fmt.Sprintf(RateLimitPattern, action, identifier)
}

func OTPEmail(email string) string {
	return fmt.Sprintf(OTPEmailPattern, email)
}

func ResetToken(tokenHash string) string {
	return fmt.Sprintf(ResetTokenPattern, tokenHash)
}

func JWKSUpdateChannel() string {
	return JWKSChannel
}
