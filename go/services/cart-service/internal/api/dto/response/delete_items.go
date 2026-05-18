package response

import "github.com/ecommerce-platform/go/services/cart-service/internal/domain/service"

// DeleteItemsResponse represents the response after deleting items
type DeleteItemsResponse struct {
	Success      bool  `json:"success"`
	DeletedCount int64 `json:"deleted_count"`
	Error       string `json:"error,omitempty"`
}

// NewDeleteItemsResponse creates a new DeleteItemsResponse
func NewDeleteItemsResponse(result *service.DeleteItemsResult) *DeleteItemsResponse {
	resp := &DeleteItemsResponse{
		Success:      result.Success,
		DeletedCount: result.DeletedCount,
	}

	if result.Error != nil {
		resp.Error = result.Error.Error()
	}

	return resp
}
