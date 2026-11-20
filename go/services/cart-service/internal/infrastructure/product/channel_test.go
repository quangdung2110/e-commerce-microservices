package product

import (
	"context"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/resolver"
	"google.golang.org/grpc/resolver/manual"

	productv1 "cart-service/internal/infrastructure/product/pb/productv1"
)

// countingServer answers GetBatchSkus (no cache, no singleflight in front of it) and counts the calls it served.
type countingServer struct {
	productv1.UnimplementedProductServiceServer
	calls atomic.Int64
}

func (s *countingServer) GetBatchSkus(_ context.Context, _ *productv1.GetBatchSkusRequest) (*productv1.GetBatchSkusResponse, error) {
	s.calls.Add(1)
	return &productv1.GetBatchSkusResponse{Skus: map[string]*productv1.Sku{"SKU-A": activeSku()}}, nil
}

func startCounting(t *testing.T, options ...grpc.ServerOption) (*countingServer, string, *connCounter) {
	t.Helper()
	raw, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	listener := &connCounter{Listener: raw}
	server := &countingServer{}
	grpcServer := grpc.NewServer(options...)
	productv1.RegisterProductServiceServer(grpcServer, server)
	go func() { _ = grpcServer.Serve(listener) }()
	t.Cleanup(grpcServer.Stop)
	return server, raw.Addr().String(), listener
}

// connCounter counts the connections a server accepted.
type connCounter struct {
	net.Listener
	accepted atomic.Int64
}

func (l *connCounter) Accept() (net.Conn, error) {
	conn, err := l.Listener.Accept()
	if err == nil {
		l.accepted.Add(1)
	}
	return conn, err
}

func TestTargetGivesABareAddressTheDnsScheme(t *testing.T) {
	cases := map[string]string{
		"product-service:9091":       "dns:///product-service:9091",
		"127.0.0.1:9091":             "dns:///127.0.0.1:9091",
		"dns:///product-service:1":   "dns:///product-service:1",
		"passthrough:///localhost:1": "passthrough:///localhost:1",
	}
	for address, want := range cases {
		if got := target(address); got != want {
			t.Errorf("target(%q) = %q, want %q", address, got, want)
		}
	}
}

// contracts ADR-007: without a waypoint a Service that resolves to several pods must still share the calls.
func TestCallsAreSpreadOverEveryResolvedAddress(t *testing.T) {
	var servers []*countingServer
	var addresses []resolver.Address
	for i := 0; i < 3; i++ {
		server, address, _ := startCounting(t)
		servers = append(servers, server)
		addresses = append(addresses, resolver.Address{Addr: address})
	}
	builder := manual.NewBuilderWithScheme("products")
	builder.InitialState(resolver.State{Addresses: addresses})

	client := startWithTarget(t, "products:///product-service", Config{DialOptions: []grpc.DialOption{grpc.WithResolvers(builder)}})

	for i := 0; i < 30; i++ {
		if _, err := client.GetBatchSkus(context.Background(), []string{"SKU-A"}); err != nil {
			t.Fatalf("call %d: %v", i, err)
		}
	}
	// Round robin starts as each connection becomes ready, so the split is near-even rather than exact.
	// pick_first would put all 30 on one backend.
	for i, server := range servers {
		if calls := server.calls.Load(); calls < 7 || calls > 13 {
			t.Errorf("backend %d served %d of 30 calls, want about 10", i, calls)
		}
	}
}

// startWithTarget builds a client for an explicit target, bypassing the dns prefix.
func startWithTarget(t *testing.T, address string, cfg Config) *Client {
	t.Helper()
	cfg.Address = address
	if cfg.DialTimeout == 0 {
		cfg.DialTimeout = 2 * time.Second
	}
	if cfg.GetSkuTimeout == 0 {
		cfg.GetSkuTimeout = time.Second
	}
	if cfg.GetBatchSkusTimeout == 0 {
		cfg.GetBatchSkusTimeout = time.Second
	}
	client, err := NewClient(context.Background(), cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = client.Close() })
	return client
}

// contracts ADR-007: Product closes connections after max-connection-age and calls must keep succeeding on a new one.
func TestCallsSurviveTheServerClosingAnAgedConnection(t *testing.T) {
	server, address, listener := startCounting(t, grpc.KeepaliveParams(keepalive.ServerParameters{
		MaxConnectionAge:      200 * time.Millisecond,
		MaxConnectionAgeGrace: 100 * time.Millisecond,
	}))
	client := startWithTarget(t, "dns:///"+address, Config{})

	var failures int
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := client.GetBatchSkus(context.Background(), []string{"SKU-A"}); err != nil {
			failures++
		}
		time.Sleep(20 * time.Millisecond)
	}

	if failures != 0 {
		t.Fatalf("%d calls failed while the server was recycling connections", failures)
	}
	if got := listener.accepted.Load(); got < 3 {
		t.Fatalf("server accepted %d connections in 2s, want at least 3: the aged connection was never replaced", got)
	}
	if server.calls.Load() < 50 {
		t.Fatalf("only %d calls reached the server", server.calls.Load())
	}
}

func TestKeepaliveDefaultsStayAboveWhatProductPermits(t *testing.T) {
	// Product permits a ping every 20s (grpc.server.permit-keep-alive-time); more frequent pings are answered
	// with GOAWAY too_many_pings.
	const productPermits = 20 * time.Second
	if DefaultKeepAliveTime <= productPermits {
		t.Fatalf("DefaultKeepAliveTime %v must exceed what Product permits (%v)", DefaultKeepAliveTime, productPermits)
	}
	if DefaultKeepAliveTimeout <= 0 || DefaultKeepAliveTimeout >= DefaultKeepAliveTime {
		t.Fatalf("keepalive timeout %v must be positive and below the interval %v", DefaultKeepAliveTimeout, DefaultKeepAliveTime)
	}
}
