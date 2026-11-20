package product

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"

	productv1 "cart-service/internal/infrastructure/product/pb/productv1"
	"cart-service/internal/reqctx"
)

const (
	propTraceID     = "4bf92f3577b34da6a3ce929d0e0e4736"
	propTraceparent = "00-" + propTraceID + "-00f067aa0ba902b7-01"
)

// metadataServer records the metadata of the last call, so the test sees what a real Product
// receives on the wire rather than what the client believes it sent.
type metadataServer struct {
	productv1.UnimplementedProductServiceServer

	mu   sync.Mutex
	last metadata.MD
}

func (s *metadataServer) record(ctx context.Context) {
	md, _ := metadata.FromIncomingContext(ctx)
	s.mu.Lock()
	s.last = md.Copy()
	s.mu.Unlock()
}

func (s *metadataServer) metadata() metadata.MD {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.last
}

func (s *metadataServer) GetSku(ctx context.Context, _ *productv1.GetSkuRequest) (*productv1.GetSkuResponse, error) {
	s.record(ctx)
	return &productv1.GetSkuResponse{Result: &productv1.GetSkuResponse_Sku{Sku: activeSku()}}, nil
}

func (s *metadataServer) GetBatchSkus(ctx context.Context, _ *productv1.GetBatchSkusRequest) (*productv1.GetBatchSkusResponse, error) {
	s.record(ctx)
	return &productv1.GetBatchSkusResponse{Skus: map[string]*productv1.Sku{"SKU-A": activeSku()}}, nil
}

func startMetadataServer(t *testing.T, cfg Config) (*Client, *metadataServer) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	server := &metadataServer{}
	grpcServer := grpc.NewServer()
	productv1.RegisterProductServiceServer(grpcServer, server)
	go func() { _ = grpcServer.Serve(listener) }()
	t.Cleanup(grpcServer.Stop)

	cfg.Address = listener.Addr().String()
	cfg.DialTimeout = 2 * time.Second
	cfg.GetSkuTimeout = time.Second
	cfg.GetBatchSkusTimeout = time.Second
	client, err := NewClient(context.Background(), cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = client.Close() })
	return client, server
}

func first(md metadata.MD, key string) string {
	if values := md.Get(key); len(values) > 0 {
		return values[0]
	}
	return ""
}

func TestGetBatchSkusSendsIdentityAndTheRequestContextToProduct(t *testing.T) {
	client, server := startMetadataServer(t, Config{})
	ctx := reqctx.WithRequestID(context.Background(), "cart-request-0001")
	ctx = reqctx.WithTrace(ctx, propTraceparent, "vendor=a")

	if _, err := client.GetBatchSkus(ctx, []string{"SKU-A"}); err != nil {
		t.Fatalf("GetBatchSkus: %v", err)
	}

	md := server.metadata()
	if got := first(md, "x-service-identity"); got != DefaultServiceIdentity {
		t.Errorf("x-service-identity = %q, want %q", got, DefaultServiceIdentity)
	}
	if got := first(md, "x-request-id"); got != "cart-request-0001" {
		t.Errorf("x-request-id = %q", got)
	}
	if got := first(md, "traceparent"); got != propTraceparent {
		t.Errorf("traceparent = %q", got)
	}
	if got := first(md, "tracestate"); got != "vendor=a" {
		t.Errorf("tracestate = %q", got)
	}
}

func TestGetSkuSendsTheSameMetadataEvenThroughSingleflight(t *testing.T) {
	client, server := startMetadataServer(t, Config{ServiceIdentity: "cart-service-canary"})
	ctx := reqctx.WithRequestID(context.Background(), "cart-request-0002")
	ctx = reqctx.WithTrace(ctx, propTraceparent, "")

	if _, err := client.GetSku(ctx, "SKU-A"); err != nil {
		t.Fatalf("GetSku: %v", err)
	}

	md := server.metadata()
	if got := first(md, "x-service-identity"); got != "cart-service-canary" {
		t.Errorf("x-service-identity = %q, want the configured identity", got)
	}
	if got := first(md, "x-request-id"); got != "cart-request-0002" {
		t.Errorf("x-request-id = %q, a shared flight must keep the starter's context values", got)
	}
	if got := first(md, "traceparent"); got != propTraceparent {
		t.Errorf("traceparent = %q", got)
	}
	if got := first(md, "tracestate"); got != "" {
		t.Errorf("tracestate = %q, none was inbound", got)
	}
}

func TestCallsWithoutARequestContextMintAnIDAndInventNoTraceparent(t *testing.T) {
	client, server := startMetadataServer(t, Config{})

	if _, err := client.GetBatchSkus(context.Background(), []string{"SKU-A"}); err != nil {
		t.Fatalf("GetBatchSkus: %v", err)
	}

	md := server.metadata()
	if got := first(md, "x-request-id"); len(got) != 36 {
		t.Errorf("x-request-id = %q, want a minted UUID", got)
	}
	if got := first(md, "x-service-identity"); got != DefaultServiceIdentity {
		t.Errorf("x-service-identity = %q", got)
	}
	if len(md.Get("traceparent")) != 0 || len(md.Get("tracestate")) != 0 {
		t.Errorf("a traceparent must never be invented, got %v %v", md.Get("traceparent"), md.Get("tracestate"))
	}
}
