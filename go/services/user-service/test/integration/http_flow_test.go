//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"encoding/json"
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

// apiFixture is the public API wired to the real adapters.
type apiFixture struct {
	engine    *gin.Engine
	publisher *recordingPublisher
	lifecycle *service.LifecycleService
}

func newAPIFixture(t *testing.T) *apiFixture {
	t.Helper()
	gin.SetMode(gin.TestMode)

	logger := zap.NewNop()
	systemClock := clock.System{}
	keys := keybuilder.New(testRedisConf.CachePrefix, testRedisConf.StatePrefix)

	profiles := postgres.NewProfileRepository(testPool)
	addresses := postgres.NewAddressRepository(testPool)
	preferences := postgres.NewPreferencesRepository(testPool)
	deletions := postgres.NewDeletionRepository(testPool)
	txManager := postgres.NewTxManager(testPool)

	cache := redisadapter.NewAddressCache(testRedis, keys, 12*time.Hour, 15*time.Minute, logger)
	limiter := redisadapter.NewRateLimiter(testRedis, keys)
	publisher := &recordingPublisher{}
	emitter := service.NewEventEmitter(publisher, logger)

	cfg := &config.Config{}
	cfg.App.Env = "test"
	cfg.Security.RateLimit.ReadPerIdentity = config.RateLimitRule{Limit: 1000, Window: time.Minute}
	cfg.Security.RateLimit.WritePerIdentity = config.RateLimitRule{Limit: 1000, Window: time.Minute}

	addressService := service.NewAddressService(addresses, profiles, cache, txManager, systemClock, logger)

	engine := router.NewPublic(router.PublicDeps{
		Config:  cfg,
		Logger:  logger,
		Limiter: limiter,
		Profile: handler.NewProfileHandler(
			service.NewProfileService(profiles, txManager, emitter, systemClock)),
		Address: handler.NewAddressHandler(addressService),
		Preferences: handler.NewPreferencesHandler(
			service.NewPreferencesService(preferences, txManager, emitter, systemClock)),
		Health: handler.NewHealthHandler(2 * time.Second),
	})

	return &apiFixture{
		engine:    engine,
		publisher: publisher,
		lifecycle: service.NewLifecycleService(
			profiles, addresses, preferences, deletions, cache, txManager, systemClock, logger),
	}
}

// bootstrap creates a verified user the way the Auth consumer would.
func (f *apiFixture) bootstrap(t *testing.T) uuid.UUID {
	t.Helper()

	userID := uuid.New()
	_, err := f.lifecycle.Bootstrap(context.Background(), userID, time.Now().UTC())
	require.NoError(t, err)
	return userID
}

// do sends a request carrying the identity the gateway would have injected.
func (f *apiFixture) do(t *testing.T, method, path string, userID uuid.UUID, contentType string, body any) *httptest.ResponseRecorder {
	t.Helper()

	var reader *bytes.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		require.NoError(t, err)
		reader = bytes.NewReader(payload)
	} else {
		reader = bytes.NewReader(nil)
	}

	request := httptest.NewRequest(method, path, reader)
	if userID != uuid.Nil {
		request.Header.Set("X-User-Id", userID.String())
	}
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}

	recorder := httptest.NewRecorder()
	f.engine.ServeHTTP(recorder, request)
	return recorder
}

func decodeBody(t *testing.T, recorder *httptest.ResponseRecorder) map[string]any {
	t.Helper()

	var body map[string]any
	require.NoError(t, json.Unmarshal(recorder.Body.Bytes(), &body))
	return body
}

func addressBody() map[string]any {
	return map[string]any{
		"recipient_name":  "Nguyen Van A",
		"recipient_phone": "+84901234567",
		"address_line":    "12 Nguyen Hue",
		"province_code":   "79",
		"district_code":   "760",
		"ward_code":       "26734",
	}
}

