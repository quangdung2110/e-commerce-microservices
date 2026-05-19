package response

type RegisterResponse struct {
	Message             string `json:"message"`
	UserID              string `json:"user_id"`
	Email               string `json:"email"`
	Status              string `json:"status"`
	RequireVerification bool   `json:"require_verification"`
}
