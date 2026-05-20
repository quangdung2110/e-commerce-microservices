package repository_test

import (
	"context"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
	"github.com/ecommerce/auth-service/test/helper"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func setupAuditLogRepoTest(t *testing.T) (*postgres.AuditLogRepository, *helper.TestDatabase) {
	db := helper.SetupPostgresContainer(t)
	repo := postgres.NewAuditLogRepository(db.Pool)
	return repo, db
}

func TestAuditLogRepository_Create(t *testing.T) {
	repo, db := setupAuditLogRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	log := &entity.AuditLog{
		ID:            "550e8400-e29b-41d4-a716-446655440200",
		ActorID:       "user-123",
		ActorType:     entity.ActorUser,
		EventType:     "USER_LOGGED_IN",
		OccurredAt:    time.Now(),
		IPAddress:     "192.168.1.1",
		DeviceID:      "device-abc",
		DeviceInfo:    "Chrome on Windows",
		SecurityLevel: entity.SecurityLevelInfo,
		Payload:       []byte(`{"browser":"Chrome"}`),
		LogCategory:   entity.LogCategoryUser,
	}

	err := repo.Create(ctx, log)
	require.NoError(t, err)

	logs, err := repo.GetByActorID(ctx, "user-123", 10, 0)
	require.NoError(t, err)
	assert.Len(t, logs, 1)
	assert.Equal(t, log.ID, logs[0].ID)
}

func TestAuditLogRepository_GetByActorID(t *testing.T) {
	repo, db := setupAuditLogRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	actorID := "actor-get-by-actor"

	for i := 0; i < 5; i++ {
		log := &entity.AuditLog{
			ID:            "550e8400-e29b-41d4-a716-44665544021" + string(rune('0'+i)),
			ActorID:       actorID,
			ActorType:     entity.ActorUser,
			EventType:     "USER_ACTION",
			OccurredAt:    time.Now(),
			SecurityLevel: entity.SecurityLevelInfo,
			LogCategory:   entity.LogCategoryUser,
		}
		err := repo.Create(ctx, log)
		require.NoError(t, err)
	}

	logs, err := repo.GetByActorID(ctx, actorID, 10, 0)
	require.NoError(t, err)
	assert.Len(t, logs, 5)
}

func TestAuditLogRepository_GetByEventType(t *testing.T) {
	repo, db := setupAuditLogRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	eventType := "PASSWORD_CHANGED"

	for i := 0; i < 3; i++ {
		log := &entity.AuditLog{
			ID:            "550e8400-e29b-41d4-a716-44665544022" + string(rune('0'+i)),
			ActorID:       "user-" + string(rune('0'+i)),
			ActorType:     entity.ActorUser,
			EventType:     eventType,
			OccurredAt:    time.Now(),
			SecurityLevel: entity.SecurityLevelWarning,
			LogCategory:   entity.LogCategoryUser,
		}
		err := repo.Create(ctx, log)
		require.NoError(t, err)
	}

	logs, err := repo.GetByEventType(ctx, eventType, 10, 0)
	require.NoError(t, err)
	assert.Len(t, logs, 3)
}

func TestAuditLogRepository_GetByTimeRange(t *testing.T) {
	repo, db := setupAuditLogRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	now := time.Now()

	log := &entity.AuditLog{
		ID:            "550e8400-e29b-41d4-a716-446655440230",
		ActorID:       "user-time",
		ActorType:     entity.ActorUser,
		EventType:     "TEST_EVENT",
		OccurredAt:    now,
		SecurityLevel: entity.SecurityLevelInfo,
		LogCategory:   entity.LogCategoryUser,
	}
	err := repo.Create(ctx, log)
	require.NoError(t, err)

	logs, err := repo.GetByTimeRange(ctx, now.Add(-1*time.Hour), now.Add(1*time.Hour), 10, 0)
	require.NoError(t, err)
	assert.Len(t, logs, 1)
}

func TestAuditLogRepository_GetLoginHistory(t *testing.T) {
	repo, db := setupAuditLogRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	userID := "login-history-user"

	loginLog := &entity.AuditLog{
		ID:            "550e8400-e29b-41d4-a716-446655440240",
		ActorID:       userID,
		ActorType:     entity.ActorUser,
		EventType:     "USER_LOGGED_IN",
		OccurredAt:    time.Now(),
		SecurityLevel: entity.SecurityLevelInfo,
		LogCategory:   entity.LogCategoryUser,
	}
	err := repo.Create(ctx, loginLog)
	require.NoError(t, err)

	otherLog := &entity.AuditLog{
		ID:            "550e8400-e29b-41d4-a716-446655440241",
		ActorID:       userID,
		ActorType:     entity.ActorUser,
		EventType:     "OTHER_EVENT",
		OccurredAt:    time.Now(),
		SecurityLevel: entity.SecurityLevelInfo,
		LogCategory:   entity.LogCategoryUser,
	}
	err = repo.Create(ctx, otherLog)
	require.NoError(t, err)

	history, err := repo.GetLoginHistory(ctx, userID, 10)
	require.NoError(t, err)
	assert.Len(t, history, 1)
	assert.Equal(t, "USER_LOGGED_IN", history[0].EventType)
}

func TestAuditLogRepository_GetByFilters(t *testing.T) {
	repo, db := setupAuditLogRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	logs := []*entity.AuditLog{
		{
			ID:            "550e8400-e29b-41d4-a716-446655440250",
			ActorID:       "user-filter",
			ActorType:     entity.ActorUser,
			EventType:     "LOGIN",
			OccurredAt:    time.Now(),
			SecurityLevel: entity.SecurityLevelInfo,
			LogCategory:   entity.LogCategoryUser,
		},
		{
			ID:            "550e8400-e29b-41d4-a716-446655440251",
			ActorID:       "admin-filter",
			ActorType:     entity.ActorAdmin,
			EventType:     "ADMIN_ACTION",
			OccurredAt:    time.Now(),
			SecurityLevel: entity.SecurityLevelWarning,
			LogCategory:   entity.LogCategorySystem,
		},
	}
	for _, log := range logs {
		err := repo.Create(ctx, log)
		require.NoError(t, err)
	}

	filters := struct {
		ActorID       string
		ActorType     string
		EventType     string
		SecurityLevel string
		LogCategory   string
		From          time.Time
		To            time.Time
	}{
		ActorType: string(entity.ActorUser),
	}

	results, total, err := repo.GetByFilters(ctx, filters, 10, 0)
	require.NoError(t, err)
	assert.Equal(t, 1, total)
	assert.Len(t, results, 1)
	assert.Equal(t, "user-filter", results[0].ActorID)
}

func TestAuditLogRepository_DeleteOlderThan(t *testing.T) {
	repo, db := setupAuditLogRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	now := time.Now()

	oldLog := &entity.AuditLog{
		ID:            "550e8400-e29b-41d4-a716-446655440260",
		ActorID:       "old-user",
		ActorType:     entity.ActorUser,
		EventType:     "OLD_EVENT",
		OccurredAt:    now.Add(-200 * 24 * time.Hour),
		SecurityLevel: entity.SecurityLevelInfo,
		LogCategory:   entity.LogCategoryUser,
	}
	err := repo.Create(ctx, oldLog)
	require.NoError(t, err)

	recentLog := &entity.AuditLog{
		ID:            "550e8400-e29b-41d4-a716-446655440261",
		ActorID:       "recent-user",
		ActorType:     entity.ActorUser,
		EventType:     "RECENT_EVENT",
		OccurredAt:    now,
		SecurityLevel: entity.SecurityLevelInfo,
		LogCategory:   entity.LogCategoryUser,
	}
	err = repo.Create(ctx, recentLog)
	require.NoError(t, err)

	deleted, err := repo.DeleteOlderThan(ctx, now.Add(-180*24*time.Hour))
	require.NoError(t, err)
	assert.Equal(t, int64(1), deleted)

	remaining, err := repo.GetByActorID(ctx, "old-user", 10, 0)
	require.NoError(t, err)
	assert.Len(t, remaining, 0)

	recentRemaining, err := repo.GetByActorID(ctx, "recent-user", 10, 0)
	require.NoError(t, err)
	assert.Len(t, recentRemaining, 1)
}
