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

func setupRefreshTokenRepoTest(t *testing.T) (*postgres.RefreshTokenRepository, *postgres.UserRepository, *helper.TestDatabase) {
	db := helper.SetupPostgresContainer(t)
	tokenRepo := postgres.NewRefreshTokenRepository(db.Pool)
	userRepo := postgres.NewUserRepository(db.Pool)

	ctx := context.Background()
	email, _ := vo.NewEmail("tokenuser@example.com")
	user := &entity.User{
		ID:           "550e8400-e29b-41d4-a716-446655440100",
		Email:        *email,
		PasswordHash: "hash",
		Roles:        []string{"USER"},
		Status:       vo.StatusActive,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Revision:     0,
	}
	err := userRepo.Create(ctx, user)
	require.NoError(t, err)

	return tokenRepo, userRepo, db
}

func TestRefreshTokenRepository_Create(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	token := &entity.RefreshToken{
		ID:         "550e8400-e29b-41d4-a716-446655440101",
		UserID:     "550e8400-e29b-41d4-a716-446655440100",
		TokenHash:  "hashed_token_value_12345678901234567890123456789012345678901234567890123456",
		FamilyID:   "family-123",
		DeviceID:   "device-abc",
		DeviceInfo: "Chrome on Windows",
		IPAddress:  "192.168.1.1",
		ExpiresAt:  time.Now().Add(24 * time.Hour),
		CreatedAt:  time.Now(),
	}

	err := tokenRepo.Create(ctx, token)
	require.NoError(t, err)

	found, err := tokenRepo.GetByID(ctx, token.ID)
	require.NoError(t, err)
	assert.Equal(t, token.ID, found.ID)
	assert.Equal(t, token.UserID, found.UserID)
	assert.Equal(t, token.TokenHash, found.TokenHash)
}

func TestRefreshTokenRepository_GetByID(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	token := &entity.RefreshToken{
		ID:         "550e8400-e29b-41d4-a716-446655440102",
		UserID:     "550e8400-e29b-41d4-a716-446655440100",
		TokenHash:  "hash_123456789012345678901234567890123456789012345678901234567",
		FamilyID:   "family-456",
		DeviceID:   "device-xyz",
		ExpiresAt:  time.Now().Add(24 * time.Hour),
		CreatedAt:  time.Now(),
	}

	err := tokenRepo.Create(ctx, token)
	require.NoError(t, err)

	found, err := tokenRepo.GetByID(ctx, token.ID)
	require.NoError(t, err)
	assert.Equal(t, token.ID, found.ID)
}

func TestRefreshTokenRepository_GetByID_NotFound(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	_, err := tokenRepo.GetByID(ctx, "non-existent-id")
	assert.Error(t, err)
	assert.Equal(t, postgres.ErrNotFound, err)
}

func TestRefreshTokenRepository_GetByTokenHash(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	tokenHash := "unique_hash_123456789012345678901234567890123456789012345678901234"
	token := &entity.RefreshToken{
		ID:         "550e8400-e29b-41d4-a716-446655440103",
		UserID:     "550e8400-e29b-41d4-a716-446655440100",
		TokenHash:  tokenHash,
		FamilyID:   "family-789",
		DeviceID:   "device-def",
		ExpiresAt:  time.Now().Add(24 * time.Hour),
		CreatedAt:  time.Now(),
	}

	err := tokenRepo.Create(ctx, token)
	require.NoError(t, err)

	found, err := tokenRepo.GetByTokenHash(ctx, tokenHash)
	require.NoError(t, err)
	assert.Equal(t, token.ID, found.ID)
	assert.Equal(t, tokenHash, found.TokenHash)
}

func TestRefreshTokenRepository_GetActiveByUserID(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	userID := "550e8400-e29b-41d4-a716-446655440100"

	for i := 0; i < 3; i++ {
		token := &entity.RefreshToken{
			ID:         "550e8400-e29b-41d4-a716-44665544011" + string(rune('0'+i)),
			UserID:     userID,
			TokenHash:  "hash_active_" + string(rune('0'+i)) + "_123456789012345678901234567890123",
			FamilyID:   "family-active-" + string(rune('0'+i)),
			DeviceID:   "device-" + string(rune('0'+i)),
			ExpiresAt:  time.Now().Add(24 * time.Hour),
			CreatedAt:  time.Now(),
		}
		err := tokenRepo.Create(ctx, token)
		require.NoError(t, err)
	}

	activeTokens, err := tokenRepo.GetActiveByUserID(ctx, userID)
	require.NoError(t, err)
	assert.Len(t, activeTokens, 3)
}

