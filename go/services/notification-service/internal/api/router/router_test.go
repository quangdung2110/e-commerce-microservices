package router_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/handler"
	"github.com/ecommerce/notification-service/internal/api/router"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/inbox"
	"github.com/ecommerce/notification-service/internal/domain/service/ops"
	"github.com/ecommerce/notification-service/internal/domain/service/status"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var fixedNow = time.Date(2026, 9, 21, 10, 0, 0, 0, time.UTC)

func devConfig() *config.Config {
	return &config.Config{
		App:       config.AppConfig{Name: "notification-service", Env: "development", Role: "all"},
		Internal:  config.InternalConfig{StatusPrincipals: []string{"order-service", "finance-ops"}, OpsPrincipals: []string{"notification-ops"}},
		Telemetry: config.TelemetryConfig{MetricsPath: "/metrics"},
	}
}

type harness struct {
	public     http.Handler
	internal   http.Handler
	inboxRepo  *fakeInboxRepo
	unread     *fakeUnread
	deliveries *fakeDeliveries
	requests   *fakeRequests
	opsRepo    *fakeOpsRepo
	lanes      *fakeLaneControl
	supp       *fakeSuppressions
	clk        *clock.Fake
}

func newHarness(t *testing.T, cfg *config.Config) *harness {
	t.Helper()
	gin.SetMode(gin.TestMode)
	h := &harness{
		inboxRepo: newFakeInboxRepo(), unread: newFakeUnread(), deliveries: newFakeDeliveries(),
		requests: newFakeRequests(), opsRepo: newFakeOpsRepo(), lanes: newFakeLaneControl(),
		supp: newFakeSuppressions(), clk: clock.NewFake(fixedNow),
	}
	logger := zap.NewNop()
	metrics := telemetry.New()
	inboxSvc := inbox.New(h.inboxRepo, h.unread, h.clk, metrics)
	statusSvc := status.New(h.requests, h.deliveries)
	contacts := &fakeContacts{known: map[uuid.UUID]string{}}
	opsSvc := ops.New(h.deliveries, h.supp, h.lanes, contacts, h.clk, logger, ops.Config{ReplayModeDefaultTTL: 6 * time.Hour}, context.Background())

	h.public = router.NewPublic(router.PublicDeps{Config: cfg, Logger: logger, Inbox: handler.NewInboxHandler(inboxSvc)})
	h.internal = router.NewInternal(router.InternalDeps{
		Config: cfg, Logger: logger, Clock: h.clk, Metrics: metrics, OpsRepo: h.opsRepo,
		Status: handler.NewStatusHandler(statusSvc),
		Ops:    handler.NewOpsHandler(opsSvc, h.clk),
		Health: handler.NewHealthHandler(map[string]handler.ReadinessCheck{"ok": func(context.Context) error { return nil }}, time.Second),
	})
	return h
}

func do(h http.Handler, method, path string, headers map[string]string, body any) *httptest.ResponseRecorder {
	var reader *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		reader = bytes.NewReader(b)
	} else {
		reader = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, reader)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func decode(t *testing.T, rec *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var out map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &out), rec.Body.String())
	return out
}

func userHeaders(user uuid.UUID) map[string]string {
	return map[string]string{constants.HeaderUserID: user.String()}
}

func opsHeaders(key string) map[string]string {
	return map[string]string{constants.HeaderServiceIdentity: "notification-ops", constants.HeaderIdempotencyKey: key}
}

// ---- inbox ----------------------------------------------------------------

func TestInboxRequiresGatewayIdentity(t *testing.T) {
	h := newHarness(t, devConfig())
	rec := do(h.public, http.MethodGet, "/api/v1/notifications/inbox", nil, nil)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
	assert.Equal(t, constants.ContentTypeProblem, rec.Header().Get("Content-Type"))
	assert.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
	body := decode(t, rec)
	assert.Equal(t, "UNAUTHORIZED", body["code"])
	for _, k := range []string{"type", "title", "status", "detail", "instance", "code"} {
		assert.Contains(t, body, k)
	}
}

