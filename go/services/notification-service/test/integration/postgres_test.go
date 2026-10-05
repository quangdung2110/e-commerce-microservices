//go:build integration

// Package integration exercises the Postgres store against a real
// PostgreSQL 16 started with testcontainers: the claim statement, the
// compare-and-set marks, the reaper, tracked-state ranking, redrive,
// keyset pagination, preference CAS and partition maintenance — everything
// only a real database can prove (15-verification.md).
package integration

import (
	"context"
	"fmt"
	"log"
	"os"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	postgresmodule "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/infrastructure/postgres"
)

const postgresImage = "postgres:16-alpine"

var (
	testPool  *pgxpool.Pool
	testStore *postgres.Store
	testDB    config.DatabaseConfig
)

func TestMain(m *testing.M) {
	ctx := context.Background()

	container, err := postgresmodule.Run(ctx, postgresImage,
		postgresmodule.WithDatabase("notification_db"),
		postgresmodule.WithUsername("postgres"),
		postgresmodule.WithPassword("postgres"),
		testcontainers.WithEnv(map[string]string{"TZ": "UTC"}),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(2*time.Minute),
		),
	)
	if err != nil {
		log.Fatalf("integration: start postgres: %v", err)
	}
	code := 1
	func() {
		defer func() {
			if testPool != nil {
				testPool.Close()
			}
			termCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			if err := container.Terminate(termCtx); err != nil {
				log.Printf("integration: terminate postgres: %v", err)
			}
		}()

		host, err := container.Host(ctx)
		if err != nil {
			log.Printf("integration: host: %v", err)
			return
		}
		mapped, err := container.MappedPort(ctx, "5432/tcp")
		if err != nil {
			log.Printf("integration: mapped port: %v", err)
			return
		}
		testDB = config.DatabaseConfig{
			Host: host, Port: int(mapped.Num()), Name: "notification_db",
			Username: "postgres", Password: "postgres", SSLMode: "disable",
			ConnMaxLifetime: 5 * time.Minute, ConnectTimeout: 10 * time.Second,
		}

		// The schema under test is the one the service ships.
		if err := postgres.Migrate(ctx, testDB.DSN()); err != nil {
			log.Printf("integration: migrate: %v", err)
			return
		}
		testPool, err = postgres.NewPool(ctx, testDB, 8, "notification-integration")
		if err != nil {
			log.Printf("integration: pool: %v", err)
			return
		}
		testStore = postgres.New(testPool, clock.System{}, zap.NewNop())
		code = m.Run()
		e2eTeardown()
	}()
	os.Exit(code)
}

// ---- builders ---------------------------------------------------------------

func newRequest(userID uuid.UUID) *entity.Request {
	now := time.Now().UTC()
	lane := entity.LaneTransactional
	subject := "orders/" + uuid.NewString()
	return &entity.Request{
		ID: uuid.New(), Source: "/services/order", EventID: uuid.NewString(),
		EventType: "com.ecommerce.notification.request.v1", EventTime: &now, Subject: &subject,
		InputKind: entity.InputRequest, Template: ptr("ORDER_CONFIRMED"), Lane: &lane,
		UserID: &userID, ExpiresAt: ptr(now.Add(time.Hour)), TemplateData: []byte(`{"order_id":"1"}`),
		Decision: entity.DecisionDecided, ReceivedAt: now,
	}
}

func newDelivery(userID uuid.UUID, lane entity.Lane, ch entity.Channel) *entity.Delivery {
	now := time.Now().UTC()
	subject := "orders/" + uuid.NewString()
	return &entity.Delivery{
		ID: uuid.New(), RequestID: uuid.New(), UserID: &userID, Subject: &subject,
		Lane: lane, Channel: ch, Template: "ORDER_CONFIRMED", Status: entity.StatusPending,
		NextAttemptAt: now, ExpiresAt: now.Add(time.Hour), CreatedAt: now, UpdatedAt: now,
	}
}

func insertDeliveries(t *testing.T, n int, lane entity.Lane, ch entity.Channel) []uuid.UUID {
	t.Helper()
	ctx := context.Background()
	userID := uuid.New()
	ids := make([]uuid.UUID, 0, n)
	for i := 0; i < n; i++ {
		d := newDelivery(userID, lane, ch)
		require.NoError(t, testStore.Deliveries().Insert(ctx, d))
		ids = append(ids, d.ID)
	}
	return ids
}

// claimIDs claims a big batch and returns the rows among the given ids;
// other tests' leftovers in the same lane×channel may be claimed too, which
// is harmless with a long lease.
func claimIDs(t *testing.T, lane entity.Lane, ch entity.Channel, worker string, lease time.Duration, want ...uuid.UUID) map[uuid.UUID]entity.Delivery {
	t.Helper()
	rows, err := testStore.Deliveries().Claim(context.Background(), lane, ch, worker, 500, lease)
	require.NoError(t, err)
	wanted := map[uuid.UUID]bool{}
	for _, id := range want {
		wanted[id] = true
	}
	out := map[uuid.UUID]entity.Delivery{}
	for _, r := range rows {
		if wanted[r.ID] {
			out[r.ID] = r
		}
	}
	return out
}

func getDelivery(t *testing.T, id uuid.UUID) *entity.Delivery {
	t.Helper()
	d, err := testStore.Deliveries().GetByID(context.Background(), id)
	require.NoError(t, err)
	return d
}

func ptr[T any](v T) *T { return &v }

// ---- tests ------------------------------------------------------------------