func TestRequestWithoutGatewayIdentityIs401(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)

	recorder := f.do(t, http.MethodGet, "/api/v1/users/me/profile", uuid.Nil, "", nil)

	require.Equal(t, http.StatusUnauthorized, recorder.Code)
	require.Equal(t, "application/problem+json", recorder.Header().Get("Content-Type"))
	body := decodeBody(t, recorder)
	require.Equal(t, "UNAUTHORIZED", body["code"])
}

func TestProfileReadAndMergePatch(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	recorder := f.do(t, http.MethodGet, "/api/v1/users/me/profile", userID, "", nil)
	require.Equal(t, http.StatusOK, recorder.Code)
	require.Equal(t, "private, no-store", recorder.Header().Get("Cache-Control"))

	body := decodeBody(t, recorder)
	require.Equal(t, userID.String(), body["id"])
	require.Nil(t, body["first_name"])
	require.Equal(t, "Người dùng", body["display_name"], "a nameless profile still has a display name")
	require.Equal(t, "UNKNOWN", body["gender"])

	recorder = f.do(t, http.MethodPatch, "/api/v1/users/me/profile", userID,
		"application/merge-patch+json", map[string]any{
			"first_name":    "Nguyen",
			"last_name":     "Van A",
			"date_of_birth": "1995-04-01",
			"gender":        "MALE",
		})
	require.Equal(t, http.StatusOK, recorder.Code)

	body = decodeBody(t, recorder)
	require.Equal(t, "Nguyen Van A", body["display_name"])
	require.Equal(t, "1995-04-01", body["date_of_birth"])
	require.Equal(t, "MALE", body["gender"])

	// Clearing a nullable member is what merge-patch null means.
	recorder = f.do(t, http.MethodPatch, "/api/v1/users/me/profile", userID,
		"application/merge-patch+json", map[string]any{"date_of_birth": nil})
	require.Equal(t, http.StatusOK, recorder.Code)
	require.Nil(t, decodeBody(t, recorder)["date_of_birth"])
}

func TestProfilePatchRejectsAReadOnlyField(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	recorder := f.do(t, http.MethodPatch, "/api/v1/users/me/profile", userID,
		"application/merge-patch+json", map[string]any{"display_name": "Somebody Else"})

	// additionalProperties:false plus a read-only field: the strict decoder
	// refuses it instead of ignoring it.
	require.Equal(t, http.StatusBadRequest, recorder.Code)
	require.Equal(t, "VALIDATION_FAILED", decodeBody(t, recorder)["code"])
}

func TestProfilePatchRejectsTheWrongMediaType(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	recorder := f.do(t, http.MethodPatch, "/api/v1/users/me/profile", userID,
		"application/json", map[string]any{"first_name": "Nguyen"})

	require.Equal(t, http.StatusBadRequest, recorder.Code)
}

func TestProfilePatchRejectsAFutureDateOfBirth(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	tomorrow := time.Now().UTC().Add(48 * time.Hour).Format("2006-01-02")
	recorder := f.do(t, http.MethodPatch, "/api/v1/users/me/profile", userID,
		"application/merge-patch+json", map[string]any{"date_of_birth": tomorrow})

	require.Equal(t, http.StatusUnprocessableEntity, recorder.Code)
	body := decodeBody(t, recorder)
	require.Equal(t, "INVALID_PROFILE_FIELD", body["code"])
	require.NotEmpty(t, body["errors"])
}

func TestProfileOfAnUnknownUserIs404(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)

	recorder := f.do(t, http.MethodGet, "/api/v1/users/me/profile", uuid.New(), "", nil)

	require.Equal(t, http.StatusNotFound, recorder.Code)
	require.Equal(t, "PROFILE_NOT_FOUND", decodeBody(t, recorder)["code"])
}

