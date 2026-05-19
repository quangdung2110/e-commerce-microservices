package response

type ForgotPasswordResponse struct {
	Message string `json:"message"`
}

type ResetPasswordResponse struct {
	Reset bool `json:"reset"`
}

type ChangePasswordResponse struct {
	Changed bool `json:"changed"`
}