func TestPostgresIngestDedup(t *testing.T) {
	ctx := context.Background()
	eventID := uuid.NewString()
	first, err := testStore.Dedup().ClaimIngest(ctx, "/services/order", eventID)
	require.NoError(t, err)
	second, err := testStore.Dedup().ClaimIngest(ctx, "/services/order", eventID)
	require.NoError(t, err)
	assert.True(t, first, "first claim owns the key")
	assert.False(t, second, "second claim is a duplicate")

	ok, err := testStore.Dedup().ClaimDelivery(ctx, "order_confirmed:1", entity.ChannelEmail)
	require.NoError(t, err)
	assert.True(t, ok)
	ok, err = testStore.Dedup().ClaimDelivery(ctx, "order_confirmed:1", entity.ChannelInApp)
	require.NoError(t, err)
	assert.True(t, ok, "the key is the pair (dedup_key, channel)")
	ok, err = testStore.Dedup().ClaimDelivery(ctx, "order_confirmed:1", entity.ChannelEmail)
	require.NoError(t, err)
	assert.False(t, ok)
}

func TestPostgresRequestRoundTrip(t *testing.T) {
	ctx := context.Background()
	userID := uuid.New()
	req := newRequest(userID)
	require.NoError(t, testStore.Requests().Insert(ctx, req))

	got, err := testStore.Requests().GetByID(ctx, req.ID)
	require.NoError(t, err)
	assert.Equal(t, req.Source, got.Source)
	assert.JSONEq(t, `{"order_id":"1"}`, string(got.TemplateData))
	assert.Equal(t, entity.LaneTransactional, *got.Lane)

	got.Decision = entity.DecisionSkipped
	got.DecisionReason = ptr(entity.ReasonAllChannelsOff)
	require.NoError(t, testStore.Requests().UpdateDecision(ctx, got))

	n, err := testStore.Requests().AnonymizeByUser(ctx, userID)
	require.NoError(t, err)
	assert.EqualValues(t, 1, n)
	got, err = testStore.Requests().GetByID(ctx, req.ID)
	require.NoError(t, err)
	assert.Nil(t, got.UserID)
	assert.Nil(t, got.Subject)
	assert.Nil(t, got.TemplateData)
	assert.Equal(t, entity.DecisionSkipped, got.Decision)

	_, err = testStore.Requests().GetByID(ctx, uuid.New())
	assert.True(t, apperr.Is(err, apperr.ErrNotFound))
}

func TestPostgresClaimMarksSendingOnce(t *testing.T) {
	ctx := context.Background()
	ids := insertDeliveries(t, 5, entity.LaneCritical, entity.ChannelEmail)

	claimed := claimIDs(t, entity.LaneCritical, entity.ChannelEmail, "w1", 10*time.Minute, ids...)
	require.Len(t, claimed, 5)
	for _, d := range claimed {
		assert.Equal(t, entity.StatusSending, d.Status)
		assert.Equal(t, 1, d.Attempt)
		require.NotNil(t, d.LeaseUntil)
		assert.True(t, d.LeaseUntil.After(time.Now()))
		require.NotNil(t, d.LockedBy)
		assert.Equal(t, "w1", *d.LockedBy)
	}

	again, err := testStore.Deliveries().Claim(ctx, entity.LaneCritical, entity.ChannelEmail, "w2", 500, time.Minute)
	require.NoError(t, err)
	for _, d := range again {
		for _, id := range ids {
			assert.NotEqual(t, id, d.ID, "a SENDING row must not be claimed twice")
		}
	}
}

func TestPostgresConcurrentClaimsDoNotOverlap(t *testing.T) {
	ctx := context.Background()
	ids := insertDeliveries(t, 40, entity.LaneTransactional, entity.ChannelEmail)
	wanted := map[uuid.UUID]bool{}
	for _, id := range ids {
		wanted[id] = true
	}

	var (
		mu   sync.Mutex
		seen = map[uuid.UUID]string{}
		dups []uuid.UUID
		wg   sync.WaitGroup
	)
	for _, worker := range []string{"a", "b", "c"} {
		wg.Add(1)
		go func(worker string) {
			defer wg.Done()
			for {
				rows, err := testStore.Deliveries().Claim(ctx, entity.LaneTransactional, entity.ChannelEmail, worker, 7, 10*time.Minute)
				if !assert.NoError(t, err) || len(rows) == 0 {
					return
				}
				mu.Lock()
				for _, r := range rows {
					if !wanted[r.ID] {
						continue
					}
					if _, dup := seen[r.ID]; dup {
						dups = append(dups, r.ID)
					}
					seen[r.ID] = worker
				}
				mu.Unlock()
			}
		}(worker)
	}
	wg.Wait()
	assert.Empty(t, dups, "no row claimed by two workers")
	assert.Len(t, seen, 40, "every row claimed exactly once")
}

func TestPostgresMarkSentCompareAndSet(t *testing.T) {
	ctx := context.Background()
	ids := insertDeliveries(t, 1, entity.LaneBulk, entity.ChannelEmail)
	id := ids[0]
	require.Len(t, claimIDs(t, entity.LaneBulk, entity.ChannelEmail, "owner", 10*time.Minute, id), 1)

	ok, err := testStore.Deliveries().MarkSent(ctx, id, "intruder", entity.ProviderMailpit, "msg-1", "abc123", time.Now(), false)
	require.NoError(t, err)
	assert.False(t, ok, "wrong worker loses the compare-and-set")

	ok, err = testStore.Deliveries().MarkSent(ctx, id, "owner", entity.ProviderMailpit, "msg-1", "abc123", time.Now().UTC(), true)
	require.NoError(t, err)
	assert.True(t, ok)

	d := getDelivery(t, id)
	assert.Equal(t, entity.StatusSent, d.Status)
	assert.Equal(t, entity.ProviderMailpit, *d.Provider)
	assert.Equal(t, "msg-1", *d.ProviderMessageID)
	assert.Equal(t, "abc123", *d.TemplateVersion)
	assert.True(t, d.PossiblyDuplicate)
	assert.Nil(t, d.LeaseUntil)
	assert.Nil(t, d.LockedBy)
	assert.NotNil(t, d.SentAt)

	found, err := testStore.Deliveries().FindByProviderMessage(ctx, entity.ProviderMailpit, "msg-1")
	require.NoError(t, err)
	assert.Equal(t, id, found.ID)

	// A second MarkSent on a SENT row is a no-op: the row is no longer SENDING.
	ok, err = testStore.Deliveries().MarkSent(ctx, id, "owner", entity.ProviderMailpit, "msg-2", "abc123", time.Now(), false)
	require.NoError(t, err)
	assert.False(t, ok)
}

