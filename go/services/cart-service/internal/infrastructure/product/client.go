// Package product is the Pull Model client for Product Service (ADR-005).
//
// Cart asks Product for catalog data at the two moments a user actually needs
// it: validating a SKU on a write, and enriching a cart on a read. It never
// subscribes to Product changes and never builds a reverse index from SKU to
// carts, because a single price change on a popular SKU would then become a
// write storm across every cart holding it.
package product

import (
	"context"
	"fmt"
	"time"

	"golang.org/x/sync/singleflight"
	"google.golang.org/grpc"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	productv1 "cart-service/internal/infrastructure/product/pb/productv1"
)

// Config configures the Product gRPC client.
type Config struct {
	Address     string
	DialTimeout time.Duration
	// GetSkuTimeout bounds a single-SKU write validation.
	GetSkuTimeout time.Duration
	// GetBatchSkusTimeout bounds a cart read enrichment. A hard deadline here
	// is mandatory: the cart page must not wait on Product.
	GetBatchSkusTimeout time.Duration
	// CacheSize and CacheTTL bound the per-pod validation cache.
	CacheSize int
	CacheTTL  time.Duration
	Breaker   BreakerConfig
}

// Client is the Product Service adapter.
type Client struct {
	conn    *grpc.ClientConn
	rpc     productv1.ProductServiceClient
	cfg     Config
	breaker *breaker
	cache   *snapshotCache
	// group collapses concurrent refreshes of the same SKU into one RPC, so a
	// thundering herd on a hot SKU costs one call rather than one per request.
	group singleflight.Group
	// Observer receives cache and breaker outcomes. It may be nil.
	Observer Observer
}

// Observer reports client-level events for metrics. Every method must tolerate
// being called concurrently.
type Observer interface {
	ObserveValidationCache(hit bool)
	ObserveProductCall(rpc string, err error)
	ObserveBreakerRejection(rpc string)
}

// NewClient dials Product Service. The connection is lazy, so a Product
// outage at startup does not prevent Cart from booting and serving reads.
func NewClient(ctx context.Context, cfg Config) (*Client, error) {
	dialCtx, cancel := context.WithTimeout(ctx, cfg.DialTimeout)
	defer cancel()

	conn, err := grpc.DialContext(dialCtx, cfg.Address,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, fmt.Errorf("dial product service at %s: %w", cfg.Address, err)
	}

	return &Client{
		conn:    conn,
		rpc:     productv1.NewProductServiceClient(conn),
		cfg:     cfg,
		breaker: newBreaker(cfg.Breaker),
		cache:   newSnapshotCache(cfg.CacheSize, cfg.CacheTTL),
	}, nil
}

var _ port.ProductCatalog = (*Client)(nil)

// Close releases the gRPC connection.
func (c *Client) Close() error {
	return c.conn.Close()
}

// GetSku validates one SKU for a write.
//
// A failure here rejects the write. Guessing that an unverified SKU is on sale
// is worse than returning an error (ADR-005).
func (c *Client) GetSku(ctx context.Context, skuID string) (domain.ProductSnapshot, error) {
	if snapshot, ok := c.cache.get(skuID); ok {
		c.observeCache(true)
		return snapshot, nil
	}
	c.observeCache(false)

	if !c.breaker.allow() {
		c.observeBreaker("GetSku")
		return domain.ProductSnapshot{}, unavailable(ErrBreakerOpen)
	}

	result, err, _ := c.group.Do(skuID, func() (any, error) {
		callCtx, cancel := context.WithTimeout(ctx, c.cfg.GetSkuTimeout)
		defer cancel()

		response, err := c.rpc.GetSku(callCtx, &productv1.GetSkuRequest{SkuId: skuID})
		c.observeCall("GetSku", err)
		if err != nil {
			c.breaker.failure()
			return nil, unavailable(err)
		}
		c.breaker.success()

		snapshot, err := snapshotFromGetSku(skuID, response)
		if err != nil {
			return nil, err
		}
		c.cache.put(skuID, snapshot)
		return snapshot, nil
	})
	if err != nil {
		return domain.ProductSnapshot{}, err
	}
	return result.(domain.ProductSnapshot), nil
}