func seedInbox(h *harness, user uuid.UUID, n int) []uuid.UUID {
	var ids []uuid.UUID
	for i := 0; i < n; i++ {
		id := uuid.New()
		link := "https://shop.example.com/orders/" + id.String()
		_ = h.inboxRepo.Insert(context.Background(), &entity.InboxItem{
			ID: id, UserID: user, Template: "ORDER_CONFIRMED", Title: "t", Body: "b", DeepLink: &link,
			CreatedAt: fixedNow.Add(time.Duration(i) * time.Second),
		})
		ids = append(ids, id)
	}
	return ids
}

// IT-INBOX-01: 60 items, three pages of 20, no duplicates, null cursor at the end.
func TestInboxListPagination(t *testing.T) {
	h := newHarness(t, devConfig())
	user := uuid.New()
	seedInbox(h, user, 60)

	seen := map[string]bool{}
	cursor := ""
	pages := 0
	for {
		path := "/api/v1/notifications/inbox?limit=20"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		rec := do(h.public, http.MethodGet, path, userHeaders(user), nil)
		require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
		assert.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
		body := decode(t, rec)
		items := body["items"].([]any)
		pages++
		for _, it := range items {
			m := it.(map[string]any)
			id := m["id"].(string)
			assert.False(t, seen[id], "duplicate item")
			seen[id] = true
			for _, k := range []string{"id", "template", "title", "body", "created_at", "read"} {
				assert.Contains(t, m, k)
			}
			assert.Equal(t, false, m["read"])
		}
		next, _ := body["next_cursor"].(string)
		if body["next_cursor"] == nil {
			break
		}
		cursor = next
	}
	assert.Equal(t, 3, pages)
	assert.Len(t, seen, 60)

	rec := do(h.public, http.MethodGet, "/api/v1/notifications/inbox?limit=99", userHeaders(user), nil)
	assert.Equal(t, http.StatusBadRequest, rec.Code)
	rec = do(h.public, http.MethodGet, "/api/v1/notifications/inbox?cursor=!!!", userHeaders(user), nil)
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

// IT-INBOX-02/03/04: read twice, archive twice, other user 404, unread recompute.
func TestInboxReadArchiveUnread(t *testing.T) {
	h := newHarness(t, devConfig())
	user, other := uuid.New(), uuid.New()
	ids := seedInbox(h, user, 3)

	rec := do(h.public, http.MethodGet, "/api/v1/notifications/inbox/unread-count", userHeaders(user), nil)
	require.Equal(t, http.StatusOK, rec.Code)
	body := decode(t, rec)
	assert.EqualValues(t, 3, body["count"])
	assert.Contains(t, body, "as_of")

	for i := 0; i < 2; i++ {
		rec = do(h.public, http.MethodPatch, "/api/v1/notifications/inbox/"+ids[0].String(), userHeaders(user), map[string]any{"read": true})
		require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
		assert.Equal(t, true, decode(t, rec)["read"])
	}
	rec = do(h.public, http.MethodGet, "/api/v1/notifications/inbox/unread-count", userHeaders(user), nil)
	assert.EqualValues(t, 2, decode(t, rec)["count"])

	for i := 0; i < 2; i++ {
		rec = do(h.public, http.MethodDelete, "/api/v1/notifications/inbox/"+ids[1].String(), userHeaders(user), nil)
		assert.Equal(t, http.StatusNoContent, rec.Code)
	}
	rec = do(h.public, http.MethodGet, "/api/v1/notifications/inbox/unread-count", userHeaders(user), nil)
	assert.EqualValues(t, 1, decode(t, rec)["count"])
	rec = do(h.public, http.MethodGet, "/api/v1/notifications/inbox", userHeaders(user), nil)
	assert.Len(t, decode(t, rec)["items"], 2, "archived item must disappear from the list")

	rec = do(h.public, http.MethodPatch, "/api/v1/notifications/inbox/"+ids[2].String(), userHeaders(other), map[string]any{"read": true})
	assert.Equal(t, http.StatusNotFound, rec.Code)
	rec = do(h.public, http.MethodDelete, "/api/v1/notifications/inbox/"+ids[2].String(), userHeaders(other), nil)
	assert.Equal(t, http.StatusNotFound, rec.Code)

	rec = do(h.public, http.MethodPatch, "/api/v1/notifications/inbox/"+ids[2].String(), userHeaders(user), map[string]any{"read": true, "extra": 1})
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	// Drop the counter: the next read recomputes from the repository.
	require.NoError(t, h.unread.Delete(context.Background(), user))
	rec = do(h.public, http.MethodGet, "/api/v1/notifications/inbox/unread-count", userHeaders(user), nil)
	assert.EqualValues(t, 1, decode(t, rec)["count"])

	rec = do(h.public, http.MethodPost, "/api/v1/notifications/inbox/mark-all-read", userHeaders(user), nil)
	assert.Equal(t, http.StatusNoContent, rec.Code)
	rec = do(h.public, http.MethodPost, "/api/v1/notifications/inbox/mark-all-read", userHeaders(user), nil)
	assert.Equal(t, http.StatusNoContent, rec.Code)
	rec = do(h.public, http.MethodGet, "/api/v1/notifications/inbox/unread-count", userHeaders(user), nil)
	assert.EqualValues(t, 0, decode(t, rec)["count"])

	rec = do(h.public, http.MethodGet, "/api/v1/notifications/nope", userHeaders(user), nil)
	assert.Equal(t, http.StatusNotFound, rec.Code)
	assert.Equal(t, constants.ContentTypeProblem, rec.Header().Get("Content-Type"))
	rec = do(h.public, http.MethodPut, "/api/v1/notifications/inbox", userHeaders(user), nil)
	assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)
}

