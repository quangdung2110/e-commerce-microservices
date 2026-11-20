//go:build integration

package integration_test

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/api/handler"
	"github.com/ecommerce/user-service/internal/api/router"
	"github.com/ecommerce/user-service/internal/common/clock"
	"github.com/ecommerce/user-service/internal/common/config"
	"github.com/ecommerce/user-service/internal/common/keybuilder"
	"github.com/ecommerce/user-service/internal/domain/service"
	"github.com/ecommerce/user-service/internal/infrastructure/postgres"
	redisadapter "github.com/ecommerce/user-service/internal/infrastructure/redis"
)

// internalFixture is the checkout snapshot listener wired to the real adapters.
type internalFixture struct {
	engine    *gin.Engine
	addresses *service.AddressService
}

func newInternalFixture(t *testing.T, perUser int) *internalFixture {
	t.Helper()
	gin.SetMode(gin.TestMode)

	logger := zap.NewNop()
	keys := keybuilder.New(testRedisConf.CachePrefix, testRedisConf.StatePrefix)
	cache := redisadapter.NewAddressCache(testRedis, keys, 12*time.Hour, 15*time.Minute, logger)

	addresses := service.NewAddressService(
		postgres.NewAddressRepository(testPool),
		postgres.NewProfileRepository(testPool),
		cache,
		postgres.NewTxManager(testPool),
		clock.System{},
		logger,
	)

	cfg := &config.Config{}
	cfg.App.Env = "test"
	cfg.Internal.AllowedCallers = []string{"checkout-service"}
	cfg.Security.RateLimit.InternalPerUser = config.RateLimitRule{Limit: perUser, Window: time.Minute}

	return &internalFixture{
		addresses: addresses,
		engine: router.NewInternal(router.InternalDeps{
			Config:   cfg,
			Logger:   logger,
			Limiter:  redisadapter.NewRateLimiter(testRedis, keys),
			Checkout: handler.NewCheckoutHandler(addresses),
			Health:   handler.NewHealthHandler(2 * time.Second),
		}),
	}
}

func (f *internalFixture) snapshot(userID, addressID uuid.UUID, caller string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(http.MethodGet,
		"/internal/v1/users/"+userID.String()+"/addresses/"+addressID.String()+"/checkout-snapshot", nil)
	if caller != "" {
		request.Header.Set("X-Service-Identity", caller)
	}
	recorder := httptest.NewRecorder()
	f.engine.ServeHTTP(recorder, request)
	return recorder
}

// seedAddress bootstraps a user with one address through the public flow.
func seedAddress(t *testing.T) (uuid.UUID, uuid.UUID) {
	t.Helper()

	api := newAPIFixture(t)
	userID := api.bootstrap(t)
	created := api.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID,
		"application/json", addressBody())
	require.Equal(t, http.StatusCreated, created.Code)
	return userID, uuid.MustParse(decodeBody(t, created)["id"].(string))
}

func TestInternalSnapshotNeedsAServiceIdentity(t *testing.T) {
	resetState(t)
	f := newInternalFixture(t, 100)
	userID, addressID := seedAddress(t)

	missing := f.snapshot(userID, addressID, "")
	require.Equal(t, http.StatusUnauthorized, missing.Code)
	require.Equal(t, "UNAUTHORIZED", decodeBody(t, missing)["code"])

	stranger := f.snapshot(userID, addressID, "order-service")
	require.Equal(t, http.StatusForbidden, stranger.Code,
		"an identity outside the allowlist is refused even if the mesh let it through")
	require.Equal(t, "FORBIDDEN", decodeBody(t, stranger)["code"])
}

func TestInternalSnapshotServesTheCheckoutService(t *testing.T) {
	resetState(t)
	f := newInternalFixture(t, 100)
	userID, addressID := seedAddress(t)

	recorder := f.snapshot(userID, addressID, "checkout-service")

	require.Equal(t, http.StatusOK, recorder.Code)
	require.Equal(t, "private, no-store", recorder.Header().Get("Cache-Control"))
	body := decodeBody(t, recorder)
	require.Equal(t, addressID.String(), body["address_id"])
	require.Equal(t, "+84901234567", body["recipient_phone"])
	require.Equal(t, "79", body["province_code"])
	require.NotContains(t, body, "is_default", "the snapshot carries shipping data only")
}

func TestInternalSnapshotOfAnotherUsersAddressIs404(t *testing.T) {
	resetState(t)
	f := newInternalFixture(t, 100)
	_, addressID := seedAddress(t)
	stranger, _ := seedAddress(t)

	recorder := f.snapshot(stranger, addressID, "checkout-service")

	require.Equal(t, http.StatusNotFound, recorder.Code)
	require.Equal(t, "ADDRESS_NOT_FOUND", decodeBody(t, recorder)["code"], "ownership is never disclosed")
}

func TestInternalRateLimitIsPerCallerAndUserNotPerIP(t *testing.T) {
	resetState(t)
	f := newInternalFixture(t, 2)
	userID, addressID := seedAddress(t)
	otherUser, otherAddress := seedAddress(t)

	require.Equal(t, http.StatusOK, f.snapshot(userID, addressID, "checkout-service").Code)
	require.Equal(t, http.StatusOK, f.snapshot(userID, addressID, "checkout-service").Code)

	limited := f.snapshot(userID, addressID, "checkout-service")
	require.Equal(t, http.StatusTooManyRequests, limited.Code)
	require.Equal(t, "RATE_LIMITED", decodeBody(t, limited)["code"])

	// Every request here comes from the same client address; only the user in
	// the path decides which budget is spent.
	require.Equal(t, http.StatusOK, f.snapshot(otherUser, otherAddress, "checkout-service").Code,
		"one noisy user must not spend the budget of the others")
}
