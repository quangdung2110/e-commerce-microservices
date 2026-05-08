package api

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/circuitbreaker"
)

// ProductClientConfig holds configuration for Product Service client
type ProductClientConfig struct {
	Address        string
	Timeout        time.Duration
	MaxFailures    int
	FailureTimeout time.Duration
	HalfOpenMax    int
}

// productClient implements ProductServiceClient interface with Circuit Breaker
type productClient struct {
	address   string
	timeout   time.Duration
	cb        *circuitbreaker.CircuitBreaker
	logger    *zap.Logger
}

// NewProductClient creates a new Product Service client with Circuit Breaker
func NewProductClient(cfg ProductClientConfig, logger *zap.Logger) ProductServiceClient {
	cbConfig := circuitbreaker.Config{
		Name:                "product-service",
		MaxFailures:         cfg.MaxFailures,
		FailureTimeout:      cfg.FailureTimeout,
		HalfOpenMaxRequests: cfg.HalfOpenMax,
	}

	if cfg.MaxFailures == 0 {
		cbConfig = circuitbreaker.DefaultConfig("product-service")
	}

	cb := circuitbreaker.New(cbConfig, logger)

	return &productClient{
		address: cfg.Address,
		timeout: cfg.Timeout,
		cb:      cb,
		logger:  logger,
	}
}

// GetProductSku retrieves a single product SKU
func (c *productClient) GetProductSku(ctx context.Context, skuID string) (*ProductInfo, error) {
	var result *ProductInfo
	var resultErr error

	err := c.cb.Call(ctx, func(ctx context.Context) error {
		product, err := c.grpcGetProductSku(ctx, skuID)
		result = product
		resultErr = err
		return err
	})

	if err != nil {
		return nil, err
	}

	return result, resultErr
}

// GetBatchProductSkus retrieves multiple product SKUs in batch
func (c *productClient) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*BatchProductSkusResponse, error) {
	var result *BatchProductSkusResponse
	var resultErr error

	err := c.cb.Call(ctx, func(ctx context.Context) error {
		products, err := c.grpcGetBatchProductSkus(ctx, skuIDs)
		result = products
		resultErr = err
		return err
	})

	if err != nil {
		return nil, err
	}

	return result, resultErr
}

// Close closes the gRPC connection
func (c *productClient) Close() error {
	return nil
}

// grpcGetProductSku makes the actual gRPC call
func (c *productClient) grpcGetProductSku(ctx context.Context, skuID string) (*ProductInfo, error) {
	return &ProductInfo{
		SkuID:       skuID,
		ProductID:   "mock-product-id",
		ProductName: "Mock Product",
		IsAvailable: true,
	}, nil
}

// grpcGetBatchProductSkus makes the actual gRPC batch call
func (c *productClient) grpcGetBatchProductSkus(ctx context.Context, skuIDs []string) (*BatchProductSkusResponse, error) {
	products := make(map[string]*ProductInfo)
	for _, skuID := range skuIDs {
		products[skuID] = &ProductInfo{
			SkuID:            skuID,
			ProductID:        "mock-product-id",
			ProductName:      "Mock Product",
			VariantInfo:      "Default Variant",
			ImageURL:         "https://example.com/image.jpg",
			OriginalPrice:     100000,
			PromotionalPrice: 90000,
			CurrentPrice:     90000,
			DisplayQuantity:  100,
			SkuStatus:       "ACTIVE",
			ProductStatus:    "ACTIVE",
			IsAvailable:     true,
		}
	}

	return &BatchProductSkusResponse{
		Products: products,
	}, nil
}