// ---- health / metrics -----------------------------------------------------

func TestHealthAndMetrics(t *testing.T) {
	h := newHarness(t, devConfig())
	assert.Equal(t, http.StatusOK, do(h.internal, http.MethodGet, "/livez", nil, nil).Code)
	assert.Equal(t, http.StatusOK, do(h.internal, http.MethodGet, "/readyz", nil, nil).Code)
	rec := do(h.internal, http.MethodGet, "/metrics", nil, nil)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), "notification_")

	failing := handler.NewHealthHandler(map[string]handler.ReadinessCheck{"postgres": func(context.Context) error { return assert.AnError }}, time.Second)
	cfg := devConfig()
	eng := router.NewInternal(router.InternalDeps{Config: cfg, Logger: zap.NewNop(), Clock: h.clk, OpsRepo: h.opsRepo,
		Status: handler.NewStatusHandler(status.New(h.requests, h.deliveries)),
		Ops:    handler.NewOpsHandler(nil, h.clk), Health: failing})
	rec = do(eng, http.MethodGet, "/readyz", nil, nil)
	assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
	assert.Contains(t, rec.Body.String(), "postgres")
}

// ---- status ---------------------------------------------------------------

func TestStatusIdentityAndTimeline(t *testing.T) {
	h := newHarness(t, devConfig())
	reqID, user := uuid.New(), uuid.New()
	subject := "orders/1000234"
	tpl := "ORDER_CONFIRMED"
	lane := entity.LaneTransactional
	require.NoError(t, h.requests.Insert(context.Background(), &entity.Request{
		ID: reqID, Source: "/services/order", EventID: "evt-1", EventType: "com.ecommerce.notification.requested.v1",
		Subject: &subject, InputKind: entity.InputRequest, Template: &tpl, Lane: &lane, UserID: &user,
		Decision: entity.DecisionDecided, ReceivedAt: fixedNow, TemplateData: json.RawMessage(`{"secret":"never"}`),
	}))
	for _, ch := range entity.AllChannels {
		require.NoError(t, h.deliveries.Insert(context.Background(), &entity.Delivery{
			ID: uuid.New(), RequestID: reqID, UserID: &user, Subject: &subject, Lane: lane, Channel: ch, Template: tpl,
			Status: entity.StatusPending, NextAttemptAt: fixedNow, ExpiresAt: fixedNow.Add(time.Hour), CreatedAt: fixedNow,
		}))
	}

	path := "/internal/v1/notifications/requests/" + reqID.String()
	rec := do(h.internal, http.MethodGet, path, nil, nil)
	assert.Equal(t, http.StatusUnauthorized, rec.Code, "no identity")
	rec = do(h.internal, http.MethodGet, path, map[string]string{constants.HeaderServiceIdentity: "cart-service"}, nil)
	assert.Equal(t, http.StatusForbidden, rec.Code, "not in allowlist")
	rec = do(h.internal, http.MethodGet, path, map[string]string{constants.HeaderServiceIdentity: "notification-ops"}, nil)
	assert.Equal(t, http.StatusForbidden, rec.Code, "ops principal is not a status principal")

	rec = do(h.internal, http.MethodGet, path, map[string]string{constants.HeaderServiceIdentity: "order-service"}, nil)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	assert.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
	body := decode(t, rec)
	assert.Equal(t, reqID.String(), body["request_id"])
	assert.Equal(t, "DECIDED", body["decision"])
	assert.NotContains(t, rec.Body.String(), "template_data")
	assert.NotContains(t, rec.Body.String(), "never")
	assert.NotContains(t, rec.Body.String(), user.String(), "no user_id in status payloads")
	assert.Len(t, body["deliveries"], 2)
	d := body["deliveries"].([]any)[0].(map[string]any)
	for _, k := range []string{"delivery_id", "request_id", "lane", "channel", "template", "status", "attempt", "created_at"} {
		assert.Contains(t, d, k)
	}

	rec = do(h.internal, http.MethodGet, "/internal/v1/notifications/deliveries?subject="+subject+"&template="+tpl, map[string]string{constants.HeaderServiceIdentity: "finance-ops"}, nil)
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Len(t, decode(t, rec)["deliveries"], 2)
	rec = do(h.internal, http.MethodGet, "/internal/v1/notifications/deliveries", map[string]string{constants.HeaderServiceIdentity: "finance-ops"}, nil)
	assert.Equal(t, http.StatusBadRequest, rec.Code, "subject is required")
	rec = do(h.internal, http.MethodGet, "/internal/v1/notifications/requests/"+uuid.New().String(), map[string]string{constants.HeaderServiceIdentity: "order-service"}, nil)
	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestInternalRejectsHeaderOutsideDevelopment(t *testing.T) {
	cfg := devConfig()
	cfg.App.Env = "staging"
	h := newHarness(t, cfg)
	rec := do(h.internal, http.MethodGet, "/internal/v1/notifications/deliveries?subject=x", map[string]string{constants.HeaderServiceIdentity: "order-service"}, nil)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

// ---- ops ------------------------------------------------------------------

func TestOpsLaneStateAndIdempotency(t *testing.T) {
	h := newHarness(t, devConfig())
	path := "/internal/v1/notifications/ops/lanes/bulk"

	rec := do(h.internal, http.MethodPut, path, map[string]string{constants.HeaderServiceIdentity: "notification-ops"}, map[string]any{"state": "paused"})
	assert.Equal(t, http.StatusBadRequest, rec.Code, "Idempotency-Key required")
	rec = do(h.internal, http.MethodPut, path, map[string]string{constants.HeaderServiceIdentity: "order-service", constants.HeaderIdempotencyKey: "k-000001"}, map[string]any{"state": "paused"})
	assert.Equal(t, http.StatusForbidden, rec.Code, "status principal cannot operate")

	body := map[string]any{"state": "paused", "channel": "email", "reason": "provider incident"}
	rec = do(h.internal, http.MethodPut, path, opsHeaders("k-000001"), body)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	first := decode(t, rec)
	assert.Equal(t, "bulk", first["lane"])
	assert.Equal(t, "paused", first["email"])
	assert.Equal(t, "active", first["in_app"])
	assert.Equal(t, "notification-ops", first["updated_by"])

	// IT-OPS-01: same key + same body → replay; same key + other body → 409.
	rec = do(h.internal, http.MethodPut, path, opsHeaders("k-000001"), body)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, first, decode(t, rec))
	rec = do(h.internal, http.MethodPut, path, opsHeaders("k-000001"), map[string]any{"state": "active"})
	assert.Equal(t, http.StatusConflict, rec.Code)
	assert.Equal(t, "IDEMPOTENCY_CONFLICT", decode(t, rec)["code"])

	rec = do(h.internal, http.MethodGet, path, map[string]string{constants.HeaderServiceIdentity: "notification-ops"}, nil)
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "paused", decode(t, rec)["email"])

	rec = do(h.internal, http.MethodPut, path, opsHeaders("k-000002"), map[string]any{"state": "bogus"})
	assert.Equal(t, http.StatusBadRequest, rec.Code)
	rec = do(h.internal, http.MethodPut, "/internal/v1/notifications/ops/lanes/urgent", opsHeaders("k-000003"), map[string]any{"state": "paused"})
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	assert.GreaterOrEqual(t, h.opsRepo.auditCount(), 4, "every ops call is audited")
}

