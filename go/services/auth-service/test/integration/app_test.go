//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/api/router"
	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
	"github.com/ecommerce/auth-service/internal/infrastructure/crypto"
	"github.com/ecommerce/auth-service/internal/infrastructure/kafka"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
	redisadapter "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

func testLogger() *zap.Logger { return zap.NewNop() }

// testApp is the service wired the way cmd/server wires it, with Kafka replaced
// by a logging publisher so the suite needs no broker. The internal listener
// runs without mTLS, as it does for local development.
type testApp struct {
	server      *httptest.Server
	internal    *httptest.Server
	client      *http.Client
	deliveries  port.DeliveryStore
	maintenance *service.MaintenanceService
	clock       *clock.Fixed
}

func newTestApp(t *testing.T) *testApp {
	t.Helper()
	resetState(t)

	ctx := context.Background()
	logger := testLogger()
	testClock := clock.NewFixed(time.Now().UTC())

	cfg := &config.Config{}
	cfg.App = config.AppConfig{Name: "auth-service", Env: "test"}
	cfg.JWT = config.JWTConfig{
		Issuer: "https://auth.test", Audience: "ecommerce-api", Algorithm: "RS256",
		AccessTokenTTL: 15 * time.Minute, ClockSkew: 30 * time.Second,
		KeyCacheTTL: time.Minute, JwksCacheControl: "public, max-age=600",
	}
	cfg.Refresh = config.RefreshConfig{
		SlidingTTL: 7 * 24 * time.Hour, AbsoluteTTL: 30 * 24 * time.Hour,
		CookieName: "auth_refresh", CookiePath: "/api/v1/auth/sessions",
		// httptest serves plain HTTP, so a Secure cookie would never be stored.
		CookieSecure: false,
	}
	generous := config.RateLimitRule{Limit: 1000, Window: time.Minute}
	cfg.Security.RateLimit = config.RateLimitConfig{
		LoginPerIP: generous, LoginPerAccount: generous, RegistrationPerIP: generous,
		OtpPerAccount: generous, PasswordResetPerIP: generous, RefreshPerIP: generous,
		InternalPerClient: generous,
	}
	cfg.Security.PasswordPolicy = config.PasswordPolicyConfig{MinLength: 12, MaxLength: 128}

	keyCipher, err := crypto.NewKeyCipher(testMasterKey())
	require.NoError(t, err)

	accounts := postgres.NewAccountRepository(testPool)
	refreshTokens := postgres.NewRefreshTokenRepository(testPool)
	jwtKeys := postgres.NewJwtKeyRepository(testPool)
	txManager := postgres.NewTxManager(testPool)

	keys := testKeys()
	revocation := redisadapter.NewRevocationStore(testRedis, keys)
	limiter := redisadapter.NewRateLimiter(testRedis, keys)
	otps := redisadapter.NewOtpStore(testRedis, keys)
	resets := redisadapter.NewResetTokenStore(testRedis, keys)
	deliveries := redisadapter.NewDeliveryStore(testRedis, keys, testClock)
	jwksNotifier := redisadapter.NewJwksNotifier(testRedis, keys)

	hasher := crypto.NewArgon2Hasher(config.Argon2Config{
		Memory: 16384, Iterations: 1, Parallelism: 1, SaltLength: 16, KeyLength: 32,
	})
	signer := crypto.NewSigner(jwtKeys, keyCipher, testClock, cfg.JWT.KeyCacheTTL)
	publisher := kafka.NewLoggingPublisher(logger)

	notifier := service.NewNotifier(deliveries, publisher, testClock, logger, 30*time.Minute)
	tokenService := service.NewTokenService(signer, testClock, service.TokenConfig{
		Issuer: cfg.JWT.Issuer, Audience: cfg.JWT.Audience,
		AccessTTL: cfg.JWT.AccessTokenTTL, ClockSkew: cfg.JWT.ClockSkew,
	})
	revoker := service.NewRevoker(refreshTokens, revocation, testClock, cfg.JWT.AccessTokenTTL, cfg.JWT.ClockSkew)

	policy := vo.NewPasswordPolicy(12, 128)
	otpPolicy := service.OtpPolicy{TTL: 5 * time.Minute, MaxAttempts: 5, ResendLimit: 3, ResendWindow: 15 * time.Minute}
	sessionPolicy := service.SessionPolicy{SlidingTTL: cfg.Refresh.SlidingTTL, AbsoluteTTL: cfg.Refresh.AbsoluteTTL}
	lockout := entity.LockoutPolicy{MaxFailedAttempts: 3, LockDuration: 15 * time.Minute, CounterResetAfter: time.Hour}
	rule := port.RateLimitRule{Limit: 1000, Window: time.Minute}

	lifecycle := service.NewKeyLifecycleService(jwtKeys, crypto.NewRSAKeyGenerator(2048, keyCipher),
		signer, jwksNotifier, testClock,
		service.KeyPolicy{RotationInterval: 720 * time.Hour, RetirementDelay: 24 * time.Hour}, logger)
	require.NoError(t, lifecycle.EnsureActiveKey(ctx))

	health := handler.NewHealthHandler(2 * time.Second)
	health.AddProbe("postgres", testPool.Ping)
	health.AddProbe("redis", func(probeCtx context.Context) error {
		return testRedis.Ping(probeCtx).Err()
	})

	cookie := httpx.NewCookieSettings(cfg.Refresh)
	engine := router.NewPublic(router.PublicDeps{
		Config:  cfg,
		Logger:  logger,
		Limiter: limiter,
		Registration: handler.NewRegistrationHandler(
			service.NewRegistrationService(accounts, otps, hasher, notifier, testClock, policy, otpPolicy, logger)),
		Session: handler.NewSessionHandler(
			service.NewAuthenticationService(accounts, refreshTokens, hasher, limiter, tokenService, notifier,
				testClock, lockout, sessionPolicy, rule, logger),
			service.NewSessionService(accounts, refreshTokens, tokenService, revoker, testClock, sessionPolicy, logger),
			cookie),
		Password: handler.NewPasswordHandler(
			service.NewPasswordService(accounts, resets, hasher, limiter, revoker, notifier, testClock, policy,
				service.ResetPolicy{TTL: 15 * time.Minute, URLTemplate: "https://app.test/reset-password#token={token}"},
				rule, logger),
			cookie),
		Account: handler.NewAccountHandler(
			service.NewEmailChangeService(accounts, otps, hasher, revoker, notifier, testClock, otpPolicy, logger),
			service.NewAccountDeletionService(accounts, refreshTokens, hasher, revoker, notifier, txManager, testClock, logger),
			cookie),
		Jwks:   handler.NewJwksHandler(service.NewJwksService(signer), cfg.JWT.JwksCacheControl),
		Health: health,
	})

	server := httptest.NewServer(engine)
	t.Cleanup(server.Close)

	internal := httptest.NewServer(router.NewInternal(router.InternalDeps{
		Config:   cfg,
		Logger:   logger,
		Limiter:  limiter,
		Delivery: handler.NewDeliveryHandler(service.NewDeliveryService(deliveries)),
		Contact:  handler.NewContactHandler(service.NewContactService(accounts)),
		Health:   health,
	}))
	t.Cleanup(internal.Close)

	jar, err := cookiejar.New(nil)
	require.NoError(t, err)

	return &testApp{
		server:      server,
		internal:    internal,
		client:      &http.Client{Jar: jar},
		deliveries:  deliveries,
		maintenance: service.NewMaintenanceService(accounts, refreshTokens, notifier, testClock, logger),
		clock:       testClock,
	}
}

