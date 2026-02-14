package entity

type RefreshTokenData struct {
	SessionID   string  `json:"session_id"`
	TokenHash   string  `json:"token_hash"`
	IPAddress   string  `json:"ip_address"`
	RotatedFrom *string `json:"rotated_from,omitempty"`
	RotatedAt   *int64  `json:"rotated_at,omitempty"`
	CreatedAt   int64   `json:"created_at"`
}
