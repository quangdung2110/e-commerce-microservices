package dto

type SessionResponse struct {
	SessionID  string `json:"session_id"`
	DeviceInfo string `json:"device_info"`
	IPAddress  string `json:"ip_address"`
	CreatedAt  string `json:"created_at"`
	IsCurrent  bool   `json:"is_current"`
}

type SessionsResponse struct {
	Sessions []SessionResponse `json:"sessions"`
}