func TestAddressBookLifecycleOverHTTP(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	empty := f.do(t, http.MethodGet, "/api/v1/users/me/addresses", userID, "", nil)
	require.Equal(t, http.StatusOK, empty.Code)
	require.Empty(t, decodeBody(t, empty)["addresses"])

	created := f.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID,
		"application/json", addressBody())
	require.Equal(t, http.StatusCreated, created.Code)

	first := decodeBody(t, created)
	require.True(t, first["is_default"].(bool), "the first address becomes the default")
	firstID := first["id"].(string)
	require.Equal(t, "/api/v1/users/me/addresses/"+firstID, created.Header().Get("Location"))

	second := f.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID,
		"application/json", addressBody())
	require.Equal(t, http.StatusCreated, second.Code)
	secondID := decodeBody(t, second)["id"].(string)
	require.False(t, decodeBody(t, second)["is_default"].(bool))

	// The default cannot be removed while it is the default.
	conflict := f.do(t, http.MethodDelete, "/api/v1/users/me/addresses/"+firstID, userID, "", nil)
	require.Equal(t, http.StatusConflict, conflict.Code)
	require.Equal(t, "DEFAULT_ADDRESS_CANNOT_BE_DELETED", decodeBody(t, conflict)["code"])

	promoted := f.do(t, http.MethodPut,
		"/api/v1/users/me/addresses/"+secondID+"/default", userID, "", nil)
	require.Equal(t, http.StatusOK, promoted.Code)
	require.True(t, decodeBody(t, promoted)["is_default"].(bool))

	deleted := f.do(t, http.MethodDelete, "/api/v1/users/me/addresses/"+firstID, userID, "", nil)
	require.Equal(t, http.StatusNoContent, deleted.Code)

	list := f.do(t, http.MethodGet, "/api/v1/users/me/addresses", userID, "", nil)
	require.Equal(t, http.StatusOK, list.Code)
	remaining := decodeBody(t, list)["addresses"].([]any)
	require.Len(t, remaining, 1)
	require.Equal(t, secondID, remaining[0].(map[string]any)["id"])
}

func TestTheOnlyAddressCanBeDeletedOverHTTP(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	created := f.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID,
		"application/json", addressBody())
	require.Equal(t, http.StatusCreated, created.Code)
	addressID := decodeBody(t, created)["id"].(string)

	deleted := f.do(t, http.MethodDelete, "/api/v1/users/me/addresses/"+addressID, userID, "", nil)
	require.Equal(t, http.StatusNoContent, deleted.Code, "USER-BR-004 only applies while a replacement exists")

	list := f.do(t, http.MethodGet, "/api/v1/users/me/addresses", userID, "", nil)
	require.Equal(t, http.StatusOK, list.Code)
	require.Empty(t, decodeBody(t, list)["addresses"])
}

func TestADeletedAccountIsNotFoundOnEveryOwnerRoute(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	require.Equal(t, http.StatusOK, f.do(t, http.MethodPatch, "/api/v1/users/me/profile", userID,
		"application/merge-patch+json", map[string]any{"first_name": "Nguyen"}).Code)
	require.Equal(t, http.StatusCreated, f.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID,
		"application/json", addressBody()).Code)
	eventsBefore := len(f.publisher.events())

	_, err := f.lifecycle.Anonymize(context.Background(), userID,
		time.Now().UTC(), time.Now().UTC().Add(90*24*time.Hour), "GDPR_REQUEST")
	require.NoError(t, err)

	// The gateway's revocation can lag or fail, so the service itself must refuse
	// an owner whose account is already anonymized (USER-INV-005).
	for _, probe := range []struct {
		method      string
		path        string
		contentType string
		body        any
	}{
		{http.MethodGet, "/api/v1/users/me/profile", "", nil},
		{http.MethodPatch, "/api/v1/users/me/profile", "application/merge-patch+json", map[string]any{"first_name": "Again"}},
		{http.MethodGet, "/api/v1/users/me/preferences", "", nil},
		{http.MethodGet, "/api/v1/users/me/addresses", "", nil},
		{http.MethodPost, "/api/v1/users/me/addresses", "application/json", addressBody()},
	} {
		recorder := f.do(t, probe.method, probe.path, userID, probe.contentType, probe.body)
		require.Equal(t, http.StatusNotFound, recorder.Code, probe.method+" "+probe.path)
		require.Equal(t, "PROFILE_NOT_FOUND", decodeBody(t, recorder)["code"], probe.method+" "+probe.path)
	}

	var firstName *string
	require.NoError(t, testPool.QueryRow(context.Background(),
		`SELECT first_name FROM "user".user_profiles WHERE id = $1`, userID).Scan(&firstName))
	require.Nil(t, firstName, "the late PATCH did not write the name back")

	var addresses int
	require.NoError(t, testPool.QueryRow(context.Background(),
		`SELECT count(*) FROM "user".user_addresses WHERE user_id = $1`, userID).Scan(&addresses))
	require.Zero(t, addresses, "the late POST did not create an address")
	require.Len(t, f.publisher.events(), eventsBefore, "and no profile event followed")
}

