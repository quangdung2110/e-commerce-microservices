package dto

type VerifyEmailRequest struct {
	Email string `json:"email" binding:"required,email"`
	OTP   string `json:"otp" binding:"required,len=6"`
}

type VerifyEmailResponse struct {
	Verified bool `json:"verified"`
}
