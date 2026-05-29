package response

// BatchDeleteResponse represents the response for DELETE /api/v1/carts/items
type BatchDeleteResponse struct {
	Success      bool   `json:"success"`
	DeletedCount int    `json:"deleted_count"`
	Message      string `json:"message,omitempty"`
}