func TestPostgresReleaseAndRetryAndFinal(t *testing.T) {
	ctx := context.Background()
	ids := insertDeliveries(t, 3, entity.LaneBulk, entity.ChannelEmail)
	claimed := claimIDs(t, entity.LaneBulk, entity.ChannelEmail, "w", 10*time.Minute, ids...)
	require.Len(t, claimed, 3)
	later := time.Now().UTC().Add(time.Minute).Truncate(time.Microsecond)

	ok, err := testStore.Deliveries().Release(ctx, ids[0], "w", later, entity.ReasonBreakerOpen)
	require.NoError(t, err)
	assert.True(t, ok)
	d := getDelivery(t, ids[0])
	assert.Equal(t, entity.StatusRetry, d.Status)
	assert.Equal(t, 0, d.Attempt, "release undoes the claim's attempt")
	assert.Equal(t, entity.ReasonBreakerOpen, *d.LastErrorCode)
	assert.True(t, d.NextAttemptAt.Equal(later))

	ok, err = testStore.Deliveries().MarkRetry(ctx, ids[1], "w", later, entity.ReasonProviderError, "503 from provider")
	require.NoError(t, err)
	assert.True(t, ok)
	d = getDelivery(t, ids[1])
	assert.Equal(t, entity.StatusRetry, d.Status)
	assert.Equal(t, 1, d.Attempt)
	assert.Equal(t, "503 from provider", *d.LastError)

	at := time.Now().UTC()
	ok, err = testStore.Deliveries().MarkFinal(ctx, ids[2], "w", entity.StatusSkipped, entity.ReasonEmailUnverified, "", at)
	require.NoError(t, err)
	assert.True(t, ok)
	d = getDelivery(t, ids[2])
	assert.Equal(t, entity.StatusSkipped, d.Status)
	assert.Equal(t, entity.ReasonEmailUnverified, *d.SkipReason)
	assert.Nil(t, d.LastErrorCode)
	assert.NotNil(t, d.FinishedAt)

	_, err = testStore.Deliveries().MarkFinal(ctx, ids[2], "w", entity.StatusSent, "", "", at)
	assert.Error(t, err, "SENT is not a terminal status for MarkFinal")

	// In-app one-step delivery.
	inApp := insertDeliveries(t, 1, entity.LaneBulk, entity.ChannelInApp)
	require.Len(t, claimIDs(t, entity.LaneBulk, entity.ChannelInApp, "w", 10*time.Minute, inApp...), 1)
	ok, err = testStore.Deliveries().MarkDeliveredInApp(ctx, inApp[0], "w", "v1", at)
	require.NoError(t, err)
	assert.True(t, ok)
	d = getDelivery(t, inApp[0])
	assert.Equal(t, entity.StatusDelivered, d.Status)
	assert.Equal(t, entity.ProviderInbox, *d.Provider)
	assert.NotNil(t, d.SentAt)
	assert.NotNil(t, d.DeliveredAt)
	assert.NotNil(t, d.FinishedAt)
}

func TestPostgresReapExpiredLeases(t *testing.T) {
	ctx := context.Background()
	ids := insertDeliveries(t, 1, entity.LaneTransactional, entity.ChannelInApp)
	id := ids[0]
	require.Len(t, claimIDs(t, entity.LaneTransactional, entity.ChannelInApp, "dead-worker", time.Millisecond, id), 1)
	time.Sleep(20 * time.Millisecond)

	n, err := testStore.Deliveries().ReapExpiredLeases(ctx, time.Now().UTC())
	require.NoError(t, err)
	assert.GreaterOrEqual(t, n, int64(1))

	d := getDelivery(t, id)
	assert.Equal(t, entity.StatusRetry, d.Status)
	assert.Equal(t, 1, d.Attempt, "the reaped attempt still counts")
	assert.True(t, d.WasLeaseExpired())
	assert.Contains(t, *d.LastError, "dead-worker")
	assert.Nil(t, d.LeaseUntil)
	assert.Nil(t, d.LockedBy)

	// The next claimant sees the lease_expired marker (layer C3).
	claimed := claimIDs(t, entity.LaneTransactional, entity.ChannelInApp, "next", 10*time.Minute, id)
	require.Len(t, claimed, 1)
	assert.Equal(t, 2, claimed[id].Attempt)
	row := claimed[id]
	assert.True(t, row.WasLeaseExpired())
}

