package port

import (
	"context"

	"cart-service/internal/domain/entity"
)

// ProductClient defines the interface for fetching product data from Product Service.
// Implemented by internal/infrastructure/grpc/product_client.go
type ProductClient interface {
	// GetProductSku fetches a single SKU's data.
	// Used in AddItem workflow to validate SKU and get display info.
	// Returns ErrProductNotFound if SKU doesn't exist or is inactive.
	GetProductSku(ctx context.Context, skuID string) (*entity.SkuInfo, error)

	// GetBatchProductSkus fetches multiple SKUs in a single gRPC call.
	// Used in GetCart (both cache-hit and cache-miss paths).
	// Returns map of skuID -> SkuInfo. Missing SKUs are omitted from map.
	// Context timeout: 200ms hard limit.
	GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error)

	// Close closes the gRPC connection.
	Close() error

	// IsHealthy checks if the product service is reachable.
	IsHealthy(ctx context.Context) bool
}

// Product unavailability reasons (for logging/debugging)
const (
	ProductStatusActive   = "ACTIVE"
	ProductStatusBanned   = "BANNED"
	ProductStatusDeleted  = "DELETED"
	ProductStatusInactive = "INACTIVE"
)
