package product

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"google.golang.org/genproto/googleapis/rpc/errdetails"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"cart-service/internal/domain"
)

// statusWith builds the error Product returns: a gRPC status carrying ErrorInfo (contracts ADR-006).
func statusWith(t *testing.T, code codes.Code, domainName, reason string, retryable string) error {
	t.Helper()
	info := &errdetails.ErrorInfo{Domain: domainName, Reason: reason, Metadata: map[string]string{"retryable": retryable}}
	withDetails, err := status.New(code, "free text that callers must not parse").WithDetails(info)
	if err != nil {
		t.Fatalf("WithDetails: %v", err)
	}
	return withDetails.Err()
}

func productDomainError(t *testing.T, err error) *domain.Error {
	t.Helper()
	var domainErr *domain.Error
	if !errors.As(err, &domainErr) || domainErr.Code != domain.CodeProductUnavailable {
		t.Fatalf("err = %v, want PRODUCT_UNAVAILABLE", err)
	}
	return domainErr
}

func openingBreaker() Config {
	return Config{Breaker: BreakerConfig{FailureThreshold: 1, OpenTimeout: time.Minute}}
}

func TestARefusedIdentityIsNotRetryableAndLeavesTheBreakerClosed(t *testing.T) {
	server := &stubServer{getSkuErr: statusWith(t, codes.PermissionDenied, errorDomain, "SERVICE_IDENTITY_NOT_ALLOWED", "false")}
	client := startStub(t, server, openingBreaker())

	for attempt := 1; attempt <= 2; attempt++ {
		_, err := client.GetSku(context.Background(), "SKU-A")
		domainErr := productDomainError(t, err)
		if domainErr.Retryable {
			t.Fatalf("attempt %d: a refused identity must not be retryable", attempt)
		}
		if !strings.Contains(err.Error(), "reason=SERVICE_IDENTITY_NOT_ALLOWED") {
			t.Fatalf("attempt %d: the reason is missing from %q", attempt, err.Error())
		}
	}
	if calls := server.getSkuCalls.Load(); calls != 2 {
		t.Fatalf("Product received %d calls, want 2: a caller fault must not open the breaker", calls)
	}
}

func TestAnInvalidArgumentWithoutDetailsIsStillTheCallersFault(t *testing.T) {
	client := startStub(t, &stubServer{getSkuErr: status.Error(codes.InvalidArgument, "bad")}, openingBreaker())

	_, err := client.GetSku(context.Background(), "SKU-A")

	if productDomainError(t, err).Retryable {
		t.Fatal("INVALID_ARGUMENT must not be retryable even without ErrorInfo")
	}
}

func TestAnUnavailableStoreIsRetryableAndCountsAgainstTheBreaker(t *testing.T) {
	server := &stubServer{getSkuErr: statusWith(t, codes.Unavailable, errorDomain, "CATALOG_STORE_UNAVAILABLE", "true")}
	client := startStub(t, server, openingBreaker())

	_, err := client.GetSku(context.Background(), "SKU-A")
	if !productDomainError(t, err).Retryable {
		t.Fatal("CATALOG_STORE_UNAVAILABLE is retryable")
	}
	_, second := client.GetSku(context.Background(), "SKU-A")
	productDomainError(t, second)

	if calls := server.getSkuCalls.Load(); calls != 1 {
		t.Fatalf("Product received %d calls, want 1: the breaker should be open after one failure", calls)
	}
}

func TestAnInternalErrorFlaggedNotRetryableIsHonouredButStillCounts(t *testing.T) {
	server := &stubServer{getSkuErr: statusWith(t, codes.Internal, errorDomain, "INTERNAL_ERROR", "false")}
	client := startStub(t, server, openingBreaker())

	_, err := client.GetSku(context.Background(), "SKU-A")
	if productDomainError(t, err).Retryable {
		t.Fatal("INTERNAL_ERROR was flagged retryable=false")
	}
	_, second := client.GetSku(context.Background(), "SKU-A")
	productDomainError(t, second)

	if calls := server.getSkuCalls.Load(); calls != 1 {
		t.Fatalf("Product received %d calls, want 1: a provider fault opens the breaker", calls)
	}
}

func TestErrorInfoFromAnotherDomainIsIgnored(t *testing.T) {
	err := statusWith(t, codes.Unavailable, "ecommerce.other.v1", "SOMETHING_ELSE", "false")

	f := classify(err)

	if f.reason != "" || !f.retryable {
		t.Fatalf("classify = %+v, want no reason and the Unavailable default (retryable)", f)
	}
}

func TestAStatusWithoutDetailsFallsBackToTheCode(t *testing.T) {
	cases := []struct {
		code        codes.Code
		retryable   bool
		callerFault bool
	}{
		{codes.Unavailable, true, false},
		{codes.DeadlineExceeded, true, false},
		{codes.Unknown, true, false},
		{codes.Internal, true, false},
		{codes.InvalidArgument, false, true},
		{codes.Unauthenticated, false, true},
		{codes.PermissionDenied, false, true},
		{codes.NotFound, false, true},
	}
	for _, tc := range cases {
		f := classify(status.Error(tc.code, "x"))
		if f.retryable != tc.retryable || f.callerFault != tc.callerFault || f.reason != "" {
			t.Errorf("%s: classify = %+v, want retryable=%v callerFault=%v", tc.code, f, tc.retryable, tc.callerFault)
		}
	}
}

func TestAPlainErrorIsATransportFailure(t *testing.T) {
	f := classify(errors.New("connection reset"))

	if !f.retryable || f.callerFault {
		t.Fatalf("classify = %+v, want a retryable transport failure", f)
	}
}

func TestReasonAndCallerFaultReadTheRawCallError(t *testing.T) {
	err := statusWith(t, codes.InvalidArgument, errorDomain, "BATCH_TOO_LARGE", "false")

	if got := Reason(err); got != "BATCH_TOO_LARGE" {
		t.Fatalf("Reason = %q", got)
	}
	if !CallerFault(err) {
		t.Fatal("INVALID_ARGUMENT is a caller fault")
	}
	if Reason(nil) != "" || CallerFault(nil) {
		t.Fatal("a nil error has no reason and no fault")
	}
}
