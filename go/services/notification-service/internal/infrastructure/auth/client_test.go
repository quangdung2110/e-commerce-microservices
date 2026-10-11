package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

func testConfig(baseURL string) config.AuthConfig {
	return config.AuthConfig{
		BaseURL:         baseURL,
		Timeout:         500 * time.Millisecond,
		ServiceIdentity: "notification-service",
		RatePerSecond:   config.LaneValues[float64]{Critical: 0, Transactional: 0, Bulk: 0},
		MaxConns:        config.LaneValues[int]{Critical: 4, Transactional: 4, Bulk: 2},
		Breaker:         config.BreakerConfig{WindowSize: 3, FailureRatio: 0.5, OpenDuration: 30 * time.Second, HalfOpenSuccesses: 1},
	}
}

func newClient(t *testing.T, srv *httptest.Server, clk clock.Clock) (*Client, *MemoryCache) {
	t.Helper()
	cache := NewMemoryCache(clk)
	c, err := NewClient(testConfig(srv.URL), 15*time.Minute, cache, telemetry.New(), nil, clk)
	require.NoError(t, err)
	return c, cache
}

func TestContactHappyPathAndCache(t *testing.T) {
	userID := uuid.New()
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/internal/v1/auth/users/"+userID.String()+"/notification-contact", r.URL.Path)
		assert.Equal(t, "notification-service", r.Header.Get(constants.HeaderServiceIdentity))
		assert.Equal(t, "req-12345678", r.Header.Get(constants.HeaderRequestID))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"user_id":"` + userID.String() + `","email":"Alice@Example.com","email_verified":true,"locale":"vi"}`))
	}))
	defer srv.Close()

	clk := clock.NewFake(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC))
	c, cache := newClient(t, srv, clk)
	ctx := reqctx.WithRequestID(context.Background(), "req-12345678")

	got, err := c.Contact(ctx, entity.LaneTransactional, userID)
	require.NoError(t, err)
	assert.Equal(t, "Alice@Example.com", got.Email.Raw())
	assert.True(t, got.EmailVerified)
	assert.Equal(t, "vi", got.Locale)
	assert.Equal(t, 1, cache.Len())

	// Second call is served from the cache.
	got2, err := c.Contact(ctx, entity.LaneBulk, userID)
	require.NoError(t, err)
	assert.Equal(t, got.Email.Raw(), got2.Email.Raw())
	assert.Equal(t, int32(1), atomic.LoadInt32(&calls))

	// After the TTL the cache misses and Auth is called again.
	clk.Advance(16 * time.Minute)
	_, err = c.Contact(ctx, entity.LaneBulk, userID)
	require.NoError(t, err)
	assert.Equal(t, int32(2), atomic.LoadInt32(&calls))

	// ForgetContact drops the entry.
	require.NoError(t, c.ForgetContact(ctx, userID))
	assert.Equal(t, 0, cache.Len())
}

func TestContactNullLocaleAndUnverified(t *testing.T) {
	userID := uuid.New()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"user_id":"` + userID.String() + `","email":"bob@example.com","email_verified":false,"locale":null}`))
	}))
	defer srv.Close()
	c, _ := newClient(t, srv, clock.System{})
	got, err := c.Contact(context.Background(), entity.LaneBulk, userID)
	require.NoError(t, err)
	assert.False(t, got.EmailVerified)
	assert.Equal(t, "", got.Locale)
}

func TestContactStatusCodes(t *testing.T) {
	cases := []struct {
		name      string
		status    int
		wantPerm  string
		wantRetry bool
		wantIs    error
	}{
		{name: "404 no recipient", status: http.StatusNotFound, wantPerm: entity.ReasonNoRecipient, wantIs: errors.ErrNoRecipient},
		{name: "429 retryable", status: http.StatusTooManyRequests, wantRetry: true},
		{name: "500 retryable", status: http.StatusInternalServerError, wantRetry: true},
		{name: "503 retryable", status: http.StatusServiceUnavailable, wantRetry: true},
		{name: "403 retryable (misconfigured identity)", status: http.StatusForbidden, wantRetry: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(`{"detail":"secret body must never be logged"}`))
			}))
			defer srv.Close()
			c, cache := newClient(t, srv, clock.System{})
			_, err := c.Contact(context.Background(), entity.LaneTransactional, uuid.New())
			require.Error(t, err)
			if tc.wantPerm != "" {
				code, ok := errors.PermanentCode(err)
				require.True(t, ok, "expected permanent, got %v", err)
				assert.Equal(t, tc.wantPerm, code)
				assert.True(t, errors.Is(err, tc.wantIs))
			}
			assert.Equal(t, tc.wantRetry, errors.IsRetryable(err))
			assert.NotContains(t, err.Error(), "secret body")
			assert.Equal(t, 0, cache.Len(), "errors are never cached")
		})
	}
}

