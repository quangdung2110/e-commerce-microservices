package response

type LoginResponse struct {
	AccessToken string 		  `json:"access_token"`
	ExpiresIn   int64         `json:"expires_in"`
	TokenType   string        `json:"token_type"`
	User        UserResponse  `json:"user"`
}

type UserResponse struct {
	ID     string   `json:"id"`
	Email  string   `json:"email"`
	Roles  []string `json:"roles"`
	Status string   `json:"status"`
}