func TestOpsRedriveAndDLQ(t *testing.T) {
	h := newHarness(t, devConfig())
	user := uuid.New()
	failed := uuid.New()
	pending := uuid.New()
	code := entity.ReasonProviderError
	contract := entity.ReasonInvalidTemplateData
	finished := fixedNow.Add(-time.Hour)
	for _, d := range []entity.Delivery{
		{ID: failed, RequestID: uuid.New(), UserID: &user, Lane: entity.LaneTransactional, Channel: entity.ChannelEmail, Template: "ORDER_CONFIRMED", Status: entity.StatusFailed, LastErrorCode: &code, FinishedAt: &finished, NextAttemptAt: fixedNow, ExpiresAt: fixedNow.Add(time.Hour), CreatedAt: fixedNow},
		{ID: pending, RequestID: uuid.New(), UserID: &user, Lane: entity.LaneTransactional, Channel: entity.ChannelEmail, Template: "ORDER_CONFIRMED", Status: entity.StatusPending, NextAttemptAt: fixedNow, ExpiresAt: fixedNow.Add(time.Hour), CreatedAt: fixedNow},
		{ID: uuid.New(), RequestID: uuid.New(), UserID: &user, Lane: entity.LaneTransactional, Channel: entity.ChannelEmail, Template: "ORDER_CONFIRMED", Status: entity.StatusFailed, LastErrorCode: &contract, FinishedAt: &finished, NextAttemptAt: fixedNow, ExpiresAt: fixedNow.Add(time.Hour), CreatedAt: fixedNow},
	} {
		d := d
		require.NoError(t, h.deliveries.Insert(context.Background(), &d))
	}

	rec := do(h.internal, http.MethodPost, "/internal/v1/notifications/ops/deliveries/"+pending.String()+"/redrive", opsHeaders("rd-000001"), nil)
	assert.Equal(t, http.StatusConflict, rec.Code)
	assert.Equal(t, "STATE_CONFLICT", decode(t, rec)["code"])

	rec = do(h.internal, http.MethodPost, "/internal/v1/notifications/ops/deliveries/"+failed.String()+"/redrive", opsHeaders("rd-000002"), map[string]any{"reason": "provider fixed"})
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	body := decode(t, rec)
	assert.Equal(t, "PENDING", body["status"])
	assert.EqualValues(t, 0, body["attempt"])
	assert.Equal(t, failed.String(), body["delivery_id"])

	rec = do(h.internal, http.MethodPost, "/internal/v1/notifications/ops/deliveries/"+uuid.New().String()+"/redrive", opsHeaders("rd-000003"), nil)
	assert.Equal(t, http.StatusNotFound, rec.Code)

	rec = do(h.internal, http.MethodPost, "/internal/v1/notifications/ops/dlq/redrive", opsHeaders("dlq-00001"), map[string]any{"lane": "transactional", "reason": "after incident"})
	require.Equal(t, http.StatusAccepted, rec.Code, rec.Body.String())
	body = decode(t, rec)
	assert.Contains(t, body, "operation_id")
	assert.EqualValues(t, 0, body["matched"], "contract failures are never redriven; the provider_error row was already redriven")

	rec = do(h.internal, http.MethodPost, "/internal/v1/notifications/ops/dlq/redrive", opsHeaders("dlq-00002"), map[string]any{"lane": "transactional"})
	assert.Equal(t, http.StatusBadRequest, rec.Code, "reason is required")
}

