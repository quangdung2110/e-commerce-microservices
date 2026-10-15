package product

import (
	"fmt"

	"google.golang.org/genproto/googleapis/rpc/errdetails"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"cart-service/internal/domain"
)

// errorDomain is ErrorInfo.domain on every error Product itself returns (contracts ADR-006).
const errorDomain = "ecommerce.product.v1"

// failure is what Cart learned from a failed ProductService call: the gRPC status plus the stable
// reason Product attached, if any. The statuses the gRPC runtime or the mesh produce (deadline,
// connect failure) carry no details and are classified by status alone.
type failure struct {
	code   codes.Code
	reason string
	// retryable is Product's own flag when it sent ErrorInfo, otherwise a default taken from the status.
	retryable bool
	// callerFault means Product answered correctly and refused the request because of what Cart sent
	// or is configured with. It proves Product is healthy, so it is not counted against the breaker.
	callerFault bool
}

// classify reads a call error. An error that is not a gRPC status (a closed connection, a cancelled
// context) is a retryable transport failure.
func classify(err error) failure {
	st, ok := status.FromError(err)
	if !ok {
		return failure{code: codes.Unavailable, retryable: true}
	}
	f := failure{code: st.Code(), callerFault: isCallerFault(st.Code())}
	f.retryable = !f.callerFault
	for _, detail := range st.Details() {
		info, ok := detail.(*errdetails.ErrorInfo)
		if !ok || info.GetDomain() != errorDomain {
			continue
		}
		f.reason = info.GetReason()
		if value, present := info.GetMetadata()["retryable"]; present {
			f.retryable = value == "true"
		}
		break
	}
	return f
}

// isCallerFault lists the statuses that answer "your request is wrong", never "I am broken".
func isCallerFault(code codes.Code) bool {
	switch code {
	case codes.InvalidArgument, codes.Unauthenticated, codes.PermissionDenied, codes.NotFound:
		return true
	}
	return false
}

// Reason returns the stable ErrorInfo.reason Product attached to err, or "" when there is none.
func Reason(err error) string {
	if err == nil {
		return ""
	}
	return classify(err).reason
}

// CallerFault reports whether err is a refusal caused by the request or by Cart's own configuration.
func CallerFault(err error) bool {
	return err != nil && classify(err).callerFault
}

// failed turns a failed call into the domain error and feeds the breaker. Only a failure of Product
// itself counts against it.
func (c *Client) failed(err error) error {
	f := classify(err)
	if f.callerFault {
		c.breaker.success()
	} else {
		c.breaker.failure()
	}
	return productFailure(err, f)
}

// productFailure wraps a Product error as PRODUCT_UNAVAILABLE. A refusal of the request itself is not
// retryable: repeating it unchanged cannot succeed.
func productFailure(err error, f failure) error {
	detail := "product service is unavailable"
	if f.callerFault {
		detail = "product service refused the request"
	}
	cause := fmt.Sprintf("%v", err)
	if f.reason != "" {
		cause = fmt.Sprintf("reason=%s: %v", f.reason, err)
	}
	return fmt.Errorf("%w (%s)", &domain.Error{
		Code:      domain.CodeProductUnavailable,
		Detail:    detail,
		Retryable: f.retryable,
	}, cause)
}
