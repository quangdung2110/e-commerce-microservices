package grpc

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/config"
	"cart-service/internal/infrastructure/grpc/pb"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
)

// ProductClientImpl implements port.ProductClient using gRPC.
type ProductClientImpl struct {
	addr           string
	conn           *grpc.ClientConn
	client         pb.ProductServiceClient
	cb             *CircuitBreaker
	maxCallTimeout time.Duration

	mu     sync.RWMutex
	closed bool
}

// NewProductClient creates a new gRPC product client.
// It establishes a connection to the product service with circuit breaker support.
func NewProductClient(cfg config.GRPCSection) (*ProductClientImpl, error) {
	dialTimeout, err := time.ParseDuration(cfg.DialTimeout)
	if err != nil {
		return nil, fmt.Errorf("invalid dial_timeout: %w", err)
	}
	maxCallTimeout, err := time.ParseDuration(cfg.MaxCallTimeout)
	if err != nil {
		return nil, fmt.Errorf("invalid max_call_timeout: %w", err)
	}

	cb, err := NewCircuitBreaker(cfg.CircuitBreaker)
	if err != nil {
		return nil, fmt.Errorf("invalid circuit_breaker config: %w", err)
	}

	// Build gRPC dial options.
	dialOpts := []grpc.DialOption{
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithUnaryInterceptor(
			withCircuitBreaker(cb, maxCallTimeout),
		),
		grpc.WithDefaultCallOptions(
			grpc.WaitForReady(false),
		),
	}

	// Establish connection with dial timeout.
	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	defer cancel()

	conn, err := grpc.DialContext(
		ctx,
		cfg.ProductServiceAddr,
		dialOpts...,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to dial product service at %s: %w", cfg.ProductServiceAddr, err)
	}

	// Verify the connection is in a usable state.
	if !conn.WaitForStateChange(ctx, conn.GetState()) {
		conn.Close()
		return nil, fmt.Errorf("connection to %s did not become ready: %w", cfg.ProductServiceAddr, ctx.Err())
	}

	if conn.GetState() != connectivity.Ready && conn.GetState() != connectivity.Idle {
		conn.Close()
		return nil, fmt.Errorf("connection to %s ended in state %v", cfg.ProductServiceAddr, conn.GetState())
	}

	client := pb.NewProductServiceClient(conn)

	return &ProductClientImpl{
		addr:           cfg.ProductServiceAddr,
		conn:           conn,
		client:         client,
		cb:             cb,
		maxCallTimeout: maxCallTimeout,
	}, nil
}

// withCircuitBreaker is a gRPC unary interceptor that enforces circuit breaker
// logic and per-call timeouts for every outbound call.
func withCircuitBreaker(cb *CircuitBreaker, maxCallTimeout time.Duration) grpc.UnaryClientInterceptor {
	return func(
		ctx context.Context,
		method string,
		req, reply interface{},
		cc *grpc.ClientConn,
		invoker grpc.UnaryInvoker,
		opts ...grpc.CallOption,
	) error {
		// Reject immediately if circuit is open.
		if !cb.Allow() {
			return status.Error(codes.Unavailable, "circuit breaker is open: product-service unavailable")
		}

		// Inject per-call timeout when no deadline is set.
		if _, ok := ctx.Deadline(); !ok {
			var cancel context.CancelFunc
			ctx, cancel = context.WithTimeout(ctx, maxCallTimeout)
			defer cancel()
		}

		err := invoker(ctx, method, req, reply, cc, opts...)

		if err != nil {
			cb.RecordFailure()
		} else {
			cb.RecordSuccess()
		}

		return err
	}
}

// GetProductSku fetches a single SKU by ID.
// Used in the AddItem workflow to validate SKU and retrieve display info.
// Returns ErrProductNotFound when the SKU does not exist.
// Returns ErrProductUnavailable when the SKU exists but is inactive/banned/deleted.
func (c *ProductClientImpl) GetProductSku(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return nil, port.ErrGrpcConnectionFailed
	}

	if skuID == "" {
		return nil, entity.ErrMissingRequiredField
	}

	req := &pb.GetSkuRequest{SkuId: skuID}
	resp, err := c.client.GetSku(ctx, req)
	if err != nil {
		return c.classifyGRPCError(err)
	}

	switch r := resp.Result.(type) {
	case *pb.SkuResult:
		if r.Sku == nil {
			return nil, entity.ErrProductNotFound
		}
		return c.skuDataToInfo(r.Sku)

	case *pb.NotFoundResult:
		return nil, entity.ErrProductNotFound

	case *pb.UnavailableResult:
		return nil, entity.ErrProductUnavailable

	default:
		return nil, entity.ErrInternal
	}
}