func TestPostgresTransitionTrackedRanking(t *testing.T) {
	ctx := context.Background()
	ids := insertDeliveries(t, 1, entity.LaneBulk, entity.ChannelEmail)
	id := ids[0]
	require.Len(t, claimIDs(t, entity.LaneBulk, entity.ChannelEmail, "w", 10*time.Minute, id), 1)
	ok, err := testStore.Deliveries().MarkSent(ctx, id, "w", entity.ProviderSES, "ses-"+id.String(), "v1", time.Now().UTC(), false)
	require.NoError(t, err)
	require.True(t, ok)

	t1 := time.Now().UTC().Truncate(time.Microsecond)
	changed, err := testStore.Deliveries().TransitionTracked(ctx, id, entity.StatusDelivered, t1, "")
	require.NoError(t, err)
	assert.True(t, changed, "SENT → DELIVERED")
	d := getDelivery(t, id)
	assert.Equal(t, entity.StatusDelivered, d.Status)
	assert.True(t, d.DeliveredAt.Equal(t1))
	assert.True(t, d.FinishedAt.Equal(t1))

	changed, err = testStore.Deliveries().TransitionTracked(ctx, id, entity.StatusSent, t1.Add(time.Second), "late send")
	require.NoError(t, err)
	assert.False(t, changed, "late SENT never downgrades")
	d = getDelivery(t, id)
	assert.Equal(t, entity.StatusDelivered, d.Status)
	assert.Nil(t, d.LastError, "a lower rank writes nothing")

	// Equal rank refreshes the timestamp only.
	t2 := t1.Add(2 * time.Second)
	changed, err = testStore.Deliveries().TransitionTracked(ctx, id, entity.StatusDelivered, t2, "again")
	require.NoError(t, err)
	assert.False(t, changed)
	d = getDelivery(t, id)
	assert.True(t, d.DeliveredAt.Equal(t2))
	assert.Nil(t, d.LastError)

	changed, err = testStore.Deliveries().TransitionTracked(ctx, id, entity.StatusBounced, t2, "delayed bounce")
	require.NoError(t, err)
	assert.True(t, changed, "BOUNCED after DELIVERED is accepted")
	d = getDelivery(t, id)
	assert.Equal(t, entity.StatusBounced, d.Status)
	assert.Equal(t, "delayed bounce", *d.LastError)

	// Recovery of a FAILED(lease_expired) row by a late webhook (04 §4 step 7).
	rec := insertDeliveries(t, 1, entity.LaneBulk, entity.ChannelEmail)
	require.Len(t, claimIDs(t, entity.LaneBulk, entity.ChannelEmail, "w", 10*time.Minute, rec...), 1)
	ok, err = testStore.Deliveries().MarkFinal(ctx, rec[0], "w", entity.StatusFailed, entity.ReasonLeaseExpired, "lease expired", time.Now().UTC())
	require.NoError(t, err)
	require.True(t, ok)
	changed, err = testStore.Deliveries().TransitionTracked(ctx, rec[0], entity.StatusDelivered, time.Now().UTC(), "recovered by webhook")
	require.NoError(t, err)
	assert.True(t, changed)
	d = getDelivery(t, rec[0])
	assert.Equal(t, entity.StatusDelivered, d.Status)
	assert.Contains(t, *d.LastError, "recovered by webhook")

	// SKIPPED rows are never touched by a webhook.
	skipped := insertDeliveries(t, 1, entity.LaneBulk, entity.ChannelEmail)
	require.Len(t, claimIDs(t, entity.LaneBulk, entity.ChannelEmail, "w", 10*time.Minute, skipped...), 1)
	_, err = testStore.Deliveries().MarkFinal(ctx, skipped[0], "w", entity.StatusSkipped, entity.ReasonSuppressed, "", time.Now().UTC())
	require.NoError(t, err)
	changed, err = testStore.Deliveries().TransitionTracked(ctx, skipped[0], entity.StatusDelivered, time.Now().UTC(), "")
	require.NoError(t, err)
	assert.False(t, changed)
	assert.Equal(t, entity.StatusSkipped, getDelivery(t, skipped[0]).Status)

	_, err = testStore.Deliveries().TransitionTracked(ctx, uuid.New(), entity.StatusDelivered, time.Now().UTC(), "")
	assert.True(t, apperr.Is(err, apperr.ErrNotFound))
}

func TestPostgresRedrive(t *testing.T) {
	ctx := context.Background()
	now := time.Now().UTC()
	ids := insertDeliveries(t, 1, entity.LaneCritical, entity.ChannelInApp)
	id := ids[0]

	_, err := testStore.Deliveries().Redrive(ctx, id, nil, now)
	assert.True(t, apperr.Is(err, apperr.ErrStateConflict), "PENDING cannot be redriven: %v", err)

	require.Len(t, claimIDs(t, entity.LaneCritical, entity.ChannelInApp, "w", 10*time.Minute, id), 1)
	ok, err := testStore.Deliveries().MarkFinal(ctx, id, "w", entity.StatusFailed, entity.ReasonProviderError, "boom", now)
	require.NoError(t, err)
	require.True(t, ok)

	d, err := testStore.Deliveries().Redrive(ctx, id, nil, now)
	require.NoError(t, err)
	assert.Equal(t, entity.StatusPending, d.Status)
	assert.Equal(t, 0, d.Attempt)
	assert.Nil(t, d.FinishedAt)
	assert.Equal(t, entity.ReasonProviderError, *d.LastErrorCode, "history is kept")

	// Past expires_at: a new expiry is required.
	require.Len(t, claimIDs(t, entity.LaneCritical, entity.ChannelInApp, "w", 10*time.Minute, id), 1)
	_, err = testStore.Deliveries().MarkFinal(ctx, id, "w", entity.StatusExpired, entity.ReasonExpired, "", now)
	require.NoError(t, err)
	future := now.Add(2 * time.Hour)
	_, err = testStore.Deliveries().Redrive(ctx, id, nil, future)
	assert.True(t, apperr.Is(err, apperr.ErrValidation), "expired without new expiry: %v", err)
	newExp := future.Add(time.Hour).Truncate(time.Microsecond)
	d, err = testStore.Deliveries().Redrive(ctx, id, &newExp, future)
	require.NoError(t, err)
	assert.Equal(t, entity.StatusPending, d.Status)
	assert.True(t, d.ExpiresAt.Equal(newExp))
	_, err = testStore.Deliveries().Redrive(ctx, uuid.New(), nil, now)
	assert.True(t, apperr.Is(err, apperr.ErrNotFound))
}

