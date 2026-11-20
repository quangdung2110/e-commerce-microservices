package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"cart-service/internal/domain"
)

func abortWith(t *testing.T, err *domain.Error) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	engine := gin.New()
	engine.GET("/x", func(c *gin.Context) { AbortWithDomainError(c, err) })
	recorder := httptest.NewRecorder()
	engine.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/x", nil))
	return recorder
}

// contracts ADR-005: every retryable 503 tells the caller how long to wait.
func TestRetryableUnavailableCarriesRetryAfter(t *testing.T) {
	for _, code := range []domain.Code{domain.CodeCartStoreUnavailable, domain.CodeProductUnavailable} {
		recorder := abortWith(t, domain.NewRetryableError(code, "down"))

		if recorder.Code != http.StatusServiceUnavailable {
			t.Fatalf("%s: status = %d, want 503", code, recorder.Code)
		}
		if got := recorder.Header().Get("Retry-After"); got != "2" {
			t.Fatalf("%s: Retry-After = %q, want 2", code, got)
		}
	}
}

func TestOtherStatusesDoNotGetRetryAfterFromTheProblemWriter(t *testing.T) {
	recorder := abortWith(t, domain.NewError(domain.CodeItemNotFound, "no such item"))

	if recorder.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want 422", recorder.Code)
	}
	if got := recorder.Header().Get("Retry-After"); got != "" {
		t.Fatalf("Retry-After = %q, want none", got)
	}
}
