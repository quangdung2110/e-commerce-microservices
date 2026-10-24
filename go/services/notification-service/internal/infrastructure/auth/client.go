// Package auth is the HTTP client for the Auth internal API
// (contracts/http/auth/internal/v1/openapi.yaml, docs/notification/10-interfaces.md
// §4). It resolves the recipient at send time so no address ever travels
// through Kafka (02-pipeline.md §2.3), and it owns the per-lane bulkhead,
// client-side rate limit, breaker and contact cache (06-backpressure.md §3).
//
// Privacy rules (11-privacy.md): response bodies are never logged, the
// address goes straight into pii.Email, and the content endpoint (which may
// carry an OTP or reset URL) is never cached.
package auth

import (
	obs "github.com/ecommerce/observability"
	"context"
	"encoding/json"
	stderrors "errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Dependency labels of dependency_request_duration_seconds and
// circuit_breaker_state (14-observability.md §2.3).
const (
	DependencyContact = "auth_contact"
	DependencyContent = "auth_content"
)

// maxBodyBytes bounds what we read from Auth; both payloads are tiny.
const maxBodyBytes = 64 << 10

// Client implements port.ContactResolver over net/http.
type Client struct {
	cfg      config.AuthConfig
	cacheTTL time.Duration
	cache    ContactCache
	clk      clock.Clock
	metrics  *telemetry.Metrics
	logger   *zap.Logger

	// One http.Client (own Transport, own connection cap) and one limiter per
	// lane: the bulkhead of 06 §3 so a bulk backlog cannot starve critical.
	clients  map[entity.Lane]*http.Client
	limiters map[entity.Lane]*limiter

	contactBreaker port.CircuitBreaker
	contentBreaker port.CircuitBreaker
}

// Option customises the client.
type Option func(*Client)

// WithBreakers injects pre-built breakers (tests, or when the caller wants
// to share them with readiness).
func WithBreakers(contact, content port.CircuitBreaker) Option {
	return func(c *Client) {
		c.contactBreaker = contact
		c.contentBreaker = content
	}
}

// NewClient builds the client. cacheTTL is contact.cache_ttl (15 m). The
// breakers auth_contact and auth_content are created from cfg.Breaker unless
// injected, and publish their state to metrics.CircuitBreakerState.
func NewClient(cfg config.AuthConfig, cacheTTL time.Duration, cache ContactCache, metrics *telemetry.Metrics, logger *zap.Logger, clk clock.Clock, opts ...Option) (*Client, error) {
	if clk == nil {
		clk = clock.System{}
	}
	if logger == nil {
		logger = zap.NewNop()
	}
	if cache == nil {
		cache = NewMemoryCache(clk)
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 2 * time.Second
	}
	cfg.BaseURL = strings.TrimRight(cfg.BaseURL, "/")
	if cfg.BaseURL == "" {
		return nil, fmt.Errorf("auth client: base_url is empty")
	}

	c := &Client{
		cfg:      cfg,
		cacheTTL: cacheTTL,
		cache:    cache,
		clk:      clk,
		metrics:  metrics,
		logger:   logger.Named("auth-client"),
		clients:  map[entity.Lane]*http.Client{},
		limiters: map[entity.Lane]*limiter{},
	}
	for _, lane := range entity.AllLanes {
		maxConns := cfg.MaxConns.For(string(lane))
		tr := &http.Transport{
			Proxy: http.ProxyFromEnvironment,
			DialContext: (&net.Dialer{
				Timeout:   cfg.Timeout,
				KeepAlive: 30 * time.Second,
			}).DialContext,
			MaxConnsPerHost:       maxConns,
			MaxIdleConnsPerHost:   maxConns,
			IdleConnTimeout:       90 * time.Second,
			ResponseHeaderTimeout: cfg.Timeout,
			ForceAttemptHTTP2:     true,
		}
		c.clients[lane] = &http.Client{Transport: obs.Transport(tr), Timeout: cfg.Timeout}
		c.limiters[lane] = newLimiter(cfg.RatePerSecond.For(string(lane)), func() time.Time { return time.Now() })
		if metrics != nil {
			metrics.BulkheadCapacity.WithLabelValues(string(lane), string(entity.ChannelEmail), "auth_http_conns").Set(float64(maxConns))
		}
	}
	for _, o := range opts {
		o(c)
	}
	if c.contactBreaker == nil {
		c.contactBreaker = breaker.New(cfg.Breaker, clk, breaker.WithStateChange(c.stateHook(DependencyContact)))
	}
	if c.contentBreaker == nil {
		c.contentBreaker = breaker.New(cfg.Breaker, clk, breaker.WithStateChange(c.stateHook(DependencyContent)))
	}
	c.publishState(DependencyContact, c.contactBreaker.State())
	c.publishState(DependencyContent, c.contentBreaker.State())
	return c, nil
}

// ContactBreaker exposes the auth_contact breaker (readiness, tests).
func (c *Client) ContactBreaker() port.CircuitBreaker { return c.contactBreaker }

// ContentBreaker exposes the auth_content breaker (readiness, tests).
func (c *Client) ContentBreaker() port.CircuitBreaker { return c.contentBreaker }

// ---- port.ContactResolver ---------------------------------------------

type contactPayload struct {
	UserID        string  `json:"user_id"`
	Email         string  `json:"email"`
	EmailVerified bool    `json:"email_verified"`
	Locale        *string `json:"locale"`
}

// Contact resolves email + email_verified + locale, cache first
// (GET /internal/v1/auth/users/{id}/notification-contact).
func (c *Client) Contact(ctx context.Context, lane entity.Lane, userID uuid.UUID) (*entity.Contact, error) {
	if cached, err := c.cache.Get(ctx, userID); err == nil && cached != nil {
		c.cacheHit("hit")
		return cached, nil
	} else if err != nil && !stderrors.Is(err, errors.ErrCacheMiss) {
		// A broken cache must not stop sends; fall through to Auth.
		c.logger.Warn("contact cache read failed", zap.String("user_id", userID.String()), zap.Error(err))
	}
	c.cacheHit("miss")

	path := "/internal/v1/auth/users/" + userID.String() + "/notification-contact"
	var payload contactPayload
	if err := c.call(ctx, lane, DependencyContact, c.contactBreaker, http.MethodGet, path, &payload); err != nil {
		return nil, err
	}
	email, err := pii.NewEmail(payload.Email)
	if err != nil {
		// Auth returned an address we cannot use; retrying will not help.
		return nil, errors.NewPermanent(entity.ReasonNoRecipient, fmt.Errorf("auth contact: invalid email for user %s: %w", userID, errors.ErrNoRecipient))
	}
	contact := &entity.Contact{UserID: userID, Email: email, EmailVerified: payload.EmailVerified}
	if payload.Locale != nil {
		contact.Locale = *payload.Locale
	}
	if c.cacheTTL > 0 {
		if err := c.cache.Set(ctx, userID, contact, c.cacheTTL); err != nil {
			c.logger.Warn("contact cache write failed", zap.String("user_id", userID.String()), zap.Error(err))
		}
	}
	return contact, nil
}

type contentPayload struct {
	DeliveryID     string          `json:"delivery_id"`
	RecipientEmail string          `json:"recipient_email"`
	Template       string          `json:"template"`
	ExpiresAt      time.Time       `json:"expires_at"`
	TemplateData   json.RawMessage `json:"template_data"`
}

// Content resolves an Auth-initiated delivery
// (POST /internal/v1/auth/notification-deliveries/{id}/content). Never cached:
// template_data may hold an OTP or reset URL.
func (c *Client) Content(ctx context.Context, lane entity.Lane, authDeliveryID uuid.UUID) (*entity.AuthContent, error) {
	path := "/internal/v1/auth/notification-deliveries/" + authDeliveryID.String() + "/content"
	var payload contentPayload
	if err := c.call(ctx, lane, DependencyContent, c.contentBreaker, http.MethodPost, path, &payload); err != nil {
		return nil, err
	}
	email, err := pii.NewEmail(payload.RecipientEmail)
	if err != nil {
		return nil, errors.NewPermanent(entity.ReasonNoRecipient, fmt.Errorf("auth content: invalid recipient for delivery %s: %w", authDeliveryID, errors.ErrNoRecipient))
	}
	out := &entity.AuthContent{
		DeliveryID:   authDeliveryID,
		Recipient:    email,
		Template:     payload.Template,
		ExpiresAt:    payload.ExpiresAt,
		TemplateData: []byte(payload.TemplateData),
	}
	if id, err := uuid.Parse(payload.DeliveryID); err == nil {
		out.DeliveryID = id
	}
	return out, nil
}

// ForgetContact drops the cached contact (user.deleted).
func (c *Client) ForgetContact(ctx context.Context, userID uuid.UUID) error {
	return c.cache.Delete(ctx, userID)
}

// ---- transport ---------------------------------------------------------

// call performs one request through the lane's bulkhead, limiter and the
// dependency's breaker, classifies the outcome and decodes a 200 body into
// out. It never logs or returns the body.
func (c *Client) call(ctx context.Context, lane entity.Lane, dependency string, br port.CircuitBreaker, method, path string, out any) error {
	hc, ok := c.clients[lane]
	if !ok {
		hc = c.clients[entity.LaneTransactional]
	}
	if !br.Allow() {
		c.observe(dependency, lane, "breaker_open", 0)
		return errors.NewRetryable(fmt.Errorf("%s: %w", dependency, errors.ErrBreakerOpen))
	}
	if lim := c.limiters[lane]; lim != nil {
		if err := lim.wait(ctx); err != nil {
			return errors.NewRetryable(fmt.Errorf("%s: waiting for client rate limit: %w", dependency, err))
		}
	}

	req, err := http.NewRequestWithContext(ctx, method, c.cfg.BaseURL+path, http.NoBody)
	if err != nil {
		return errors.NewPermanent(entity.ReasonProviderError, fmt.Errorf("%s: build request: %w", dependency, err))
	}
	req.Header.Set("Accept", constants.ContentTypeJSON)
	req.Header.Set(constants.HeaderCacheControl, constants.NoStore)
	if c.cfg.ServiceIdentity != "" {
		req.Header.Set(constants.HeaderServiceIdentity, c.cfg.ServiceIdentity)
	}
	rid := reqctx.RequestID(ctx)
	if rid == "" {
		rid = uuid.NewString()
	}
	req.Header.Set(constants.HeaderRequestID, rid)
	if tp := reqctx.TraceParent(ctx); tp != "" {
		req.Header.Set("traceparent", tp)
	}

	start := c.clk.Now()
	resp, err := hc.Do(req)
	elapsed := c.clk.Now().Sub(start)
	if err != nil {
		status := "error"
		if isTimeout(err) {
			status = "timeout"
		}
		c.observe(dependency, lane, status, elapsed)
		br.Failure()
		c.logger.Warn("auth call failed", zap.String("dependency", dependency), zap.String("lane", string(lane)), zap.String("status", status), zap.String("request_id", rid), zap.Error(err))
		return errors.NewRetryable(fmt.Errorf("%s: %s: %w", dependency, status, err))
	}
	defer func() {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, maxBodyBytes))
		_ = resp.Body.Close()
	}()
	c.observe(dependency, lane, strconv.Itoa(resp.StatusCode), elapsed)

	switch {
	case resp.StatusCode == http.StatusOK:
		br.Success()
		body, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
		if err != nil {
			return errors.NewRetryable(fmt.Errorf("%s: read body: %w", dependency, err))
		}
		if err := json.Unmarshal(body, out); err != nil {
			// Do not include the body in the error: it holds PII/secret.
			return errors.NewRetryable(fmt.Errorf("%s: malformed 200 body", dependency))
		}
		return nil
	case resp.StatusCode == http.StatusNotFound:
		br.Success() // Auth answered; the recipient simply does not exist.
		return errors.NewPermanent(entity.ReasonNoRecipient, fmt.Errorf("%s: %w", dependency, errors.ErrNoRecipient))
	case resp.StatusCode == http.StatusGone:
		br.Success()
		return errors.NewPermanent(entity.ReasonContentExpired, fmt.Errorf("%s: %w", dependency, errors.ErrContentExpired))
	case resp.StatusCode == http.StatusTooManyRequests:
		// Auth is alive but throttling us: retry later, do not open the breaker.
		br.Success()
		r := &errors.Retryable{Cause: fmt.Errorf("%s: rate limited by auth (429)", dependency)}
		if ra := resp.Header.Get(constants.HeaderRetryAfter); ra != "" {
			if secs, err := strconv.Atoi(ra); err == nil && secs > 0 {
				r.RetryAfter = time.Duration(secs) * time.Second
			}
		}
		return r
	default:
		// 5xx, and unexpected 4xx (401/403 = misconfigured identity): the
		// dependency is unhealthy from our point of view.
		br.Failure()
		c.logger.Warn("auth call returned error status", zap.String("dependency", dependency), zap.String("lane", string(lane)), zap.Int("status", resp.StatusCode), zap.String("request_id", rid))
		return errors.NewRetryable(fmt.Errorf("%s: unexpected status %d", dependency, resp.StatusCode))
	}
}

func isTimeout(err error) bool {
	if stderrors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	if stderrors.As(err, &ne) && ne.Timeout() {
		return true
	}
	return false
}

// ---- metrics -----------------------------------------------------------

func (c *Client) observe(dependency string, lane entity.Lane, status string, d time.Duration) {
	if c.metrics == nil {
		return
	}
	c.metrics.DependencyDuration.WithLabelValues(dependency, string(lane), status).Observe(d.Seconds())
}

func (c *Client) cacheHit(result string) {
	if c.metrics == nil {
		return
	}
	c.metrics.ContactCacheHits.WithLabelValues(result).Inc()
}

func (c *Client) stateHook(dependency string) func(int) {
	return func(state int) { c.publishState(dependency, state) }
}

func (c *Client) publishState(dependency string, state int) {
	if c.metrics == nil {
		return
	}
	c.metrics.CircuitBreakerState.WithLabelValues(dependency).Set(float64(state))
}

var _ port.ContactResolver = (*Client)(nil)