func TestRefreshTokenRepository_Revoke(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	token := &entity.RefreshToken{
		ID:         "550e8400-e29b-41d4-a716-446655440110",
		UserID:     "550e8400-e29b-41d4-a716-446655440100",
		TokenHash:  "revoke_hash_12345678901234567890123456789012345678901234567890123",
		FamilyID:   "family-revoke",
		DeviceID:   "device-revoke",
		ExpiresAt:  time.Now().Add(24 * time.Hour),
		CreatedAt:  time.Now(),
	}

	err := tokenRepo.Create(ctx, token)
	require.NoError(t, err)

	err = tokenRepo.Revoke(ctx, token.ID)
	require.NoError(t, err)

	found, err := tokenRepo.GetByID(ctx, token.ID)
	require.NoError(t, err)
	assert.NotNil(t, found.RevokedAt)
}

func TestRefreshTokenRepository_RevokeAllForUser(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	userID := "550e8400-e29b-41d4-a716-446655440100"

	for i := 0; i < 3; i++ {
		token := &entity.RefreshToken{
			ID:         "550e8400-e29b-41d4-a716-44665544012" + string(rune('0'+i)),
			UserID:     userID,
			TokenHash:  "revoke_all_hash_" + string(rune('0'+i)) + "_123456789012345678901234567890",
			FamilyID:   "family-revoke-all-" + string(rune('0'+i)),
			DeviceID:   "device-revoke-all-" + string(rune('0'+i)),
			ExpiresAt:  time.Now().Add(24 * time.Hour),
			CreatedAt:  time.Now(),
		}
		err := tokenRepo.Create(ctx, token)
		require.NoError(t, err)
	}

	err := tokenRepo.RevokeAllForUser(ctx, userID)
	require.NoError(t, err)

	activeTokens, err := tokenRepo.GetActiveByUserID(ctx, userID)
	require.NoError(t, err)
	assert.Len(t, activeTokens, 0)
}

func TestRefreshTokenRepository_RevokeFamily(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	userID := "550e8400-e29b-41d4-a716-446655440100"
	familyID := "family-detect-theft"

	for i := 0; i < 3; i++ {
		token := &entity.RefreshToken{
			ID:         "550e8400-e29b-41d4-a716-44665544013" + string(rune('0'+i)),
			UserID:     userID,
			TokenHash:  "family_hash_" + string(rune('0'+i)) + "_1234567890123456789012345678901234",
			FamilyID:   familyID,
			DeviceID:   "device-family-" + string(rune('0'+i)),
			ExpiresAt:  time.Now().Add(24 * time.Hour),
			CreatedAt:  time.Now(),
		}
		err := tokenRepo.Create(ctx, token)
		require.NoError(t, err)
	}

	err := tokenRepo.RevokeFamily(ctx, familyID)
	require.NoError(t, err)

	activeTokens, err := tokenRepo.GetActiveByUserID(ctx, userID)
	require.NoError(t, err)
	assert.Len(t, activeTokens, 0)
}

func TestRefreshTokenRepository_UpdateReplacedBy(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()
	oldToken := &entity.RefreshToken{
		ID:         "550e8400-e29b-41d4-a716-446655440140",
		UserID:     "550e8400-e29b-41d4-a716-446655440100",
		TokenHash:  "old_token_hash_1234567890123456789012345678901234567890123456789012",
		FamilyID:   "family-replace",
		DeviceID:   "device-replace",
		ExpiresAt:  time.Now().Add(24 * time.Hour),
		CreatedAt:  time.Now(),
	}
	err := tokenRepo.Create(ctx, oldToken)
	require.NoError(t, err)

	newTokenID := "550e8400-e29b-41d4-a716-446655440141"

	err = tokenRepo.UpdateReplacedBy(ctx, oldToken.ID, newTokenID)
	require.NoError(t, err)

	found, err := tokenRepo.GetByID(ctx, oldToken.ID)
	require.NoError(t, err)
	require.NotNil(t, found.ReplacedBy)
	assert.Equal(t, newTokenID, *found.ReplacedBy)
}

func TestRefreshTokenRepository_DeleteExpired(t *testing.T) {
	tokenRepo, _, db := setupRefreshTokenRepoTest(t)
	defer db.Cleanup()

	ctx := context.Background()

	expiredToken := &entity.RefreshToken{
		ID:         "550e8400-e29b-41d4-a716-446655440150",
		UserID:     "550e8400-e29b-41d4-a716-446655440100",
		TokenHash:  "expired_hash_123456789012345678901234567890123456789012345678901234",
		FamilyID:   "family-expired",
		DeviceID:   "device-expired",
		ExpiresAt:  time.Now().Add(-48 * time.Hour),
		RevokedAt:  timePtr(time.Now().Add(-24 * time.Hour)),
		CreatedAt:  time.Now().Add(-72 * time.Hour),
	}
	err := tokenRepo.Create(ctx, expiredToken)
	require.NoError(t, err)

	count, err := tokenRepo.DeleteExpired(ctx, time.Now().Add(-24*time.Hour))
	require.NoError(t, err)
	assert.GreaterOrEqual(t, count, int64(1))

	_, err = tokenRepo.GetByID(ctx, expiredToken.ID)
	assert.Error(t, err)
}

func timePtr(t time.Time) *time.Time {
	return &t
}
