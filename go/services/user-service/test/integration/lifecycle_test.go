//go:build integration

package integration_test

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/clock"
	"github.com/ecommerce/user-service/internal/common/keybuilder"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/service"
	"github.com/ecommerce/user-service/internal/domain/vo"
	"github.com/ecommerce/user-service/internal/infrastructure/postgres"
	redisadapter "github.com/ecommerce/user-service/internal/infrastructure/redis"
)

// recordingPublisher captures what would have been sent to Kafka.
type recordingPublisher struct {
	mu        sync.Mutex
	published []*event.Envelope
	failWith  error
}

func (p *recordingPublisher) Publish(_ context.Context, envelope *event.Envelope) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.failWith != nil {
		return p.failWith
	}
	p.published = append(p.published, envelope)
	return nil
}

func (p *recordingPublisher) events() []*event.Envelope {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]*event.Envelope(nil), p.published...)
}

// lifecycleFixture wires the real adapters around the lifecycle use cases.
type lifecycleFixture struct {
	clock          *clock.Fixed
	profiles       *postgres.ProfileRepository
	addresses      *postgres.AddressRepository
	preferences    *postgres.PreferencesRepository
	deletions      *postgres.DeletionRepository
	publisher      *recordingPublisher
	lifecycle      *service.LifecycleService
	reconciliation *service.ReconciliationService
}

func newLifecycleFixture(t *testing.T) *lifecycleFixture {
	t.Helper()

	logger := zap.NewNop()
	keys := keybuilder.New(testRedisConf.CachePrefix, testRedisConf.StatePrefix)
	cache := redisadapter.NewAddressCache(testRedis, keys, time.Hour, time.Minute, logger)
	publisher := &recordingPublisher{}

	fixture := &lifecycleFixture{
		clock:       clock.NewFixed(testNow),
		profiles:    postgres.NewProfileRepository(testPool),
		addresses:   postgres.NewAddressRepository(testPool),
		preferences: postgres.NewPreferencesRepository(testPool),
		deletions:   postgres.NewDeletionRepository(testPool),
		publisher:   publisher,
	}
	txManager := postgres.NewTxManager(testPool)

	fixture.lifecycle = service.NewLifecycleService(
		fixture.profiles, fixture.addresses, fixture.preferences, fixture.deletions,
		cache, txManager, fixture.clock, logger)
	fixture.reconciliation = service.NewReconciliationService(
		postgres.NewProjectionRepository(testPool),
		service.NewEventEmitter(publisher, logger), logger)

	return fixture
}

func TestBootstrapThenAnonymizeThenPurge(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	f := newLifecycleFixture(t)
	userID := uuid.New()

	bootstrap, err := f.lifecycle.Bootstrap(ctx, userID, testNow)
	require.NoError(t, err)
	require.True(t, bootstrap.ProfileCreated)
	require.True(t, bootstrap.PreferencesCreated)

	profile, err := f.profiles.GetByID(ctx, userID)
	require.NoError(t, err)
	_, err = profile.Apply(entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    stringPtr("Nguyen"),
		SetGender:    true,
		Gender:       vo.GenderMale,
	}, testNow)
	require.NoError(t, err)
	require.NoError(t, f.profiles.Update(ctx, profile))
	require.NoError(t, f.addresses.Insert(ctx, newAddress(t, userID, true)))

	deadline := testNow.Add(90 * 24 * time.Hour)
	anonymized, err := f.lifecycle.Anonymize(ctx, userID, testNow, deadline, vo.DeletionGDPRRequest.String())
	require.NoError(t, err)
	require.True(t, anonymized.ProfileAnonymized)
	require.Equal(t, int64(1), anonymized.AddressesDeleted)
	require.True(t, anonymized.PreferencesDeleted)

	scrubbed, err := f.profiles.GetByID(ctx, userID)
	require.NoError(t, err)
	require.True(t, scrubbed.IsAnonymized())

	// Before the deadline nothing is purged.
	early, err := f.lifecycle.PurgeDue(ctx, 100)
	require.NoError(t, err)
	require.Zero(t, early.Purged)

	f.clock.Set(deadline.Add(time.Minute))
	purged, err := f.lifecycle.PurgeDue(ctx, 100)
	require.NoError(t, err)
	require.Equal(t, 1, purged.Purged)

	_, err = f.profiles.GetByID(ctx, userID)
	require.Error(t, err, "the profile row is gone")
	exists, err := f.deletions.ExistsForUser(ctx, userID)
	require.NoError(t, err)
	require.False(t, exists, "the retention record goes with it")
}

