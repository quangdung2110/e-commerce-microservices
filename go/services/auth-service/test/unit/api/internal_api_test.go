// Package api_test exercises the internal HTTP engine end to end over httptest,
// with in-memory doubles behind the handlers. It covers what the service unit
// tests cannot: the route, the JSON field names of the contract, the no-store
// header, the problem mapping and the middleware chain of the internal
// listener.
package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
	"go.uber.org/zap/zaptest/observer"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/router"
	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

var testNow = time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC)

const (
	testEmail    = "Owner@Example.com"
	notification = "notification-service"
)

func contactPath(id string) string {
	return "/internal/v1/auth/users/" + id + "/notification-contact"
}

// ---------------------------------------------------------------- doubles --

// stubAccountRepo answers GetByID only; the internal API never writes.
type stubAccountRepo struct {
	accounts map[uuid.UUID]*entity.Account
}

func newStubAccountRepo() *stubAccountRepo {
	return &stubAccountRepo{accounts: map[uuid.UUID]*entity.Account{}}
}

func (r *stubAccountRepo) put(account *entity.Account) { r.accounts[account.ID] = account }

func (r *stubAccountRepo) GetByID(_ context.Context, id uuid.UUID) (*entity.Account, error) {
	account, ok := r.accounts[id]
	if !ok {
		return nil, apperr.ErrAccountNotFound
	}
	return account, nil
}

func (r *stubAccountRepo) Create(context.Context, *entity.Account) error { return apperr.ErrInternal }
func (r *stubAccountRepo) GetByNormalizedEmail(context.Context, string) (*entity.Account, error) {
	return nil, apperr.ErrInternal
}
func (r *stubAccountRepo) Update(context.Context, *entity.Account) error { return apperr.ErrInternal }
func (r *stubAccountRepo) ExistsByNormalizedEmail(context.Context, string) (bool, error) {
	return false, apperr.ErrInternal
}
func (r *stubAccountRepo) ListExpiredLocks(context.Context, time.Time, int) ([]*entity.Account, error) {
	return nil, apperr.ErrInternal
}
func (r *stubAccountRepo) ListDueForHardDelete(context.Context, time.Time, int) ([]uuid.UUID, error) {
	return nil, apperr.ErrInternal
}
func (r *stubAccountRepo) HardDelete(context.Context, []uuid.UUID) (int64, error) {
	return 0, apperr.ErrInternal
}

// stubLimiter records the scopes and subjects it was asked about and denies on
// demand.
type stubLimiter struct {
	scopes   []string
	subjects []string
	deny     bool
}

func (l *stubLimiter) Allow(_ context.Context, scope, subject string, rule port.RateLimitRule) (port.RateLimitResult, error) {
	l.scopes = append(l.scopes, scope)
	l.subjects = append(l.subjects, subject)
	return port.RateLimitResult{Allowed: !l.deny, RetryAfter: rule.Window}, nil
}

// stubDeliveryStore keeps the delivery endpoint wired without exercising it.
type stubDeliveryStore struct{}

func (stubDeliveryStore) Put(context.Context, *entity.NotificationDelivery, time.Duration) error {
	return nil
}

func (stubDeliveryStore) Get(context.Context, uuid.UUID) (*entity.NotificationDelivery, error) {
	return nil, apperr.ErrDeliveryNotFound
}

var (
	_ port.AccountRepository = (*stubAccountRepo)(nil)
	_ port.RateLimiter       = (*stubLimiter)(nil)
	_ port.DeliveryStore     = stubDeliveryStore{}
)

// ---------------------------------------------------------------- harness --

type internalApp struct {
	engine   *gin.Engine
	accounts *stubAccountRepo
	limiter  *stubLimiter
	logs     *observer.ObservedLogs
}

// newInternalApp builds the internal engine the way cmd/server does. Caller
// admission is the X-Service-Identity allowlist; the mesh, which enforces the
// real principal check, is not part of these tests.
func newInternalApp(t *testing.T) *internalApp {
	t.Helper()
	gin.SetMode(gin.TestMode)

	core, logs := observer.New(zap.DebugLevel)
	cfg := &config.Config{}
	cfg.App = config.AppConfig{Name: "auth-service", Env: "test"}
	cfg.Security.RateLimit.InternalPerResource = config.RateLimitRule{Limit: 120, Window: time.Minute}
	cfg.Internal = config.InternalConfig{AllowedCallers: []string{notification}}

	accounts := newStubAccountRepo()
	limiter := &stubLimiter{}
	engine := router.NewInternal(router.InternalDeps{
		Config:   cfg,
		Logger:   zap.New(core),
		Limiter:  limiter,
		Delivery: handler.NewDeliveryHandler(service.NewDeliveryService(stubDeliveryStore{})),
		Contact:  handler.NewContactHandler(service.NewContactService(accounts)),
		Health:   handler.NewHealthHandler(time.Second),
	})

	return &internalApp{engine: engine, accounts: accounts, limiter: limiter, logs: logs}
}