func TestPostgresRedriveBatchExcludesContractReasons(t *testing.T) {
	ctx := context.Background()
	now := time.Now().UTC()
	ids := insertDeliveries(t, 3, entity.LaneCritical, entity.ChannelInApp)
	claimed := claimIDs(t, entity.LaneCritical, entity.ChannelInApp, "w", 10*time.Minute, ids...)
	require.Len(t, claimed, 3)
	for i, reason := range []string{entity.ReasonProviderError, entity.ReasonRenderFailed, entity.ReasonProviderError} {
		ok, err := testStore.Deliveries().MarkFinal(ctx, ids[i], "w", entity.StatusFailed, reason, "x", now)
		require.NoError(t, err)
		require.True(t, ok)
	}
	filter := entity.RedriveFilter{
		Lane:        entity.LaneCritical,
		FailedAfter: ptr(now.Add(-time.Second)),
		Limit:       100,
	}
	count, err := testStore.Deliveries().CountRedrivable(ctx, filter)
	require.NoError(t, err)
	assert.EqualValues(t, 2, count, "render_failed is a producer-contract reason")

	n, err := testStore.Deliveries().RedriveBatch(ctx, filter, now)
	require.NoError(t, err)
	assert.EqualValues(t, 2, n)
	assert.Equal(t, entity.StatusPending, getDelivery(t, ids[0]).Status)
	assert.Equal(t, entity.StatusFailed, getDelivery(t, ids[1]).Status)
	assert.Equal(t, entity.StatusPending, getDelivery(t, ids[2]).Status)

	// Explicit contract reason still yields nothing.
	filter.ReasonCode = entity.ReasonRenderFailed
	count, err = testStore.Deliveries().CountRedrivable(ctx, filter)
	require.NoError(t, err)
	assert.Zero(t, count)
}

func TestPostgresListAndStats(t *testing.T) {
	ctx := context.Background()
	userID := uuid.New()
	subject := "orders/" + uuid.NewString()
	var reqID uuid.UUID = uuid.New()
	for i := 0; i < 3; i++ {
		d := newDelivery(userID, entity.LaneBulk, entity.ChannelInApp)
		d.RequestID = reqID
		d.Subject = &subject
		d.CreatedAt = d.CreatedAt.Add(time.Duration(i) * time.Second)
		if i == 2 {
			d.Template = "CART_ABANDONED"
			d.NextAttemptAt = d.NextAttemptAt.Add(time.Hour) // scheduled, not ready
		}
		require.NoError(t, testStore.Deliveries().Insert(ctx, d))
	}
	byReq, err := testStore.Deliveries().ListByRequest(ctx, reqID)
	require.NoError(t, err)
	assert.Len(t, byReq, 3)

	bySubject, err := testStore.Deliveries().ListBySubject(ctx, subject, "", 10)
	require.NoError(t, err)
	require.Len(t, bySubject, 3)
	assert.True(t, bySubject[0].CreatedAt.After(bySubject[2].CreatedAt), "newest first")
	bySubject, err = testStore.Deliveries().ListBySubject(ctx, subject, "CART_ABANDONED", 10)
	require.NoError(t, err)
	assert.Len(t, bySubject, 1)

	stats, err := testStore.Deliveries().PendingStats(ctx, time.Now().UTC())
	require.NoError(t, err)
	require.Len(t, stats, 6, "every lane × channel is reported")
	var bulkInApp entity.PendingStat
	for _, s := range stats {
		if s.Lane == entity.LaneBulk && s.Channel == entity.ChannelInApp {
			bulkInApp = s
		}
	}
	assert.GreaterOrEqual(t, bulkInApp.ReadyCount, int64(2))
	assert.GreaterOrEqual(t, bulkInApp.ScheduledCount, int64(1))
	assert.Greater(t, bulkInApp.OldestReadyAge, time.Duration(0))

	n, err := testStore.Deliveries().SkipPendingByUser(ctx, userID, entity.ReasonUserDeleted, time.Now().UTC())
	require.NoError(t, err)
	assert.EqualValues(t, 3, n)
	n, err = testStore.Deliveries().AnonymizeByUser(ctx, userID)
	require.NoError(t, err)
	assert.EqualValues(t, 3, n)
	d := getDelivery(t, byReq[0].ID)
	assert.Nil(t, d.UserID)
	assert.Nil(t, d.Subject)
}

func TestPostgresWithinTxRollsBack(t *testing.T) {
	ctx := context.Background()
	d := newDelivery(uuid.New(), entity.LaneBulk, entity.ChannelInApp)
	err := testStore.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		if err := tx.Deliveries().Insert(ctx, d); err != nil {
			return err
		}
		return fmt.Errorf("abort")
	})
	require.EqualError(t, err, "abort")
	_, err = testStore.Deliveries().GetByID(ctx, d.ID)
	assert.True(t, apperr.Is(err, apperr.ErrNotFound))

	ev := &entity.OutboxEvent{ID: uuid.New(), AggregateType: entity.OutboxAggregateType, AggregateID: uuid.NewString(),
		Type: entity.EventTypeDelivered, Payload: []byte(`{"specversion":"1.0"}`)}
	require.NoError(t, testStore.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		if err := tx.Deliveries().Insert(ctx, d); err != nil {
			return err
		}
		return tx.Outbox().Insert(ctx, ev)
	}))
	_, err = testStore.Deliveries().GetByID(ctx, d.ID)
	require.NoError(t, err)

	var fetched []entity.OutboxEvent
	require.NoError(t, testStore.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		rows, err := tx.Outbox().FetchUnpublished(ctx, 100)
		if err != nil {
			return err
		}
		fetched = rows
		return tx.Outbox().MarkPublished(ctx, []uuid.UUID{ev.ID}, time.Now().UTC())
	}))
	var found bool
	for _, r := range fetched {
		if r.ID == ev.ID {
			found = true
			assert.JSONEq(t, `{"specversion":"1.0"}`, string(r.Payload))
		}
	}
	assert.True(t, found)
	count, _, err := testStore.Outbox().Stats(ctx, time.Now().UTC())
	require.NoError(t, err)
	assert.Zero(t, count)
}