func TestAddressListIsServedFromTheCacheAfterAMiss(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	require.Equal(t, http.StatusCreated,
		f.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID, "application/json", addressBody()).Code)

	// The create invalidated the key; this read refills it.
	require.Equal(t, http.StatusOK,
		f.do(t, http.MethodGet, "/api/v1/users/me/addresses", userID, "", nil).Code)

	exists, err := testRedis.Exists(ctx, "user:caches:addresses:"+userID.String()).Result()
	require.NoError(t, err)
	require.Equal(t, int64(1), exists)

	// A mutation drops it again, so the next reader cannot see a stale list.
	require.Equal(t, http.StatusCreated,
		f.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID, "application/json", addressBody()).Code)

	exists, err = testRedis.Exists(ctx, "user:caches:addresses:"+userID.String()).Result()
	require.NoError(t, err)
	require.Equal(t, int64(0), exists)
}

func TestAddressOfAnotherOwnerIs404OverHTTP(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	owner := f.bootstrap(t)
	stranger := f.bootstrap(t)

	created := f.do(t, http.MethodPost, "/api/v1/users/me/addresses", owner,
		"application/json", addressBody())
	require.Equal(t, http.StatusCreated, created.Code)
	addressID := decodeBody(t, created)["id"].(string)

	for _, probe := range []struct {
		method string
		path   string
		body   any
	}{
		{http.MethodPut, "/api/v1/users/me/addresses/" + addressID, addressBody()},
		{http.MethodPut, "/api/v1/users/me/addresses/" + addressID + "/default", nil},
		{http.MethodDelete, "/api/v1/users/me/addresses/" + addressID, nil},
	} {
		contentType := ""
		if probe.body != nil {
			contentType = "application/json"
		}
		recorder := f.do(t, probe.method, probe.path, stranger, contentType, probe.body)
		require.Equal(t, http.StatusNotFound, recorder.Code, probe.method+" "+probe.path)
		require.Equal(t, "ADDRESS_NOT_FOUND", decodeBody(t, recorder)["code"],
			"ownership is never disclosed")
	}
}

func TestInvalidAddressIs422WithFieldErrors(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	payload := addressBody()
	payload["recipient_phone"] = "0901234567"

	recorder := f.do(t, http.MethodPost, "/api/v1/users/me/addresses", userID,
		"application/json", payload)

	require.Equal(t, http.StatusUnprocessableEntity, recorder.Code)
	body := decodeBody(t, recorder)
	require.Equal(t, "INVALID_ADDRESS", body["code"])

	errorsList := body["errors"].([]any)
	require.Len(t, errorsList, 1)
	require.Equal(t, "recipient_phone", errorsList[0].(map[string]any)["field"])
}