// GetBatchSkus enriches a cart read with live prices and availability.
//
// The caller degrades rather than fails on error, so this method reports the
// failure and leaves the policy decision upstream.
func (c *Client) GetBatchSkus(ctx context.Context, skuIDs []string) (map[string]domain.ProductSnapshot, error) {
	if len(skuIDs) == 0 {
		return map[string]domain.ProductSnapshot{}, nil
	}
	if len(skuIDs) > domain.MaxBatchSkuLookup {
		return nil, fmt.Errorf("batch lookup of %d skus exceeds the provider cap of %d", len(skuIDs), domain.MaxBatchSkuLookup)
	}

	if !c.breaker.allow() {
		c.observeBreaker("GetBatchSkus")
		return nil, unavailable(ErrBreakerOpen)
	}

	callCtx, cancel := context.WithTimeout(ctx, c.cfg.GetBatchSkusTimeout)
	defer cancel()

	response, err := c.rpc.GetBatchSkus(callCtx, &productv1.GetBatchSkusRequest{SkuIds: skuIDs})
	c.observeCall("GetBatchSkus", err)
	if err != nil {
		c.breaker.failure()
		return nil, unavailable(err)
	}
	c.breaker.success()

	snapshots := make(map[string]domain.ProductSnapshot, len(response.GetSkus()))
	for skuID, sku := range response.GetSkus() {
		snapshots[skuID] = snapshotFromSku(skuID, sku)
	}
	// A SKU Product reports as missing or unavailable still needs an entry, so
	// the read renders it as unavailable rather than silently as degraded.
	for _, skuID := range response.GetNotFoundSkuIds() {
		snapshots[skuID] = domain.ProductSnapshot{SkuID: skuID, IsAvailable: false}
	}
	for _, skuID := range response.GetUnavailableSkuIds() {
		snapshots[skuID] = domain.ProductSnapshot{SkuID: skuID, IsAvailable: false}
	}
	return snapshots, nil
}

// Ready reports whether the client is usable. It does not issue an RPC: a
// readiness probe must not add load to a struggling dependency.
func (c *Client) Ready(ctx context.Context) error {
	if c.breaker.isOpen() {
		return unavailable(ErrBreakerOpen)
	}
	switch c.conn.GetState() {
	case connectivity.Shutdown:
		return unavailable(fmt.Errorf("product connection is shut down"))
	case connectivity.TransientFailure:
		return unavailable(fmt.Errorf("product connection is in transient failure"))
	default:
		return nil
	}
}

// snapshotFromGetSku maps the oneof result of GetSku onto a snapshot.
//
// not_found and unavailable are answers, not transport errors: they mean the
// SKU may not be added to a cart.
func snapshotFromGetSku(skuID string, response *productv1.GetSkuResponse) (domain.ProductSnapshot, error) {
	switch result := response.GetResult().(type) {
	case *productv1.GetSkuResponse_Sku:
		snapshot := snapshotFromSku(skuID, result.Sku)
		if !snapshot.IsAvailable {
			return domain.ProductSnapshot{}, domain.NewError(domain.CodeProductNotAvailable,
				"product service reports the sku is not on sale")
		}
		return snapshot, nil
	case *productv1.GetSkuResponse_NotFound:
		return domain.ProductSnapshot{}, domain.NewError(domain.CodeProductNotAvailable,
			"product service does not know this sku")
	case *productv1.GetSkuResponse_Unavailable:
		return domain.ProductSnapshot{}, domain.NewError(domain.CodeProductNotAvailable,
			"product service reports the sku is unavailable: "+result.Unavailable.GetReason().String())
	default:
		return domain.ProductSnapshot{}, fmt.Errorf("product service returned an empty result for sku %s", skuID)
	}
}

func snapshotFromSku(skuID string, sku *productv1.Sku) domain.ProductSnapshot {
	if sku == nil {
		return domain.ProductSnapshot{SkuID: skuID, IsAvailable: false}
	}

	snapshot := domain.ProductSnapshot{
		SkuID:             skuID,
		ProductID:         sku.GetProductId(),
		ProductName:       sku.GetProductName(),
		VariantAttributes: sku.GetVariantAttributes(),
		ImageKey:          sku.GetImageUrl(),
		IsAvailable:       sku.GetStatus() == productv1.SkuStatus_SKU_STATUS_ACTIVE,
	}
	if price := sku.GetPrice(); price != nil {
		snapshot.Price = domain.Money{
			AmountMinor: price.GetAmountMinor(),
			Currency:    price.GetCurrency(),
		}
	}
	return snapshot
}

// unavailable wraps a transport-level failure as a retryable domain error.
func unavailable(err error) error {
	return fmt.Errorf("%w (%v)", &domain.Error{
		Code:      domain.CodeProductUnavailable,
		Detail:    "product service is unavailable",
		Retryable: true,
	}, err)
}

func (c *Client) observeCache(hit bool) {
	if c.Observer != nil {
		c.Observer.ObserveValidationCache(hit)
	}
}

func (c *Client) observeCall(rpc string, err error) {
	if c.Observer != nil {
		c.Observer.ObserveProductCall(rpc, err)
	}
}

func (c *Client) observeBreaker(rpc string) {
	if c.Observer != nil {
		c.Observer.ObserveBreakerRejection(rpc)
	}
}