func TestContactMalformedBodyIsRetryableAndNotLeaked(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"email": "leak@example.com", not json`))
	}))
	defer srv.Close()
	c, _ := newClient(t, srv, clock.System{})
	_, err := c.Contact(context.Background(), entity.LaneTransactional, uuid.New())
	require.Error(t, err)
	assert.True(t, errors.IsRetryable(err))
	assert.NotContains(t, err.Error(), "leak@example.com")
}

func TestContactTimeoutIsRetryable(t *testing.T) {
	block := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-block:
		case <-r.Context().Done():
		}
	}))
	defer srv.Close()
	defer close(block)
	c, _ := newClient(t, srv, clock.System{})
	_, err := c.Contact(context.Background(), entity.LaneCritical, uuid.New())
	require.Error(t, err)
	assert.True(t, errors.IsRetryable(err))
}

func TestConnectionRefusedIsRetryable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	url := srv.URL
	srv.Close()
	cache := NewMemoryCache(nil)
	c, err := NewClient(testConfig(url), time.Minute, cache, nil, nil, nil)
	require.NoError(t, err)
	_, err = c.Contact(context.Background(), entity.LaneCritical, uuid.New())
	require.Error(t, err)
	assert.True(t, errors.IsRetryable(err))
}

func TestBreakerOpensAfterFailuresAndRecovers(t *testing.T) {
	var fail atomic.Bool
	fail.Store(true)
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		if fail.Load() {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		_, _ = w.Write([]byte(`{"user_id":"` + uuid.NewString() + `","email":"x@example.com","email_verified":true}`))
	}))
	defer srv.Close()

	clk := clock.NewFake(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC))
	m := telemetry.New()
	c, _ := newClient(t, srv, clk)
	c.metrics = m
	ctx := context.Background()

	// window_size 3, ratio 0.5: three failures open the circuit.
	for i := 0; i < 3; i++ {
		_, err := c.Contact(ctx, entity.LaneBulk, uuid.New())
		require.True(t, errors.IsRetryable(err))
	}
	assert.Equal(t, breaker.StateOpen, c.ContactBreaker().State())
	assert.Equal(t, breaker.StateClosed, c.ContentBreaker().State(), "content breaker is independent")

	_, err := c.Contact(ctx, entity.LaneBulk, uuid.New())
	require.Error(t, err)
	assert.True(t, errors.IsRetryable(err))
	assert.True(t, errors.Is(err, errors.ErrBreakerOpen))
	assert.Equal(t, int32(3), atomic.LoadInt32(&calls), "open breaker does not call Auth")

	// After open_duration a probe goes through; success closes the circuit.
	fail.Store(false)
	clk.Advance(30 * time.Second)
	_, err = c.Contact(ctx, entity.LaneBulk, uuid.New())
	require.NoError(t, err)
	assert.Equal(t, breaker.StateClosed, c.ContactBreaker().State())
	assert.Equal(t, int32(4), atomic.LoadInt32(&calls))
}

func TestContentHappyPathNeverCached(t *testing.T) {
	deliveryID := uuid.New()
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/internal/v1/auth/notification-deliveries/"+deliveryID.String()+"/content", r.URL.Path)
		assert.Equal(t, int64(0), r.ContentLength)
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write([]byte(`{"delivery_id":"` + deliveryID.String() + `","recipient_email":"otp@example.com","template":"PASSWORD_RESET","expires_at":"2026-01-01T00:15:00Z","template_data":{"reset_url":"https://x/reset?t=SECRET"}}`))
	}))
	defer srv.Close()
	c, cache := newClient(t, srv, clock.System{})

	got, err := c.Content(context.Background(), entity.LaneCritical, deliveryID)
	require.NoError(t, err)
	assert.Equal(t, deliveryID, got.DeliveryID)
	assert.Equal(t, "otp@example.com", got.Recipient.Raw())
	assert.Equal(t, "PASSWORD_RESET", got.Template)
	assert.Equal(t, time.Date(2026, 1, 1, 0, 15, 0, 0, time.UTC), got.ExpiresAt)
	assert.JSONEq(t, `{"reset_url":"https://x/reset?t=SECRET"}`, string(got.TemplateData))
	assert.Equal(t, 0, cache.Len())

	_, err = c.Content(context.Background(), entity.LaneCritical, deliveryID)
	require.NoError(t, err)
	assert.Equal(t, int32(2), atomic.LoadInt32(&calls), "content is never cached")
}

func TestContentStatusCodes(t *testing.T) {
	cases := []struct {
		name     string
		status   int
		wantPerm string
		wantIs   error
	}{
		{name: "404", status: http.StatusNotFound, wantPerm: entity.ReasonNoRecipient, wantIs: errors.ErrNoRecipient},
		{name: "410", status: http.StatusGone, wantPerm: entity.ReasonContentExpired, wantIs: errors.ErrContentExpired},
		{name: "500", status: http.StatusInternalServerError},
		{name: "429", status: http.StatusTooManyRequests},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
			}))
			defer srv.Close()
			c, _ := newClient(t, srv, clock.System{})
			_, err := c.Content(context.Background(), entity.LaneCritical, uuid.New())
			require.Error(t, err)
			if tc.wantPerm != "" {
				code, ok := errors.PermanentCode(err)
				require.True(t, ok)
				assert.Equal(t, tc.wantPerm, code)
				assert.True(t, errors.Is(err, tc.wantIs))
				assert.False(t, errors.IsRetryable(err))
			} else {
				assert.True(t, errors.IsRetryable(err))
			}
		})
	}
}

func TestPerLaneClientsAndLimiters(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer srv.Close()
	cfg := testConfig(srv.URL)
	cfg.RatePerSecond = config.LaneValues[float64]{Critical: 0, Transactional: 30, Bulk: 20}
	c, err := NewClient(cfg, time.Minute, nil, nil, nil, nil)
	require.NoError(t, err)
	assert.Len(t, c.clients, 3)
	for _, lane := range entity.AllLanes {
		tr := c.clients[lane].Transport.(*http.Transport)
		assert.Equal(t, cfg.MaxConns.For(string(lane)), tr.MaxConnsPerHost)
		assert.Equal(t, cfg.RatePerSecond.For(string(lane)), c.limiters[lane].rate)
	}
	// Distinct transports per lane (bulkhead).
	assert.NotSame(t, c.clients[entity.LaneBulk].Transport, c.clients[entity.LaneCritical].Transport)
}

func TestLimiterWaitsBetweenTokens(t *testing.T) {
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	l := newLimiter(2, func() time.Time { return now })
	var slept []time.Duration
	l.sleep = func(ctx context.Context, d time.Duration) error {
		slept = append(slept, d)
		now = now.Add(d)
		return nil
	}
	ctx := context.Background()
	require.NoError(t, l.wait(ctx)) // burst 2
	require.NoError(t, l.wait(ctx))
	require.NoError(t, l.wait(ctx)) // must wait ~500 ms for one token
	require.Len(t, slept, 1)
	assert.InDelta(t, float64(500*time.Millisecond), float64(slept[0]), float64(time.Millisecond))

	unlimited := newLimiter(0, func() time.Time { return now })
	for i := 0; i < 100; i++ {
		require.NoError(t, unlimited.wait(ctx))
	}
}

func TestLimiterHonoursContextCancel(t *testing.T) {
	l := newLimiter(1, time.Now)
	require.NoError(t, l.wait(context.Background()))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	err := l.wait(ctx)
	assert.ErrorIs(t, err, context.DeadlineExceeded)
}

func TestNewClientRejectsEmptyBaseURL(t *testing.T) {
	_, err := NewClient(config.AuthConfig{}, time.Minute, nil, nil, nil, nil)
	require.Error(t, err)
}
