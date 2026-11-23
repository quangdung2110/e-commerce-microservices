package product

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc"

	"cart-service/internal/domain"
	commonv1 "cart-service/internal/infrastructure/product/pb/commonv1"
	productv1 "cart-service/internal/infrastructure/product/pb/productv1"
)

// stubServer is a real gRPC implementation of ProductService, so the client is
// exercised against the generated stubs rather than a hand-written fake.
type stubServer struct {
	productv1.UnimplementedProductServiceServer

	mu sync.Mutex

	getSkuResponse *productv1.GetSkuResponse
	getSkuErr      error
	getSkuCalls    atomic.Int64
	getSkuDelay    time.Duration

	batchResponse     *productv1.GetBatchSkusResponse
	batchErr          error
	batchDelay        time.Duration
	firstBatchStarted chan struct{}
	batchCalls        atomic.Int64
}

func (s *stubServer) GetSku(ctx context.Context, req *productv1.GetSkuRequest) (*productv1.GetSkuResponse, error) {
	s.getSkuCalls.Add(1)

	s.mu.Lock()
	delay := s.getSkuDelay
	response, err := s.getSkuResponse, s.getSkuErr
	s.mu.Unlock()

	if delay > 0 {
		select {
		case <-time.After(delay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return response, err
}

func (s *stubServer) GetBatchSkus(ctx context.Context, _ *productv1.GetBatchSkusRequest) (*productv1.GetBatchSkusResponse, error) {
	if s.firstBatchStarted != nil && s.batchCalls.Add(1) == 1 {
		close(s.firstBatchStarted)
		<-ctx.Done()
		return nil, ctx.Err()
	}
	if s.batchDelay > 0 {
		select {
		case <-time.After(s.batchDelay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return s.batchResponse, s.batchErr
}

func startStub(t *testing.T, server *stubServer, cfg Config) *Client {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	grpcServer := grpc.NewServer()
	productv1.RegisterProductServiceServer(grpcServer, server)
	go func() { _ = grpcServer.Serve(listener) }()
	t.Cleanup(grpcServer.Stop)

	cfg.Address = listener.Addr().String()
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

func activeSku() *productv1.Sku {
	return &productv1.Sku{
		SkuId:             "SKU-A",
		ProductId:         "PRODUCT-A",
		ProductName:       "Ao thun do",
		VariantAttributes: map[string]string{"color": "red"},
		VariantName:       "Màu sắc: Đỏ",
		ImageUrl:          "products/a/hero.png",
		Price:             &commonv1.Money{AmountMinor: 125000, Currency: "VND"},
		Status:            productv1.SkuStatus_SKU_STATUS_ACTIVE,
	}
}

func TestGetSkuMapsAnActiveSku(t *testing.T) {
	client := startStub(t, &stubServer{
		getSkuResponse: &productv1.GetSkuResponse{
			Result: &productv1.GetSkuResponse_Sku{Sku: activeSku()},
		},
	}, Config{})

	snapshot, err := client.GetSku(context.Background(), "SKU-A")
	if err != nil {
		t.Fatalf("GetSku: %v", err)
	}

	if snapshot.ProductID != "PRODUCT-A" || snapshot.ProductName != "Ao thun do" {
		t.Fatalf("snapshot = %+v", snapshot)
	}
	if snapshot.Price.AmountMinor != 125000 || snapshot.Price.Currency != "VND" {
		t.Fatalf("price = %+v", snapshot.Price)
	}
	// Product sends a storage key; Cart keeps it as is and builds the URL only
	// when it renders a response.
	if snapshot.ImageKey != "products/a/hero.png" {
		t.Fatalf("image key = %q, want the unchanged Product storage key", snapshot.ImageKey)
	}
	if !snapshot.IsAvailable {
		t.Fatalf("catalog availability = %v, want true", snapshot.IsAvailable)
	}
	if snapshot.VariantAttributes["color"] != "red" {
		t.Fatalf("variant attributes = %+v", snapshot.VariantAttributes)
	}
	if snapshot.VariantName != "Màu sắc: Đỏ" {
		t.Fatalf("variant name = %q", snapshot.VariantName)
	}
}

// not_found and unavailable are answers, not transport failures: the SKU may
// not enter a cart, and the caller must see a domain error rather than a 503.
func TestGetSkuRejectsSkusThatCannotBeSold(t *testing.T) {
	cases := map[string]*productv1.GetSkuResponse{
		"not found": {Result: &productv1.GetSkuResponse_NotFound{
			NotFound: &productv1.SkuNotFound{SkuId: "SKU-A"},
		}},
		"inactive": {Result: &productv1.GetSkuResponse_Unavailable{
			Unavailable: &productv1.SkuUnavailable{
				SkuId:  "SKU-A",
				Reason: productv1.SkuUnavailableReason_SKU_UNAVAILABLE_REASON_INACTIVE,
			},
		}},
		"inactive status": {Result: &productv1.GetSkuResponse_Sku{Sku: &productv1.Sku{
			SkuId:  "SKU-A",
			Status: productv1.SkuStatus_SKU_STATUS_INACTIVE,
		}}},
	}

	for name, response := range cases {
		client := startStub(t, &stubServer{getSkuResponse: response}, Config{})

		_, err := client.GetSku(context.Background(), "SKU-A")
		var domainErr *domain.Error
		if !errors.As(err, &domainErr) || domainErr.Code != domain.CodeProductNotAvailable {
			t.Fatalf("%s: err = %v, want PRODUCT_NOT_AVAILABLE", name, err)
		}
	}
}

// A transport failure is retryable infrastructure trouble, and must not be
// confused with "this SKU is not for sale".
func TestGetSkuReportsTransportFailuresAsUnavailable(t *testing.T) {
	client := startStub(t, &stubServer{getSkuErr: errors.New("boom")}, Config{})

	_, err := client.GetSku(context.Background(), "SKU-A")
	var domainErr *domain.Error
	if !errors.As(err, &domainErr) || domainErr.Code != domain.CodeProductUnavailable {
		t.Fatalf("err = %v, want PRODUCT_UNAVAILABLE", err)
	}
	if !domainErr.Retryable {
		t.Fatal("a transport failure should be marked retryable")
	}
}

// The deadline is mandatory: a slow Product must not hold a cart write open.
func TestGetSkuEnforcesItsDeadline(t *testing.T) {
	client := startStub(t, &stubServer{
		getSkuDelay:    500 * time.Millisecond,
		getSkuResponse: &productv1.GetSkuResponse{Result: &productv1.GetSkuResponse_Sku{Sku: activeSku()}},
	}, Config{GetSkuTimeout: 50 * time.Millisecond})

	start := time.Now()
	_, err := client.GetSku(context.Background(), "SKU-A")
	elapsed := time.Since(start)

	if err == nil {
		t.Fatal("a call past its deadline succeeded")
	}
	if elapsed > 300*time.Millisecond {
		t.Fatalf("the call took %s; the deadline was not enforced", elapsed)
	}
}

// A fresh cache entry must short-circuit the RPC entirely.
func TestGetSkuServesFromTheValidationCache(t *testing.T) {
	server := &stubServer{
		getSkuResponse: &productv1.GetSkuResponse{Result: &productv1.GetSkuResponse_Sku{Sku: activeSku()}},
	}
	client := startStub(t, server, Config{CacheSize: 16, CacheTTL: time.Minute})

	for i := 0; i < 5; i++ {
		if _, err := client.GetSku(context.Background(), "SKU-A"); err != nil {
			t.Fatalf("call %d: %v", i, err)
		}
	}

	if calls := server.getSkuCalls.Load(); calls != 1 {
		t.Fatalf("product was called %d times, want 1", calls)
	}
}

// A thundering herd on one hot SKU must cost one RPC, not one per request.
func TestGetSkuCoalescesConcurrentRefreshes(t *testing.T) {
	server := &stubServer{
		getSkuDelay:    100 * time.Millisecond,
		getSkuResponse: &productv1.GetSkuResponse{Result: &productv1.GetSkuResponse_Sku{Sku: activeSku()}},
	}
	client := startStub(t, server, Config{CacheSize: 16, CacheTTL: time.Minute})

	var wg sync.WaitGroup
	errs := make(chan error, 20)
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := client.GetSku(context.Background(), "SKU-A"); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)

	for err := range errs {
		t.Fatalf("concurrent GetSku: %v", err)
	}
	if calls := server.getSkuCalls.Load(); calls != 1 {
		t.Fatalf("product was called %d times for 20 concurrent requests, want 1", calls)
	}
}

// One client hanging up must not fail the other requests that joined the same
// flight: the leader's cancellation is not Product's failure.
func TestGetSkuLeaderCancellationDoesNotFailFollowers(t *testing.T) {
	server := &stubServer{
		getSkuDelay:    200 * time.Millisecond,
		getSkuResponse: &productv1.GetSkuResponse{Result: &productv1.GetSkuResponse_Sku{Sku: activeSku()}},
	}
	client := startStub(t, server, Config{
		CacheSize: 16, CacheTTL: time.Minute,
		Breaker: BreakerConfig{FailureThreshold: 1, OpenTimeout: time.Minute},
	})

	leaderCtx, cancelLeader := context.WithCancel(context.Background())
	leaderDone := make(chan error, 1)
	go func() {
		_, err := client.GetSku(leaderCtx, "SKU-A")
		leaderDone <- err
	}()
	// Let the leader start the flight before the follower joins it.
	time.Sleep(50 * time.Millisecond)

	followerDone := make(chan error, 1)
	go func() {
		_, err := client.GetSku(context.Background(), "SKU-A")
		followerDone <- err
	}()
	time.Sleep(20 * time.Millisecond)
	cancelLeader()

	if err := <-leaderDone; err == nil {
		t.Fatal("the cancelled caller was not released with an error")
	}
	if err := <-followerDone; err != nil {
		t.Fatalf("follower failed because the leader hung up: %v", err)
	}
	if calls := server.getSkuCalls.Load(); calls != 1 {
		t.Fatalf("product was called %d times, want 1", calls)
	}
	if !client.breaker.allow() {
		t.Fatal("a caller hanging up opened the breaker")
	}
}

// A cancelled caller returns promptly instead of waiting out the flight.
func TestGetSkuCancelledCallerReturnsEarly(t *testing.T) {
	client := startStub(t, &stubServer{
		getSkuDelay:    500 * time.Millisecond,
		getSkuResponse: &productv1.GetSkuResponse{Result: &productv1.GetSkuResponse_Sku{Sku: activeSku()}},
	}, Config{})

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	start := time.Now()
	_, err := client.GetSku(ctx, "SKU-A")
	if err == nil {
		t.Fatal("a cancelled caller got a result")
	}
	if elapsed := time.Since(start); elapsed > 300*time.Millisecond {
		t.Fatalf("the caller waited %s for the flight", elapsed)
	}
}

func cancelInFlightBatch(t *testing.T, client *Client, started <-chan struct{}) error {
	t.Helper()
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	result := make(chan error, 1)
	go func() {
		_, err := client.GetBatchSkus(ctx, []string{"SKU-A"})
		result <- err
	}()
	select {
	case <-started:
		cancel()
		return <-result
	case err := <-result:
		t.Fatalf("RPC completed before the stub started: %v", err)
		return err
	}
}

// A client abandoning a cart read says nothing about Product's health.
func TestGetBatchSkusCallerCancellationDoesNotCountAgainstTheBreaker(t *testing.T) {
	started := make(chan struct{})
	client := startStub(t, &stubServer{firstBatchStarted: started},
		Config{Breaker: BreakerConfig{FailureThreshold: 1, OpenTimeout: time.Minute}})
	if err := cancelInFlightBatch(t, client, started); err == nil {
		t.Fatal("a cancelled read succeeded")
	}
	if !client.breaker.allow() {
		t.Fatal("a cancelled caller opened the breaker")
	}
}

func TestGetBatchSkusCallerDeadlineDoesNotCountAgainstTheBreaker(t *testing.T) {
	client := startStub(t, &stubServer{
		batchDelay:    500 * time.Millisecond,
		batchResponse: &productv1.GetBatchSkusResponse{},
	}, Config{Breaker: BreakerConfig{FailureThreshold: 1, OpenTimeout: time.Minute}})

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err := client.GetBatchSkus(ctx, []string{"SKU-A"}); err == nil {
		t.Fatal("a read past the caller's deadline succeeded")
	}

	if !client.breaker.allow() {
		t.Fatal("the caller's deadline opened the breaker")
	}
}

// The half-open probe is a real request, so its caller can hang up. That must
// neither reopen the breaker nor strand the probe slot: the next request has to
// be admitted as the new probe and, once it succeeds, close the breaker.
func TestGetBatchSkusCancelledHalfOpenProbeDoesNotStrandTheBreaker(t *testing.T) {
	started := make(chan struct{})
	client := startStub(t, &stubServer{
		firstBatchStarted: started,
		batchResponse:     &productv1.GetBatchSkusResponse{},
	}, Config{Breaker: BreakerConfig{FailureThreshold: 1, HalfOpenMaxCalls: 1, OpenTimeout: 30 * time.Second}})

	now := time.Unix(1780000000, 0)
	client.breaker.now = func() time.Time { return now }

	// Trip the breaker, then let the open timeout elapse so the next call is
	// admitted as the half-open probe.
	client.breaker.allow()
	client.breaker.failure()
	if _, err := client.GetBatchSkus(context.Background(), []string{"SKU-A"}); err == nil {
		t.Fatal("the open breaker admitted a call")
	}
	now = now.Add(31 * time.Second)

	if err := cancelInFlightBatch(t, client, started); err == nil {
		t.Fatal("a cancelled probe succeeded")
	}

	// The cancelled probe must not have reopened the breaker...
	if client.breaker.isOpen() {
		t.Fatal("a cancelled probe reopened the breaker")
	}
	// ...nor kept its slot, which would reject every call from here on.
	if _, err := client.GetBatchSkus(context.Background(), []string{"SKU-A"}); err != nil {
		t.Fatalf("the next probe was not admitted: %v", err)
	}
	if !client.breaker.allow() || !client.breaker.allow() {
		t.Fatal("a successful probe did not close the breaker")
	}
}

// Product being slow is still Product's failure: its own deadline must count.
func TestGetBatchSkusOwnDeadlineStillCountsAgainstTheBreaker(t *testing.T) {
	client := startStub(t, &stubServer{
		batchDelay:    500 * time.Millisecond,
		batchResponse: &productv1.GetBatchSkusResponse{},
	}, Config{
		GetBatchSkusTimeout: 50 * time.Millisecond,
		Breaker:             BreakerConfig{FailureThreshold: 1, OpenTimeout: time.Minute},
	})

	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	if _, err := client.GetBatchSkus(ctx, []string{"SKU-A"}); err == nil {
		t.Fatal("a call past its deadline succeeded")
	}
	if client.breaker.allow() {
		t.Fatal("Product timing out did not open the breaker")
	}
}

func TestGetBatchSkusMapsEveryOutcome(t *testing.T) {
	client := startStub(t, &stubServer{batchResponse: &productv1.GetBatchSkusResponse{
		Skus:              map[string]*productv1.Sku{"SKU-A": activeSku()},
		NotFoundSkuIds:    []string{"SKU-GONE"},
		UnavailableSkuIds: []string{"SKU-INACTIVE"},
	}}, Config{})

	snapshots, err := client.GetBatchSkus(context.Background(), []string{"SKU-A", "SKU-GONE", "SKU-INACTIVE"})
	if err != nil {
		t.Fatalf("GetBatchSkus: %v", err)
	}

	// Every requested SKU needs an entry, or a read cannot tell "product said
	// no" apart from "product did not answer".
	if len(snapshots) != 3 {
		t.Fatalf("got %d snapshots, want 3", len(snapshots))
	}
	if !snapshots["SKU-A"].IsAvailable || snapshots["SKU-A"].Price.AmountMinor != 125000 {
		t.Fatalf("SKU-A = %+v", snapshots["SKU-A"])
	}
	if snapshots["SKU-GONE"].IsAvailable || snapshots["SKU-INACTIVE"].IsAvailable {
		t.Fatalf("a missing or unavailable sku was marked available")
	}
	if snapshots["SKU-GONE"].Price.Currency != "" {
		t.Fatal("a missing sku carries a price")
	}
}

func TestGetBatchSkusRejectsAnOversizedBatch(t *testing.T) {
	client := startStub(t, &stubServer{}, Config{})

	skuIDs := make([]string, domain.MaxBatchSkuLookup+1)
	for i := range skuIDs {
		skuIDs[i] = "SKU"
	}

	if _, err := client.GetBatchSkus(context.Background(), skuIDs); err == nil {
		t.Fatal("a batch past the provider cap was sent")
	}
}

func TestGetBatchSkusShortCircuitsAnEmptyCart(t *testing.T) {
	client := startStub(t, &stubServer{batchErr: errors.New("should not be called")}, Config{})

	snapshots, err := client.GetBatchSkus(context.Background(), nil)
	if err != nil {
		t.Fatalf("GetBatchSkus: %v", err)
	}
	if len(snapshots) != 0 {
		t.Fatalf("got %d snapshots for an empty request", len(snapshots))
	}
}

// Once the breaker is open, calls must fail fast without touching the network.
func TestOpenBreakerRejectsWithoutCallingProduct(t *testing.T) {
	server := &stubServer{getSkuErr: errors.New("boom")}
	client := startStub(t, server, Config{
		Breaker: BreakerConfig{FailureThreshold: 2, OpenTimeout: time.Minute},
	})

	for i := 0; i < 2; i++ {
		if _, err := client.GetSku(context.Background(), "SKU-A"); err == nil {
			t.Fatalf("call %d unexpectedly succeeded", i)
		}
	}
	callsBefore := server.getSkuCalls.Load()

	_, err := client.GetSku(context.Background(), "SKU-A")
	if err == nil {
		t.Fatal("the breaker allowed a call while open")
	}
	if server.getSkuCalls.Load() != callsBefore {
		t.Fatal("an open breaker still reached the network")
	}

	// Readiness has to reflect the open breaker without issuing an RPC of its
	// own; probing a struggling dependency is not a probe, it is more load.
	if err := client.Ready(context.Background()); err == nil {
		t.Fatal("Ready reported healthy while the breaker was open")
	}
	if server.getSkuCalls.Load() != callsBefore {
		t.Fatal("the readiness probe issued an rpc")
	}
}
