package request

type ResendOTPRequest struct {
	Email string `json:"email" binding:"required,email"`
}
