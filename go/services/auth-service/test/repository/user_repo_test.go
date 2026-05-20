package repository_test

import (
	"context"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/vo"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
	"github.com/ecommerce/auth-service/test/helper"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func setupUserRepoTest(t *testing.T) (*postgres.UserRepository, *helper.TestDatabase) {
	db := helper.SetupPostgresContainer(t)
	repo := postgres.NewUserRepository(db.Pool)
	return repo, db
}

func TestUserRepository_Create(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, err := vo.NewEmail("test@example.com")
	require.NoError(t, err)

	user := &entity.User{
		ID:                  "550e8400-e29b-41d4-a716-446655440000",
		Email:               *email,
		PasswordHash:        "hashed_password",
		Roles:               []string{"USER"},
		Status:              vo.StatusPendingVerification,
		FailedLoginAttempts: 0,
		CreatedAt:           time.Now(),
		UpdatedAt:           time.Now(),
		Revision:            0,
	}

	err = repo.Create(ctx, user)
	require.NoError(t, err)

	created, err := repo.GetByID(ctx, user.ID)
	require.NoError(t, err)
	assert.Equal(t, user.ID, created.ID)
	assert.Equal(t, "test@example.com", created.Email.String())
	assert.Equal(t, vo.StatusPendingVerification, created.Status)
	assert.Equal(t, []string{"USER"}, created.Roles)
}

func TestUserRepository_GetByID(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("getbyid@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440001",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusActive,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	found, err := repo.GetByID(ctx, user.ID)
	require.NoError(t, err)
	assert.Equal(t, user.ID, found.ID)
	assert.Equal(t, "getbyid@example.com", found.Email.String())
}

func TestUserRepository_GetByID_NotFound(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	_, err := repo.GetByID(ctx, "non-existent-id")
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}

func TestUserRepository_GetByEmail(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("getbyemail@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440002",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusActive,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	found, err := repo.GetByEmail(ctx, "getbyemail@example.com")
	require.NoError(t, err)
	assert.Equal(t, user.ID, found.ID)
}

func TestUserRepository_GetByEmail_NotFound(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	_, err := repo.GetByEmail(ctx, "nonexistent@example.com")
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}

func TestUserRepository_Update(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("update@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440003",
		Email:        *email,
		PasswordHash: "old_hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusPendingVerification,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	user.PasswordHash = "new_hash"
	user.Status = vo.StatusActive
	user.Roles = []string{"USER", "ADMIN"}

	err = repo.Update(ctx, user)
	require.NoError(t, err)

	found, err := repo.GetByID(ctx, user.ID)
	require.NoError(t, err)
	assert.Equal(t, "new_hash", found.PasswordHash)
	assert.Equal(t, vo.StatusActive, found.Status)
	assert.Equal(t, []string{"USER", "ADMIN"}, found.Roles)
}

func TestUserRepository_UpdateStatus(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("status@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440004",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusPendingVerification,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	err = repo.UpdateStatus(ctx, user.ID, string(vo.StatusActive))
	require.NoError(t, err)

	found, err := repo.GetByID(ctx, user.ID)
	require.NoError(t, err)
	assert.Equal(t, vo.StatusActive, found.Status)
}

func TestUserRepository_LockAccount(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("lock@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440005",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusActive,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	err = repo.LockAccount(ctx, user.ID)
	require.NoError(t, err)

	found, err := repo.GetByID(ctx, user.ID)
	require.NoError(t, err)
	assert.Equal(t, vo.StatusLocked, found.Status)
	assert.NotNil(t, found.LockedAt)
	assert.Equal(t, 0, found.FailedLoginAttempts)
}

func TestUserRepository_LockAccount_NotFound(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	err := repo.LockAccount(ctx, "non-existent-id")
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}

func TestUserRepository_UnlockAccount(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("unlock@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440006",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusActive,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	err = repo.LockAccount(ctx, user.ID)
	require.NoError(t, err)

	err = repo.UnlockAccount(ctx, user.ID)
	require.NoError(t, err)

	found, err := repo.GetByID(ctx, user.ID)
	require.NoError(t, err)
	assert.Equal(t, vo.StatusActive, found.Status)
	assert.Nil(t, found.LockedAt)
}

func TestUserRepository_BanAccount(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("ban@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440007",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusActive,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	err = repo.BanAccount(ctx, user.ID)
	require.NoError(t, err)

	found, err := repo.GetByID(ctx, user.ID)
	require.NoError(t, err)
	assert.Equal(t, vo.StatusBanned, found.Status)
}

func TestUserRepository_Delete(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	email, _ := vo.NewEmail("delete@example.com")

	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440008",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusActive,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}

	err := repo.Create(ctx, user)
	require.NoError(t, err)

	err = repo.Delete(ctx, user.ID)
	require.NoError(t, err)

	_, err = repo.GetByID(ctx, user.ID)
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}

func TestUserRepository_GetLockedUsers(t *testing.T) {
	repo, db := setupUserRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	for i := 0; i < 5; i++ {
		email, _ := vo.NewEmail("lockeduser" + string(rune('0'+i)) + "@example.com")
		user := &entity.User{
			ID:           "550e8400-e29b-41d4-a716-44665544001" + string(rune('0'+i)),
			Email:        *email,
			PasswordHash: "hash",
			Roles:        []string{"USER"},
			Status:       vo.StatusActive,
			CreatedAt:    time.Now(),
			UpdatedAt:    time.Now(),
			Revision:     0,
		}
		err := repo.Create(ctx, user)
		require.NoError(t, err)
	}

	for i := 0; i < 3; i++ {
		err := repo.LockAccount(ctx, "550e8400-e29b-41d4-a716-44665544001"+string(rune('0'+i)))
		require.NoError(t, err)
	}

	lockedUsers, err := repo.GetLockedUsers(ctx, 30*time.Minute, 10)
	require.NoError(t, err)
	assert.Len(t, lockedUsers, 3)
}