func TestPreferencesReadAndReplace(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	recorder := f.do(t, http.MethodGet, "/api/v1/users/me/preferences", userID, "", nil)
	require.Equal(t, http.StatusOK, recorder.Code)

	body := decodeBody(t, recorder)
	require.Equal(t, "vi", body["language"])
	require.Equal(t, "VND", body["currency"])
	require.True(t, body["email_enabled"].(bool))
	require.True(t, body["in_app_enabled"].(bool))
	require.False(t, body["marketing_enabled"].(bool), "marketing is opt-in until the owner says otherwise")

	recorder = f.do(t, http.MethodPut, "/api/v1/users/me/preferences", userID,
		"application/json", map[string]any{
			"email_enabled":     true,
			"in_app_enabled":    true,
			"marketing_enabled": true,
		})
	require.Equal(t, http.StatusOK, recorder.Code)
	require.True(t, decodeBody(t, recorder)["marketing_enabled"].(bool))

	// Sending the same body again is idempotent.
	recorder = f.do(t, http.MethodPut, "/api/v1/users/me/preferences", userID,
		"application/json", map[string]any{
			"email_enabled":     true,
			"in_app_enabled":    true,
			"marketing_enabled": true,
		})
	require.Equal(t, http.StatusOK, recorder.Code)
}

func TestPreferencesRejectAReadOnlyField(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	recorder := f.do(t, http.MethodPut, "/api/v1/users/me/preferences", userID,
		"application/json", map[string]any{
			"language":          "en",
			"email_enabled":     true,
			"in_app_enabled":    true,
			"marketing_enabled": true,
		})

	require.Equal(t, http.StatusBadRequest, recorder.Code, "language and currency are read-only")
}

func TestPreferencesForAnUnknownUserIs404(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)

	recorder := f.do(t, http.MethodPut, "/api/v1/users/me/preferences", uuid.New(),
		"application/json", map[string]any{
			"email_enabled":     true,
			"in_app_enabled":    true,
			"marketing_enabled": true,
		})

	// WF-USER-005 prefers 404 over recreating a profile that may have been
	// deleted.
	require.Equal(t, http.StatusNotFound, recorder.Code)
	require.Equal(t, "PROFILE_NOT_FOUND", decodeBody(t, recorder)["code"])
}

func TestEventsArePublishedAfterCommit(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	require.Equal(t, http.StatusOK, f.do(t, http.MethodPatch, "/api/v1/users/me/profile", userID,
		"application/merge-patch+json", map[string]any{"first_name": "Nguyen"}).Code)
	require.Equal(t, http.StatusOK, f.do(t, http.MethodPut, "/api/v1/users/me/preferences", userID,
		"application/json", map[string]any{
			"email_enabled":     false,
			"in_app_enabled":    true,
			"marketing_enabled": true,
		}).Code)

	events := f.publisher.events()
	require.Len(t, events, 2)
	require.Equal(t, "com.ecommerce.user.profile-updated.v1", events[0].Type)
	require.Equal(t, "com.ecommerce.user.preferences-updated.v1", events[1].Type)
	for _, envelope := range events {
		require.Equal(t, userID.String(), envelope.Key)
	}
}

func TestUnknownRouteAndMethodUseProblemJSON(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)
	userID := f.bootstrap(t)

	missing := f.do(t, http.MethodGet, "/api/v1/users/me/unknown", userID, "", nil)
	require.Equal(t, http.StatusNotFound, missing.Code)
	require.Equal(t, "NOT_FOUND", decodeBody(t, missing)["code"])

	wrongMethod := f.do(t, http.MethodPost, "/api/v1/users/me/profile", userID,
		"application/json", map[string]any{})
	require.Equal(t, http.StatusMethodNotAllowed, wrongMethod.Code)
	require.Equal(t, "METHOD_NOT_ALLOWED", decodeBody(t, wrongMethod)["code"])
}

func TestHealthEndpoints(t *testing.T) {
	resetState(t)
	f := newAPIFixture(t)

	live := f.do(t, http.MethodGet, "/health/live", uuid.Nil, "", nil)
	require.Equal(t, http.StatusOK, live.Code)
	require.Equal(t, "UP", decodeBody(t, live)["status"])
}
