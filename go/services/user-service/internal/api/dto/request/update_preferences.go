package request

// UpdatePreferencesRequest represents the request body for updating preferences
type UpdatePreferencesRequest struct {
	Language          string `json:"language" binding:"omitempty,oneof=vi en zh ja ko"`
	Currency          string `json:"currency" binding:"omitempty,oneof=VND USD EUR JPY CNY"`
	EmailEnabled     *bool  `json:"email_enabled"`
	InAppEnabled     *bool  `json:"in_app_enabled"`
	MarketingEnabled *bool  `json:"marketing_enabled"`
}
