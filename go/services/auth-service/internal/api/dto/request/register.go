package dto

type RegisterRequest struct {
	Email    string `json:"email" binding:"required,email"`
	Password string `json:"password" binding:"required,min=8"`
}

type RegisterResponse struct {
	UserID      string `json:"user_id"`
	Email       string `json:"email"`
	RequireVerification bool `json:"require_verification"`
}