// verifiedAccount stores an ACTIVE account and returns it.
func (a *internalApp) verifiedAccount(t *testing.T) *entity.Account {
	t.Helper()
	email, err := vo.NewEmail(testEmail)
	require.NoError(t, err)
	account := entity.NewAccount(email, "hashed:password", testNow)
	require.NoError(t, account.Activate(testNow))
	a.accounts.put(account)
	return account
}

// get performs a request as the named calling service; an empty caller sends no
// X-Service-Identity header.
func (a *internalApp) get(t *testing.T, path, caller string) *httptest.ResponseRecorder {
	t.Helper()
	return a.do(t, http.MethodGet, path, caller)
}

func (a *internalApp) do(t *testing.T, method, path, caller string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	if caller != "" {
		req.Header.Set(constants.HeaderServiceIdentity, caller)
	}
	rec := httptest.NewRecorder()
	a.engine.ServeHTTP(rec, req)
	return rec
}

func problemCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	require.Equal(t, constants.ContentTypeProblem, rec.Header().Get("Content-Type"))
	var problem struct {
		Code string `json:"code"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &problem), "body: %s", rec.Body.String())
	return problem.Code
}

// ------------------------------------------------------------------ tests --

// The 200 body carries exactly the four contract fields, locale serialized as
// null, and the response is marked no-store.
func TestNotificationContactReturnsTheContractPayload(t *testing.T) {
	app := newInternalApp(t)
	account := app.verifiedAccount(t)

	rec := app.get(t, contactPath(account.ID.String()), notification)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	require.Equal(t, constants.NoStore, rec.Header().Get(constants.HeaderCacheControl))
	require.Contains(t, rec.Header().Get("Content-Type"), "application/json")

	var body map[string]json.RawMessage
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	require.ElementsMatch(t, []string{"user_id", "email", "email_verified", "locale"}, keysOf(body))
	require.JSONEq(t, `"`+account.ID.String()+`"`, string(body["user_id"]))
	require.JSONEq(t, `"`+testEmail+`"`, string(body["email"]))
	require.JSONEq(t, `true`, string(body["email_verified"]))
	require.JSONEq(t, `null`, string(body["locale"]))

	require.Equal(t, []string{"internal-contact"}, app.limiter.scopes)
	require.Equal(t, []string{notification + ":" + account.ID.String()}, app.limiter.subjects,
		"the budget belongs to the (calling service, user) pair, not to the client IP")
}

func TestNotificationContactOfAPendingAccountIsUnverified(t *testing.T) {
	app := newInternalApp(t)
	email, err := vo.NewEmail(testEmail)
	require.NoError(t, err)
	pending := entity.NewAccount(email, "hashed:password", testNow)
	app.accounts.put(pending)

	rec := app.get(t, contactPath(pending.ID.String()), notification)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())

	var body struct {
		EmailVerified bool `json:"email_verified"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	require.False(t, body.EmailVerified)
}

func TestNotificationContactOfAnUnknownAccountIs404(t *testing.T) {
	app := newInternalApp(t)

	rec := app.get(t, contactPath(uuid.NewString()), notification)
	require.Equal(t, http.StatusNotFound, rec.Code)
	require.Equal(t, apperr.CodeAccountNotFound, problemCode(t, rec))
	require.Equal(t, constants.NoStore, rec.Header().Get(constants.HeaderCacheControl))
}

// A deleted account was anonymized on the spot (AUTH-BR-007), so there is no
// recipient: the caller sees the same 404 as for an unknown id.
func TestNotificationContactOfADeletedAccountIs404(t *testing.T) {
	app := newInternalApp(t)
	account := app.verifiedAccount(t)
	require.NoError(t, account.MarkDeleted(vo.DeletionGDPRRequest, testNow))

	rec := app.get(t, contactPath(account.ID.String()), notification)
	require.Equal(t, http.StatusNotFound, rec.Code)
	require.Equal(t, apperr.CodeAccountNotFound, problemCode(t, rec))
	require.NotContains(t, rec.Body.String(), "@", "a problem never carries an address")
}

