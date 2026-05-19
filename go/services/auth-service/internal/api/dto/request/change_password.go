package dto

type ChangePasswordRequest struct {
	CurrentPassword string `json:"current_password" binding:"required"`
	NewPassword     string `json:"new_password" binding:"required,min=8"`
}

type ChangePasswordResponse struct {
	Changed bool `json:"changed"`
}