// response is one HTTP exchange, decoded for assertions.
type response struct {
	Status  int
	Body    string
	Headers http.Header
}

// JSON decodes the body into dst.
func (r response) JSON(t *testing.T, dst any) {
	t.Helper()
	require.NoError(t, json.Unmarshal([]byte(r.Body), dst), "body: %s", r.Body)
}

// Code returns the stable error code of a problem response.
func (r response) Code(t *testing.T) string {
	t.Helper()
	var problem struct {
		Code string `json:"code"`
	}
	r.JSON(t, &problem)
	return problem.Code
}

func (a *testApp) post(t *testing.T, path string, body any, headers map[string]string) response {
	t.Helper()
	return a.do(t, a.request(t, http.MethodPost, path, body, headers), a.client)
}

func (a *testApp) put(t *testing.T, path string, body any, headers map[string]string) response {
	t.Helper()
	return a.do(t, a.request(t, http.MethodPut, path, body, headers), a.client)
}

func (a *testApp) get(t *testing.T, path string, headers map[string]string) response {
	t.Helper()
	return a.do(t, a.request(t, http.MethodGet, path, nil, headers), a.client)
}

// getInternal calls the cluster-internal listener, the way Notification Service
// does, without the public cookie jar.
func (a *testApp) getInternal(t *testing.T, path string) response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, a.internal.URL+path, nil)
	require.NoError(t, err)
	return a.do(t, req, &http.Client{})
}

func (a *testApp) delete(t *testing.T, path string, headers map[string]string) response {
	t.Helper()
	return a.do(t, a.request(t, http.MethodDelete, path, nil, headers), a.client)
}

// deleteWithoutJar sends a request without the shared cookie jar, which is how
// another device revokes a remote session.
func (a *testApp) deleteWithoutJar(t *testing.T, path string, headers map[string]string) response {
	t.Helper()
	return a.do(t, a.request(t, http.MethodDelete, path, nil, headers), &http.Client{})
}

// postWithoutJar sends a request with an explicit cookie, bypassing the shared
// jar, which is how a replayed credential is simulated.
func (a *testApp) postWithoutJar(t *testing.T, path string, cookie string) response {
	t.Helper()
	req := a.request(t, http.MethodPost, path, nil, nil)
	if cookie != "" {
		req.Header.Set("Cookie", cookie)
	}
	return a.do(t, req, &http.Client{})
}

func (a *testApp) request(t *testing.T, method, path string, body any, headers map[string]string) *http.Request {
	t.Helper()

	var payload io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		require.NoError(t, err)
		payload = bytes.NewReader(encoded)
	}

	req, err := http.NewRequest(method, a.server.URL+path, payload)
	require.NoError(t, err)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	return req
}

func (a *testApp) do(t *testing.T, req *http.Request, client *http.Client) response {
	t.Helper()

	resp, err := client.Do(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)

	return response{Status: resp.StatusCode, Body: string(body), Headers: resp.Header}
}