func TestNotificationContactRejectsANonUUIDPath(t *testing.T) {
	app := newInternalApp(t)

	rec := app.get(t, contactPath("not-a-uuid"), notification)
	require.Equal(t, http.StatusBadRequest, rec.Code)
	require.Equal(t, apperr.CodeValidationFailed, problemCode(t, rec))
}

func TestNotificationContactIsRateLimitedPerResource(t *testing.T) {
	app := newInternalApp(t)
	account := app.verifiedAccount(t)
	app.limiter.deny = true

	rec := app.get(t, contactPath(account.ID.String()), notification)
	require.Equal(t, http.StatusTooManyRequests, rec.Code)
	require.Equal(t, apperr.CodeRateLimited, problemCode(t, rec))
	require.Equal(t, "60", rec.Header().Get(constants.HeaderRetryAfter))
}

// The delivery route has its own scope and keys its budget by delivery id.
func TestDeliveryContentIsRateLimitedPerDelivery(t *testing.T) {
	app := newInternalApp(t)
	deliveryID := uuid.NewString()
	path := "/internal/v1/auth/notification-deliveries/" + deliveryID + "/content"

	rec := app.do(t, http.MethodPost, path, notification)
	require.Equal(t, http.StatusNotFound, rec.Code, "the stub store holds no delivery")
	require.Equal(t, []string{"internal-delivery"}, app.limiter.scopes)
	require.Equal(t, []string{notification + ":" + deliveryID}, app.limiter.subjects)

	app.limiter.deny = true
	rec = app.do(t, http.MethodPost, path, notification)
	require.Equal(t, http.StatusTooManyRequests, rec.Code)
}

// Callers are admitted by the X-Service-Identity allowlist on both routes: no
// header is 401, a service outside the list is 403, Notification passes. The
// refusal happens before the rate limiter and the handler run.
func TestInternalAPIRequiresTheNotificationServiceIdentity(t *testing.T) {
	app := newInternalApp(t)
	account := app.verifiedAccount(t)
	contact := contactPath(account.ID.String())
	delivery := "/internal/v1/auth/notification-deliveries/" + uuid.NewString() + "/content"

	anonymous := app.get(t, contact, "")
	require.Equal(t, http.StatusUnauthorized, anonymous.Code)
	require.Equal(t, apperr.CodeUnauthorized, problemCode(t, anonymous))

	stranger := app.get(t, contact, "order-service")
	require.Equal(t, http.StatusForbidden, stranger.Code)
	require.Equal(t, apperr.CodeForbidden, problemCode(t, stranger))

	deliveryAnonymous := app.do(t, http.MethodPost, delivery, "")
	require.Equal(t, http.StatusUnauthorized, deliveryAnonymous.Code)
	deliveryStranger := app.do(t, http.MethodPost, delivery, "order-service")
	require.Equal(t, http.StatusForbidden, deliveryStranger.Code)

	require.Empty(t, app.limiter.scopes, "a refused caller must not spend any budget")

	trusted := app.get(t, contact, notification)
	require.Equal(t, http.StatusOK, trusted.Code, trusted.Body.String())

	for _, rec := range []*httptest.ResponseRecorder{anonymous, stranger, deliveryAnonymous, deliveryStranger} {
		require.NotContains(t, rec.Body.String(), "@", "a refusal never leaks the address")
	}
}

// The address is PII: whatever the outcome, no log line may carry it.
func TestNotificationContactNeverLogsTheEmail(t *testing.T) {
	app := newInternalApp(t)
	account := app.verifiedAccount(t)

	require.Equal(t, http.StatusOK, app.get(t, contactPath(account.ID.String()), notification).Code)
	require.Equal(t, http.StatusNotFound, app.get(t, contactPath(uuid.NewString()), notification).Code)
	require.NotEmpty(t, app.logs.All(), "the access log must record the requests")

	for _, entry := range app.logs.All() {
		require.NotContains(t, strings.ToLower(entry.Message), strings.ToLower(testEmail))
		for _, field := range entry.Context {
			require.NotContains(t, strings.ToLower(field.String), strings.ToLower(testEmail),
				"field %q of %q carries the address", field.Key, entry.Message)
		}
	}
}

func keysOf(m map[string]json.RawMessage) []string {
	keys := make([]string, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	return keys
}