func TestPostgresInboxPagination(t *testing.T) {
	ctx := context.Background()
	userID := uuid.New()
	base := time.Now().UTC().Add(-time.Hour)
	for i := 0; i < 60; i++ {
		item := &entity.InboxItem{ID: uuid.New(), UserID: userID, Template: "ORDER_CONFIRMED",
			Title: fmt.Sprintf("item %d", i), Body: "body", CreatedAt: base.Add(time.Duration(i/2) * time.Second)} // pairs share created_at
		require.NoError(t, testStore.Inbox().Insert(ctx, item))
	}

	seen := map[uuid.UUID]bool{}
	var cursor *entity.InboxCursor
	for page := 0; page < 3; page++ {
		p, err := testStore.Inbox().List(ctx, userID, cursor, 20, false)
		require.NoError(t, err)
		require.Len(t, p.Items, 20, "page %d", page)
		for i, it := range p.Items {
			assert.False(t, seen[it.ID], "duplicate item across pages")
			seen[it.ID] = true
			if i > 0 {
				prev := p.Items[i-1]
				assert.False(t, it.CreatedAt.After(prev.CreatedAt), "created_at DESC")
			}
		}
		if page < 2 {
			require.NotNil(t, p.NextCursor, "page %d has a next cursor", page)
		} else {
			assert.Nil(t, p.NextCursor, "last page has no cursor")
		}
		cursor = p.NextCursor
	}
	assert.Len(t, seen, 60)

	// Read / archive / counters.
	unread, err := testStore.Inbox().CountUnread(ctx, userID)
	require.NoError(t, err)
	assert.EqualValues(t, 60, unread)

	first, err := testStore.Inbox().List(ctx, userID, nil, 1, true)
	require.NoError(t, err)
	id := first.Items[0].ID
	item, changed, err := testStore.Inbox().SetRead(ctx, userID, id, true, time.Now().UTC())
	require.NoError(t, err)
	assert.True(t, changed)
	assert.NotNil(t, item.ReadAt)
	_, changed, err = testStore.Inbox().SetRead(ctx, userID, id, true, time.Now().UTC())
	require.NoError(t, err)
	assert.False(t, changed, "already read")
	_, _, err = testStore.Inbox().SetRead(ctx, uuid.New(), id, true, time.Now().UTC())
	assert.True(t, apperr.Is(err, apperr.ErrNotFound), "another user's item is invisible")

	wasUnread, changed, err := testStore.Inbox().Archive(ctx, userID, id, time.Now().UTC())
	require.NoError(t, err)
	assert.True(t, changed)
	assert.False(t, wasUnread)
	_, changed, err = testStore.Inbox().Archive(ctx, userID, id, time.Now().UTC())
	require.NoError(t, err)
	assert.False(t, changed)

	other := first.Items[0]
	_ = other
	n, err := testStore.Inbox().MarkAllRead(ctx, userID, time.Now().UTC())
	require.NoError(t, err)
	assert.EqualValues(t, 59, n)
	unread, err = testStore.Inbox().CountUnread(ctx, userID)
	require.NoError(t, err)
	assert.Zero(t, unread)

	n, err = testStore.Inbox().DeleteByUser(ctx, userID)
	require.NoError(t, err)
	assert.EqualValues(t, 60, n)
}

func TestPostgresPreferenceCompareAndSet(t *testing.T) {
	ctx := context.Background()
	userID := uuid.New()
	_, err := testStore.Preferences().Get(ctx, userID)
	assert.True(t, apperr.Is(err, apperr.ErrNotFound))

	p := &entity.Preference{UserID: userID, EmailEnabled: true, InAppEnabled: true, MarketingEnabled: true,
		Timezone: ptr("Asia/Ho_Chi_Minh"), Version: 200}
	applied, err := testStore.Preferences().Upsert(ctx, p)
	require.NoError(t, err)
	assert.True(t, applied)

	older := *p
	older.Version = 100
	older.EmailEnabled = false
	applied, err = testStore.Preferences().Upsert(ctx, &older)
	require.NoError(t, err)
	assert.False(t, applied, "stale version is ignored")
	got, err := testStore.Preferences().Get(ctx, userID)
	require.NoError(t, err)
	assert.True(t, got.EmailEnabled)
	assert.EqualValues(t, 200, got.Version)

	newer := *p
	newer.Version = 300
	newer.EmailEnabled = false
	applied, err = testStore.Preferences().Upsert(ctx, &newer)
	require.NoError(t, err)
	assert.True(t, applied)

	require.NoError(t, testStore.Preferences().DisableMarketing(ctx, userID, 250, time.Now().UTC()))
	got, err = testStore.Preferences().Get(ctx, userID)
	require.NoError(t, err)
	assert.False(t, got.MarketingEnabled)
	assert.EqualValues(t, 300, got.Version, "version only bumps upwards")
	assert.False(t, got.EmailEnabled)

	fresh := uuid.New()
	require.NoError(t, testStore.Preferences().DisableMarketing(ctx, fresh, 10, time.Now().UTC()))
	got, err = testStore.Preferences().Get(ctx, fresh)
	require.NoError(t, err)
	assert.True(t, got.EmailEnabled)
	assert.True(t, got.InAppEnabled)
	assert.False(t, got.MarketingEnabled)
	assert.EqualValues(t, 10, got.Version)

	require.NoError(t, testStore.Preferences().Delete(ctx, fresh))
}