// GetBatchProductSkus fetches multiple SKUs in a single gRPC call.
// Used in the GetCart endpoint (both cache-hit and cache-miss paths).
// Context timeout: hard limit of maxCallTimeout (200ms) enforced by the interceptor.
// Returns GrpcResult with Success=false and Error set when the call fails.
// Missing or unavailable SKUs are simply omitted from the returned map.
func (c *ProductClientImpl) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return &entity.GrpcResult{Success: false, Error: port.ErrGrpcConnectionFailed.Error()}, nil
	}

	if len(skuIDs) == 0 {
		return &entity.GrpcResult{Success: true, Skus: make(map[string]entity.SkuInfo)}, nil
	}

	unique := deduplicateStrings(skuIDs)

	req := &pb.GetBatchSkusRequest{SkuIds: unique}
	resp, err := c.client.GetBatchSkus(ctx, req)
	if err != nil {
		return c.classifyBatchError(err)
	}

	skus := make(map[string]entity.SkuInfo, len(resp.Skus))
	for skuID, sku := range resp.Skus {
		if info, err := c.skuDataToInfo(sku); err == nil {
			skus[skuID] = *info
		}
	}

	return &entity.GrpcResult{Success: true, Skus: skus}, nil
}

// Close closes the gRPC connection and releases resources.
func (c *ProductClientImpl) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

// IsHealthy checks whether the product service is reachable.
// Returns true when the gRPC connection is ready AND the circuit breaker allows traffic.
func (c *ProductClientImpl) IsHealthy(ctx context.Context) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed || c.conn == nil {
		return false
	}

	if !c.cb.Allow() {
		return false
	}

	state := c.conn.GetState()
	return state == connectivity.Ready || state == connectivity.Idle
}

// skuDataToInfo converts a protobuf SkuData to the domain SkuInfo.
func (c *ProductClientImpl) skuDataToInfo(pbSku *pb.SkuData) (*entity.SkuInfo, error) {
	if pbSku == nil {
		return nil, entity.ErrProductNotFound
	}

	return &entity.SkuInfo{
		ProductID:    pbSku.ProductId,
		VariantInfo:  pbSku.VariantInfo,
		IsAvailable:  pbSku.IsActive(),
		CurrentPrice: pbSku.Price,
		ProductName:  pbSku.ProductName,
		ImageURL:     pbSku.ImageUrl,
		AvailableQty: pbSku.StockQty,
	}, nil
}

// classifyGRPCError maps a gRPC error to a domain error for single-SKU calls.
func (c *ProductClientImpl) classifyGRPCError(err error) (*entity.SkuInfo, error) {
	st, _ := status.FromError(err)
	switch st.Code() {
	case codes.DeadlineExceeded, codes.Canceled:
		return nil, port.ErrGrpcTimeout
	case codes.NotFound:
		return nil, entity.ErrProductNotFound
	case codes.Unavailable:
		return nil, port.ErrGrpcConnectionFailed
	default:
		return nil, fmt.Errorf("%w: %s", port.ErrGrpcConnectionFailed, err.Error())
	}
}

// classifyBatchError maps a gRPC error to a partial result for batch calls.
func (c *ProductClientImpl) classifyBatchError(err error) (*entity.GrpcResult, error) {
	st, _ := status.FromError(err)
	switch st.Code() {
	case codes.DeadlineExceeded, codes.Canceled:
		return &entity.GrpcResult{Success: false, Error: port.ErrGrpcTimeout.Error()}, nil
	default:
		return &entity.GrpcResult{Success: false, Error: fmt.Sprintf("%s: %s", port.ErrGrpcConnectionFailed, err.Error())}, nil
	}
}

// deduplicateStrings returns a new slice with duplicate entries removed.
// Preserves order of first occurrence.
func deduplicateStrings(in []string) []string {
	seen := make(map[string]struct{}, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s == "" {
			continue
		}
		if _, ok := seen[s]; !ok {
			seen[s] = struct{}{}
			out = append(out, s)
		}
	}
	return out
}

// resolveProductStatus maps a product service status string to a domain constant.
func resolveProductStatus(status string) string {
	switch strings.ToUpper(status) {
	case "ACTIVE":
		return port.ProductStatusActive
	case "BANNED":
		return port.ProductStatusBanned
	case "DELETED":
		return port.ProductStatusDeleted
	default:
		return port.ProductStatusInactive
	}
}
