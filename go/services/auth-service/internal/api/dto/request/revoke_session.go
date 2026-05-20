package request

type RevokeSessionRequest struct {
	Reason string `json:"reason,omitempty"`
}