func TestPostgresSuppressionAndProviderEvents(t *testing.T) {
	ctx := context.Background()
	userID := uuid.New()
	ev := &entity.ProviderEvent{Provider: entity.ProviderSES, ProviderEventID: uuid.NewString(),
		ProviderMessageID: ptr("msg-" + uuid.NewString()), EventType: entity.ProviderEventBounce,
		Raw: []byte(`{"eventType":"Bounce"}`)}
	require.NoError(t, testStore.ProviderEvents().Insert(ctx, ev))
	assert.NotZero(t, ev.ID)
	assert.False(t, ev.ReceivedAt.IsZero())
	assert.Equal(t, entity.ProviderEventPending, ev.Status)

	var claimed []entity.ProviderEvent
	require.NoError(t, testStore.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		rows, err := tx.ProviderEvents().Claim(ctx, 100, time.Now().UTC())
		if err != nil {
			return err
		}
		claimed = rows
		for _, r := range rows {
			if r.ID == ev.ID {
				return tx.ProviderEvents().Reschedule(ctx, r.ID, r.ReceivedAt, 1, time.Now().UTC().Add(30*time.Second))
			}
		}
		return fmt.Errorf("event %d not claimed", ev.ID)
	}))
	assert.NotEmpty(t, claimed)
	list, err := testStore.ProviderEvents().ListByProviderMessage(ctx, *ev.ProviderMessageID)
	require.NoError(t, err)
	require.Len(t, list, 1)
	assert.Equal(t, entity.ProviderEventUnmatched, list[0].Status)
	assert.Equal(t, 1, list[0].Attempt)
	age, err := testStore.ProviderEvents().OldestUnmatchedAge(ctx, time.Now().UTC())
	require.NoError(t, err)
	assert.GreaterOrEqual(t, age, time.Duration(0))
	require.NoError(t, testStore.ProviderEvents().SetStatus(ctx, ev.ID, ev.ReceivedAt, entity.ProviderEventProcessed))

	s := &entity.Suppression{ID: uuid.New(), UserID: &userID, EmailHash: "hash-" + uuid.NewString(),
		Reason: entity.SuppressionHardBounce, Source: "ses", EvidenceEventID: &ev.ID}
	require.NoError(t, testStore.Suppressions().Insert(ctx, s))
	active, err := testStore.Suppressions().ActiveByUser(ctx, userID)
	require.NoError(t, err)
	require.Len(t, active, 1)
	byHash, err := testStore.Suppressions().ActiveByHash(ctx, s.EmailHash)
	require.NoError(t, err)
	assert.Len(t, byHash, 1)
	require.NoError(t, testStore.Suppressions().MarkSuperseded(ctx, s.ID, time.Now().UTC()))
	active, err = testStore.Suppressions().ActiveByUser(ctx, userID)
	require.NoError(t, err)
	assert.Empty(t, active)
	got, err := testStore.Suppressions().GetByID(ctx, s.ID)
	require.NoError(t, err)
	assert.NotNil(t, got.SupersededAt)
	n, err := testStore.Suppressions().DetachUser(ctx, userID)
	require.NoError(t, err)
	assert.EqualValues(t, 1, n)
	require.NoError(t, testStore.Suppressions().Delete(ctx, s.ID))
	assert.True(t, apperr.Is(testStore.Suppressions().Delete(ctx, s.ID), apperr.ErrNotFound))
}

