// Package request holds the inbound payloads of the public HTTP API. Each type
// mirrors one schema of contracts/http/auth/v1/openapi.yaml; unknown fields are
// rejected by the decoder, matching additionalProperties:false.
package request

// Registration creates an account that still has to verify its email.
type Registration struct {
	Email           string `json:"email"`
	Password        string `json:"password"`
	ConfirmPassword string `json:"confirm_password"`
}

// Email is the payload of the endpoints that only take an address: resending a
// verification code and requesting a password reset. Both answer the same way
// whether or not the address has an account.
type Email struct {
	Email string `json:"email"`
}

// EmailVerification confirms a registration code.
type EmailVerification struct {
	Email string `json:"email"`
	OTP   string `json:"otp"`
}

// EmailChange starts an email change with the current password.
type EmailChange struct {
	CurrentPassword string `json:"current_password"`
	NewEmail        string `json:"new_email"`
}

// EmailChangeConfirmation completes an email change with the code sent to the
// new address.
type EmailChangeConfirmation struct {
	NewEmail string `json:"new_email"`
	OTP      string `json:"otp"`
}

// Login exchanges credentials for a token pair.
type Login struct {
	Email      string `json:"email"`
	Password   string `json:"password"`
	DeviceName string `json:"device_name,omitempty"`
}

// ChangePassword replaces the credential of the authenticated account.
type ChangePassword struct {
	CurrentPassword string `json:"current_password"`
	NewPassword     string `json:"new_password"`
	ConfirmPassword string `json:"confirm_password"`
}

// CompletePasswordReset redeems an opaque reset token.
type CompletePasswordReset struct {
	ResetToken      string `json:"reset_token"`
	NewPassword     string `json:"new_password"`
	ConfirmPassword string `json:"confirm_password"`
}

// CurrentPassword re-authenticates the owner before a destructive operation.
type CurrentPassword struct {
	CurrentPassword string `json:"current_password"`
}