func TestConcurrentPurgeRunsDoNotDoubleProcess(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	f := newLifecycleFixture(t)

	deadline := testNow.Add(time.Hour)
	for i := 0; i < 5; i++ {
		userID := uuid.New()
		_, err := f.lifecycle.Bootstrap(ctx, userID, testNow)
		require.NoError(t, err)
		_, err = f.lifecycle.Anonymize(ctx, userID, testNow, deadline, vo.DeletionGDPRRequest.String())
		require.NoError(t, err)
	}

	f.clock.Set(deadline.Add(time.Minute))

	// Two replicas sweep at the same time. FOR UPDATE SKIP LOCKED means each
	// record is claimed once; the other run skips it instead of blocking.
	var (
		wg      sync.WaitGroup
		mu      sync.Mutex
		purged  int
		results = make([]error, 0, 2)
	)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := f.lifecycle.PurgeDue(ctx, 100)
			mu.Lock()
			defer mu.Unlock()
			purged += result.Purged
			results = append(results, err)
		}()
	}
	wg.Wait()

	for _, err := range results {
		require.NoError(t, err)
	}
	require.Equal(t, 5, purged, "every record is purged exactly once")

	remaining, err := f.deletions.ListDue(ctx, deadline.Add(time.Hour), 100)
	require.NoError(t, err)
	require.Empty(t, remaining)
}

func TestBootstrapAfterDeletionDoesNotResurrectTheAccount(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	f := newLifecycleFixture(t)
	userID := uuid.New()

	_, err := f.lifecycle.Anonymize(ctx, userID, testNow,
		testNow.Add(90*24*time.Hour), vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	// An out-of-order verification event, or a replay of a very old one.
	result, err := f.lifecycle.Bootstrap(ctx, userID, testNow)
	require.NoError(t, err)
	require.True(t, result.SkippedDeleted)

	_, err = f.profiles.GetByID(ctx, userID)
	require.Error(t, err)
}

func TestReconciliationSkipsDeletedUsers(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	f := newLifecycleFixture(t)

	active := uuid.New()
	_, err := f.lifecycle.Bootstrap(ctx, active, testNow)
	require.NoError(t, err)

	deleted := uuid.New()
	_, err = f.lifecycle.Bootstrap(ctx, deleted, testNow)
	require.NoError(t, err)
	_, err = f.lifecycle.Anonymize(ctx, deleted, testNow,
		testNow.Add(90*24*time.Hour), vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	result, err := f.reconciliation.Reconcile(ctx, 100, 0)
	require.NoError(t, err)
	require.Equal(t, 1, result.Scanned, "the anonymized profile is excluded")
	require.Equal(t, 2, result.Published, "one profile event and one preferences event")

	for _, envelope := range f.publisher.events() {
		require.Equal(t, active.String(), envelope.Key,
			"a user in the retention window is never republished (ADR-007)")
	}
}

func TestReconciliationRepublishesTheStoredTimestamp(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	f := newLifecycleFixture(t)

	userID := uuid.New()
	_, err := f.lifecycle.Bootstrap(ctx, userID, testNow)
	require.NoError(t, err)

	profile, err := f.profiles.GetByID(ctx, userID)
	require.NoError(t, err)
	_, err = profile.Apply(entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    stringPtr("Nguyen"),
	}, testNow)
	require.NoError(t, err)
	require.NoError(t, f.profiles.Update(ctx, profile))

	_, err = f.reconciliation.Reconcile(ctx, 100, 0)
	require.NoError(t, err)

	events := f.publisher.events()
	require.NotEmpty(t, events)
	data, ok := events[0].Data.(event.ProfileUpdatedData)
	require.True(t, ok)
	require.Equal(t, "Nguyen", data.SenderName)
	require.Equal(t, event.FormatTime(profile.UpdatedAt), data.UpdatedAt,
		"the corrective snapshot keeps what PostgreSQL holds")
}
