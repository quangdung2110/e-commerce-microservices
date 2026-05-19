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

func setupOutboxRepoTest(t *testing.T) (*postgres.OutboxRepository, *helper.TestDatabase) {
	db := helper.SetupPostgresContainer(t)
	repo := postgres.NewOutboxRepository(db.Pool)
	return repo, db
}

func TestOutboxRepository_Create(t *testing.T) {
	repo, db := setupOutboxRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	event := &entity.OutboxEvent{
		EventID:       "550e8400-e29b-41d4-a716-446655440300",
		EventType:     "user.created",
		AggregateType: "user",
		AggregateID:   "user-123",
		PartitionKey:  "user-123",
		Payload:       []byte(`{"email":"user@example.com"}`),
		CreatedAt:     time.Now(),
	}

	err := repo.Create(ctx, event)
	require.NoError(t, err)

	events, err := repo.GetPending(ctx, 10)
	require.NoError(t, err)
	assert.Len(t, events, 1)
	assert.Equal(t, event.EventID, events[0].EventID)
}

func TestOutboxRepository_GetPending(t *testing.T) {
	repo, db := setupOutboxRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	for i := 0; i < 5; i++ {
		event := &entity.OutboxEvent{
			EventID:       "550e8400-e29b-41d4-a716-44665544031" + string(rune('0'+i)),
			EventType:     "test.event",
			AggregateType: "test",
			AggregateID:   "aggregate-" + string(rune('0'+i)),
			PartitionKey:  "aggregate-" + string(rune('0'+i)),
			Payload:       []byte(`{"index":` + string(rune('0'+i)) + `}`),
			CreatedAt:     time.Now(),
		}
		err := repo.Create(ctx, event)
		require.NoError(t, err)
	}

	events, err := repo.GetPending(ctx, 3)
	require.NoError(t, err)
	assert.Len(t, events, 3)
}

func TestOutboxRepository_GetPending_OrderedByCreatedAt(t *testing.T) {
	repo, db := setupOutboxRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	now := time.Now()

	event1 := &entity.OutboxEvent{
		EventID:       "550e8400-e29b-41d4-a716-446655440320",
		EventType:     "event.first",
		AggregateType: "test",
		AggregateID:   "first",
		PartitionKey:  "first",
		Payload:       []byte(`{}`),
		CreatedAt:     now.Add(-2 * time.Hour),
	}
	err := repo.Create(ctx, event1)
	require.NoError(t, err)

	event2 := &entity.OutboxEvent{
		EventID:       "550e8400-e29b-41d4-a716-446655440321",
		EventType:     "event.second",
		AggregateType: "test",
		AggregateID:   "second",
		PartitionKey:  "second",
		Payload:       []byte(`{}`),
		CreatedAt:     now.Add(-1 * time.Hour),
	}
	err = repo.Create(ctx, event2)
	require.NoError(t, err)

	events, err := repo.GetPending(ctx, 10)
	require.NoError(t, err)
	assert.Len(t, events, 2)
	assert.Equal(t, "event.first", events[0].EventType)
	assert.Equal(t, "event.second", events[1].EventType)
}

func TestOutboxRepository_Delete(t *testing.T) {
	repo, db := setupOutboxRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	event := &entity.OutboxEvent{
		EventID:       "550e8400-e29b-41d4-a716-446655440330",
		EventType:     "event.to.delete",
		AggregateType: "test",
		AggregateID:   "delete-test",
		PartitionKey:  "delete-test",
		Payload:       []byte(`{}`),
		CreatedAt:     time.Now(),
	}
	err := repo.Create(ctx, event)
	require.NoError(t, err)

	err = repo.Delete(ctx, event.EventID)
	require.NoError(t, err)

	events, err := repo.GetPending(ctx, 10)
	require.NoError(t, err)
	assert.Len(t, events, 0)
}

func TestOutboxRepository_Delete_NotFound(t *testing.T) {
	repo, db := setupOutboxRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	err := repo.Delete(ctx, "non-existent-id")
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}

func TestOutboxRepository_DeleteOld(t *testing.T) {
	repo, db := setupOutboxRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	now := time.Now()

	oldEvent := &entity.OutboxEvent{
		EventID:       "550e8400-e29b-41d4-a716-446655440340",
		EventType:     "old.event",
		AggregateType: "test",
		AggregateID:   "old",
		PartitionKey:  "old",
		Payload:       []byte(`{}`),
		CreatedAt:     now.Add(-20 * 24 * time.Hour),
	}
	err := repo.Create(ctx, oldEvent)
	require.NoError(t, err)

	recentEvent := &entity.OutboxEvent{
		EventID:       "550e8400-e29b-41d4-a716-446655440341",
		EventType:     "recent.event",
		AggregateType: "test",
		AggregateID:   "recent",
		PartitionKey:  "recent",
		Payload:       []byte(`{}`),
		CreatedAt:     now,
	}
	err = repo.Create(ctx, recentEvent)
	require.NoError(t, err)

	deleted, err := repo.DeleteOld(ctx, now.Add(-14*24*time.Hour))
	require.NoError(t, err)
	assert.Equal(t, int64(1), deleted)

	events, err := repo.GetPending(ctx, 10)
	require.NoError(t, err)
	assert.Len(t, events, 1)
	assert.Equal(t, "recent.event", events[0].EventType)
}

func TestOutboxRepository_TransactionalOutbox(t *testing.T) {
	repo, db := setupOutboxRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	events := []*entity.OutboxEvent{
		{
			EventID:       "550e8400-e29b-41d4-a716-446655440350",
			EventType:     "user.created",
			AggregateType: "user",
			AggregateID:   "new-user",
			PartitionKey:  "new-user",
			Payload:       []byte(`{"action":"created"}`),
			CreatedAt:     time.Now(),
		},
		{
			EventID:       "550e8400-e29b-41d4-a716-446655440351",
			EventType:     "email.sent",
			AggregateType: "email",
			AggregateID:   "email-123",
			PartitionKey:  "new-user",
			Payload:       []byte(`{"user_id":"new-user"}`),
			CreatedAt:     time.Now(),
		},
	}

	for _, event := range events {
		err := repo.Create(ctx, event)
		require.NoError(t, err)
	}

	pending, err := repo.GetPending(ctx, 10)
	require.NoError(t, err)
	assert.Len(t, pending, 2)

	for _, event := range pending {
		err := repo.Delete(ctx, event.EventID)
		require.NoError(t, err)
	}

	remaining, err := repo.GetPending(ctx, 10)
	require.NoError(t, err)
	assert.Len(t, remaining, 0)
}