func TestPostgresOpsAndControl(t *testing.T) {
	ctx := context.Background()
	key := "idem-" + uuid.NewString()
	existing, err := testStore.Ops().BeginIdempotent(ctx, key, "h1", time.Now().UTC())
	require.NoError(t, err)
	assert.Nil(t, existing)
	require.NoError(t, testStore.Ops().CompleteIdempotent(ctx, key, 200, []byte(`{"ok":true}`)))
	existing, err = testStore.Ops().BeginIdempotent(ctx, key, "h2", time.Now().UTC())
	require.NoError(t, err)
	require.NotNil(t, existing)
	assert.Equal(t, "h1", existing.RequestHash)
	assert.Equal(t, 200, existing.Status)
	assert.JSONEq(t, `{"ok":true}`, string(existing.Response))

	require.NoError(t, testStore.Ops().InsertAudit(ctx, &entity.AuditEntry{Principal: "notification-ops",
		Operation: "lane.pause", Params: []byte(`{"lane":"bulk"}`), Result: "ok"}))

	fake := clock.NewFake(time.Now().UTC())
	ctl := postgres.NewControl(testStore.Ops(), fake, time.Hour, nil)
	st, err := ctl.LaneState(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.False(t, st.IsPaused(entity.ChannelEmail), "missing key → default active")
	mode, err := ctl.ReplayMode(ctx)
	require.NoError(t, err)
	assert.False(t, mode.Enabled)

	require.NoError(t, ctl.SetLaneState(ctx, entity.LaneState{Lane: entity.LaneBulk, Email: entity.LanePaused,
		InApp: entity.LaneActive, UpdatedBy: ptr("ops"), Reason: ptr("provider incident")}))
	st, err = ctl.LaneState(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.True(t, st.IsPaused(entity.ChannelEmail), "write-through refreshes the cache")
	assert.False(t, st.IsPaused(entity.ChannelInApp))

	// Another replica sees it after its poll interval, not before.
	other := postgres.NewControl(testStore.Ops(), fake, time.Minute, nil)
	st, err = other.LaneState(ctx, entity.LaneBulk)
	require.NoError(t, err)
	assert.True(t, st.IsPaused(entity.ChannelEmail))
	require.NoError(t, ctl.SetLaneState(ctx, entity.LaneState{Lane: entity.LaneBulk, Email: entity.LaneActive, InApp: entity.LaneActive}))
	st, _ = other.LaneState(ctx, entity.LaneBulk)
	assert.True(t, st.IsPaused(entity.ChannelEmail), "cached until the poll interval elapses")
	fake.Advance(2 * time.Minute)
	st, _ = other.LaneState(ctx, entity.LaneBulk)
	assert.False(t, st.IsPaused(entity.ChannelEmail))

	exp := time.Now().UTC().Add(time.Hour)
	require.NoError(t, ctl.SetReplayMode(ctx, entity.ReplayMode{Enabled: true, IgnoreEventsOlderThan: 6 * time.Hour, ExpiresAt: &exp}, "ops"))
	mode, err = ctl.ReplayMode(ctx)
	require.NoError(t, err)
	assert.True(t, mode.ActiveAt(time.Now().UTC()))
	assert.Equal(t, 6*time.Hour, mode.IgnoreEventsOlderThan)
	require.NoError(t, ctl.SetReplayMode(ctx, entity.ReplayMode{}, "ops"))
	controls, err := testStore.Ops().ListControls(ctx, "lane:")
	require.NoError(t, err)
	assert.Contains(t, controls, "lane:bulk")
	assert.NotContains(t, controls, "replay_mode")
}

func TestPostgresWakeListener(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	listener := postgres.NewListener(ctx, testPool, []entity.Lane{entity.LaneCritical}, nil)

	// LISTEN is established asynchronously; notify until the wake lands.
	deadline := time.Now().Add(10 * time.Second)
	woken := false
	for time.Now().Before(deadline) && !woken {
		require.NoError(t, testStore.Maintenance().Notify(ctx, entity.LaneCritical))
		woken = listener.Wait(ctx, entity.LaneCritical, 200*time.Millisecond)
	}
	assert.True(t, woken, "NOTIFY wakes the lane")
	assert.False(t, listener.Wait(ctx, entity.LaneBulk, 50*time.Millisecond), "unlistened lane times out")
	cancel()
	select {
	case <-listener.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("listener did not stop")
	}
}

func TestPostgresPartitions(t *testing.T) {
	ctx := context.Background()
	now := time.Now().UTC()
	m := testStore.Maintenance()
	require.NoError(t, m.EnsurePartitions(ctx, now, 3))
	future, err := m.FutureMonths(ctx, now)
	require.NoError(t, err)
	require.Len(t, future, 4)
	for table, n := range future {
		assert.GreaterOrEqual(t, n, 2, "table %s", table)
	}

	// A partition four months back still holding a PENDING row is refused.
	old := now.AddDate(0, -4, 0)
	var created bool
	require.NoError(t, testPool.QueryRow(ctx, `SELECT notification_ensure_partition('notification_delivery', $1::date)`, old).Scan(&created))
	var partition string
	require.NoError(t, testPool.QueryRow(ctx, `SELECT notification_partition_name('notification_delivery', $1::date)`, old).Scan(&partition))

	d := newDelivery(uuid.New(), entity.LaneBulk, entity.ChannelEmail)
	d.CreatedAt = old
	d.UpdatedAt = old
	d.NextAttemptAt = old
	d.ExpiresAt = old.Add(time.Hour)
	require.NoError(t, testStore.Deliveries().Insert(ctx, d))

	retention := map[string]int{"notification_delivery": 90}
	dropped, refused, err := m.DropExpiredPartitions(ctx, now, retention)
	require.NoError(t, err)
	assert.Contains(t, refused, partition)
	assert.NotContains(t, dropped, partition)
	_, err = testStore.Deliveries().GetByID(ctx, d.ID)
	require.NoError(t, err, "refused partition keeps its rows")

	// Once nothing is queued the partition goes.
	_, err = testPool.Exec(ctx, `UPDATE notification_delivery SET status = 'FAILED' WHERE id = $1`, d.ID)
	require.NoError(t, err)
	dropped, refused, err = m.DropExpiredPartitions(ctx, now, retention)
	require.NoError(t, err)
	assert.Contains(t, dropped, partition)
	assert.NotContains(t, refused, partition)
	_, err = testStore.Deliveries().GetByID(ctx, d.ID)
	assert.True(t, apperr.Is(err, apperr.ErrNotFound))

	ratio, err := m.DeadTupleRatio(ctx, "notification_delivery")
	require.NoError(t, err)
	assert.GreaterOrEqual(t, ratio, 0.0)
	assert.LessOrEqual(t, ratio, 1.0)

	tables := make([]string, 0, len(future))
	for table := range future {
		tables = append(tables, table)
	}
	sort.Strings(tables)
	assert.Equal(t, []string{"inbox_item", "notification_delivery", "notification_request", "provider_event"}, tables)
}

func TestPostgresMigrateIsIdempotentAndReversible(t *testing.T) {
	ctx := context.Background()
	require.NoError(t, postgres.Migrate(ctx, testDB.DSN()), "second Up is a no-op")
	require.NoError(t, postgres.MigrateDown(ctx, testDB.DSN()), "one step down (publication)")
	var exists bool
	require.NoError(t, testPool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'notification_outbox_publication')`).Scan(&exists))
	assert.False(t, exists)
	require.NoError(t, postgres.Migrate(ctx, testDB.DSN()))
	require.NoError(t, testPool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'notification_outbox_publication')`).Scan(&exists))
	assert.True(t, exists)
}
