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

func setupJWTKeyRepoTest(t *testing.T) (*postgres.JWTKeyRepository, *helper.TestDatabase) {
	db := helper.SetupPostgresContainer(t)
	repo := postgres.NewJWTKeyRepository(db.Pool)
	return repo, db
}

func TestJWTKeyRepository_Create(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	key := &entity.JWTKey{
		Kid:        "key-001",
		PublicKey:  "public_key_content",
		PrivateKey: "private_key_content",
		Status:     entity.KeyStatusActive,
		CreatedAt:  time.Now(),
	}

	err := repo.Create(ctx, key)
	require.NoError(t, err)

	found, err := repo.GetByKid(ctx, key.Kid)
	require.NoError(t, err)
	assert.Equal(t, key.Kid, found.Kid)
	assert.Equal(t, key.PublicKey, found.PublicKey)
	assert.Equal(t, key.PrivateKey, found.PrivateKey)
}

func TestJWTKeyRepository_GetActive(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	key1 := &entity.JWTKey{
		Kid:        "key-old",
		PublicKey:  "pub1",
		PrivateKey: "priv1",
		Status:     entity.KeyStatusRetired,
		CreatedAt:  time.Now().Add(-48 * time.Hour),
	}
	err := repo.Create(ctx, key1)
	require.NoError(t, err)

	key2 := &entity.JWTKey{
		Kid:        "key-active",
		PublicKey:  "pub2",
		PrivateKey: "priv2",
		Status:     entity.KeyStatusActive,
		CreatedAt:  time.Now(),
	}
	err = repo.Create(ctx, key2)
	require.NoError(t, err)

	active, err := repo.GetActive(ctx)
	require.NoError(t, err)
	assert.Equal(t, "key-active", active.Kid)
}

func TestJWTKeyRepository_GetAllForSigning(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	for i := 0; i < 3; i++ {
		key := &entity.JWTKey{
			Kid:        "sign-key-" + string(rune('0'+i)),
			PublicKey:  "pub_sign_" + string(rune('0'+i)),
			PrivateKey: "priv_sign_" + string(rune('0'+i)),
			Status:     entity.KeyStatusActive,
			CreatedAt:  time.Now(),
		}
		err := repo.Create(ctx, key)
		require.NoError(t, err)
	}

	phaseOutKey := &entity.JWTKey{
		Kid:        "sign-key-phased",
		PublicKey:  "pub_phase",
		PrivateKey: "priv_phase",
		Status:     entity.KeyStatusPhaseOut,
		CreatedAt:  time.Now(),
	}
	err := repo.Create(ctx, phaseOutKey)
	require.NoError(t, err)

	keys, err := repo.GetAllForSigning(ctx)
	require.NoError(t, err)
	assert.Len(t, keys, 3)
	for _, k := range keys {
		assert.True(t, k.IsActive())
	}
}

func TestJWTKeyRepository_GetAllForVerification(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	activeKey := &entity.JWTKey{
		Kid:        "verify-key-active",
		PublicKey:  "pub_active",
		PrivateKey: "priv_active",
		Status:     entity.KeyStatusActive,
		CreatedAt:  time.Now(),
	}
	err := repo.Create(ctx, activeKey)
	require.NoError(t, err)

	phaseOutKey := &entity.JWTKey{
		Kid:        "verify-key-phaseout",
		PublicKey:  "pub_phaseout",
		PrivateKey: "priv_phaseout",
		Status:     entity.KeyStatusPhaseOut,
		CreatedAt:  time.Now(),
	}
	err = repo.Create(ctx, phaseOutKey)
	require.NoError(t, err)

	retiredKey := &entity.JWTKey{
		Kid:        "verify-key-retired",
		PublicKey:  "pub_retired",
		PrivateKey: "priv_retired",
		Status:     entity.KeyStatusRetired,
		CreatedAt:  time.Now(),
	}
	err = repo.Create(ctx, retiredKey)
	require.NoError(t, err)

	keys, err := repo.GetAllForVerification(ctx)
	require.NoError(t, err)
	assert.Len(t, keys, 2)
}

func TestJWTKeyRepository_GetByKid(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	key := &entity.JWTKey{
		Kid:        "unique-key-id",
		PublicKey:  "pub_unique",
		PrivateKey: "priv_unique",
		Status:     entity.KeyStatusActive,
		CreatedAt:  time.Now(),
	}
	err := repo.Create(ctx, key)
	require.NoError(t, err)

	found, err := repo.GetByKid(ctx, "unique-key-id")
	require.NoError(t, err)
	assert.Equal(t, key.Kid, found.Kid)
}

func TestJWTKeyRepository_GetByKid_NotFound(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	_, err := repo.GetByKid(ctx, "non-existent-key")
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}

func TestJWTKeyRepository_UpdateStatus(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	key := &entity.JWTKey{
		Kid:        "status-update-key",
		PublicKey:  "pub_status",
		PrivateKey: "priv_status",
		Status:     entity.KeyStatusActive,
		CreatedAt:  time.Now(),
	}
	err := repo.Create(ctx, key)
	require.NoError(t, err)

	err = repo.UpdateStatus(ctx, key.Kid, string(entity.KeyStatusPhaseOut))
	require.NoError(t, err)

	found, err := repo.GetByKid(ctx, key.Kid)
	require.NoError(t, err)
	assert.Equal(t, entity.KeyStatusPhaseOut, found.Status)
	assert.NotNil(t, found.RotatedAt)
}

func TestJWTKeyRepository_GetPhaseOutKeys(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	recentKey := &entity.JWTKey{
		Kid:       "recent-phaseout",
		PublicKey: "pub_recent",
		Status:    entity.KeyStatusPhaseOut,
		CreatedAt: time.Now().Add(-1 * time.Hour),
	}
	now := time.Now()
	recentKey.RotatedAt = &now
	err := repo.Create(ctx, recentKey)
	require.NoError(t, err)

	oldKey := &entity.JWTKey{
		Kid:       "old-phaseout",
		PublicKey: "pub_old",
		Status:    entity.KeyStatusPhaseOut,
		CreatedAt: time.Now().Add(-48 * time.Hour),
	}
	oldRotated := time.Now().Add(-48 * time.Hour)
	oldKey.RotatedAt = &oldRotated
	err = repo.Create(ctx, oldKey)
	require.NoError(t, err)

	phaseOutKeys, err := repo.GetPhaseOutKeys(ctx, time.Now().Add(-24*time.Hour))
	require.NoError(t, err)
	assert.Len(t, phaseOutKeys, 1)
	assert.Equal(t, "old-phaseout", phaseOutKeys[0].Kid)
}

func TestJWTKeyRepository_Delete(t *testing.T) {
	repo, db := setupJWTKeyRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	key := &entity.JWTKey{
		Kid:        "delete-key",
		PublicKey:  "pub_delete",
		PrivateKey: "priv_delete",
		Status:     entity.KeyStatusRetired,
		CreatedAt:  time.Now(),
	}
	err := repo.Create(ctx, key)
	require.NoError(t, err)

	err = repo.Delete(ctx, key.Kid)
	require.NoError(t, err)

	_, err = repo.GetByKid(ctx, key.Kid)
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}