func TestOpsSuppressionsAndReplayMode(t *testing.T) {
	h := newHarness(t, devConfig())
	hard := uuid.New()
	require.NoError(t, h.supp.Insert(context.Background(), &entity.Suppression{ID: hard, EmailHash: strings.Repeat("a", 64), Reason: entity.SuppressionHardBounce, Source: "ses", CreatedAt: fixedNow}))
	manual := uuid.New()
	require.NoError(t, h.supp.Insert(context.Background(), &entity.Suppression{ID: manual, EmailHash: strings.Repeat("b", 64), Reason: entity.SuppressionManual, Source: "ops", CreatedAt: fixedNow}))

	// IT-OPS-02: HARD_BOUNCE cannot be removed.
	rec := do(h.internal, http.MethodDelete, "/internal/v1/notifications/ops/suppressions/"+hard.String(), map[string]string{constants.HeaderServiceIdentity: "notification-ops"}, nil)
	assert.Equal(t, http.StatusConflict, rec.Code)
	rec = do(h.internal, http.MethodDelete, "/internal/v1/notifications/ops/suppressions/"+manual.String(), map[string]string{constants.HeaderServiceIdentity: "notification-ops"}, nil)
	assert.Equal(t, http.StatusNoContent, rec.Code)
	rec = do(h.internal, http.MethodDelete, "/internal/v1/notifications/ops/suppressions/"+manual.String(), map[string]string{constants.HeaderServiceIdentity: "notification-ops"}, nil)
	assert.Equal(t, http.StatusNotFound, rec.Code)

	// Manual suppression needs a resolvable user (fake contacts knows nobody → 404).
	rec = do(h.internal, http.MethodPost, "/internal/v1/notifications/ops/suppressions", opsHeaders("sp-000001"), map[string]any{"user_id": uuid.New().String(), "reason": "legal"})
	assert.Equal(t, http.StatusNotFound, rec.Code, rec.Body.String())
	rec = do(h.internal, http.MethodPost, "/internal/v1/notifications/ops/suppressions", opsHeaders("sp-000002"), map[string]any{"user_id": "nope", "reason": "legal"})
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	rec = do(h.internal, http.MethodPut, "/internal/v1/notifications/ops/replay-mode", opsHeaders("rp-000001"), map[string]any{"enabled": true})
	assert.Equal(t, http.StatusBadRequest, rec.Code, "ignore_events_older_than required when enabled")
	rec = do(h.internal, http.MethodPut, "/internal/v1/notifications/ops/replay-mode", opsHeaders("rp-000002"), map[string]any{"enabled": true, "ignore_events_older_than": "7 days"})
	assert.Equal(t, http.StatusBadRequest, rec.Code)
	rec = do(h.internal, http.MethodPut, "/internal/v1/notifications/ops/replay-mode", opsHeaders("rp-000003"), map[string]any{"enabled": true, "ignore_events_older_than": "P7D", "ttl": "PT2H"})
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	body := decode(t, rec)
	assert.Equal(t, true, body["enabled"])
	assert.Equal(t, "P7D", body["ignore_events_older_than"])
	assert.Equal(t, fixedNow.Add(2*time.Hour).Format(time.RFC3339), body["expires_at"])
	rec = do(h.internal, http.MethodPut, "/internal/v1/notifications/ops/replay-mode", opsHeaders("rp-000004"), map[string]any{"enabled": false})
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, false, decode(t, rec)["enabled"])
}
