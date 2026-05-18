package service

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce-platform/go/services/cart-service/internal/common/circuitbreaker"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/entity"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/port"
)

// ProductServiceClientWithCB wraps ProductServiceClient with Circuit Breaker protection
type ProductServiceClientWithCB struct {
	client       port.ProductServiceClient
	circuitBreaker *circuitbreaker.CircuitBreaker
	logger       *zap.Logger
}

// NewProductServiceClientWithCB creates a new ProductServiceClient with Circuit Breaker
func NewProductServiceClientWithCB(
	client port.ProductServiceClient,
	cbConfig circuitbreaker.Config,
	logger *zap.Logger,
) *ProductServiceClientWithCB {
	return &ProductServiceClientWithCB{
		client:        client,
		circuitBreaker: circuitbreaker.New(cbConfig, logger),
		logger:        logger,
	}
}

// GetProductSku retrieves a single product SKU with Circuit Breaker protection
func (p *ProductServiceClientWithCB) GetProductSku(ctx context.Context, skuID string) (*entity.ProductInfo, error) {
	var result *entity.ProductInfo
	var cbErr error

	err := p.circuitBreaker.Call(ctx, func(ctx context.Context) error {
		var err error
		result, err = p.client.GetProductSku(ctx, skuID)
		return err
	})

	if err != nil {
		p.logger.Warn("Product Service call failed, circuit breaker open",
			zap.String("sku_id", skuID),
			zap.Error(err),
		)
		cbErr = err
	}

	return result, cbErr
}

// GetBatchProductSkus retrieves multiple product SKUs with Circuit Breaker protection
func (p *ProductServiceClientWithCB) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.BatchProductSkusResponse, error) {
	var result *entity.BatchProductSkusResponse
	var cbErr error

	err := p.circuitBreaker.Call(ctx, func(ctx context.Context) error {
		var err error
		result, err = p.client.GetBatchProductSkus(ctx, skuIDs)
		return err
	})

	if err != nil {
		p.logger.Warn("Batch Product Service call failed, circuit breaker open",
			zap.Int("sku_count", len(skuIDs)),
			zap.Error(err),
		)
		cbErr = err
	}

	return result, cbErr
}

// Close closes the underlying client
func (p *ProductServiceClientWithCB) Close() error {
	return p.client.Close()
}

// CircuitBreakerState returns the current circuit breaker state
func (p *ProductServiceClientWithCB) CircuitBreakerState() circuitbreaker.State {
	return p.circuitBreaker.State()
}

// CircuitBreakerConfig returns a default circuit breaker config
func CircuitBreakerConfig(name string, timeoutMs int) circuitbreaker.Config {
	timeout := 30 * time.Second
	if timeoutMs > 0 {
		timeout = time.Duration(timeoutMs) * time.Millisecond
	}

	return circuitbreaker.Config{
		Name:                  name,
		MaxFailures:          5,
		FailureTimeout:       timeout,
		HalfOpenMaxRequests:  3,
	}
}
