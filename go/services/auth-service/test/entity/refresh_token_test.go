package entity_test

import (
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/stretchr/testify/assert"
)

func TestNewRefreshToken(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id-123",
		"user-id-456",
		"hashed_token_value",
		"device-id-789",
		"Chrome on Windows",
		"192.168.1.1",
		24*time.Hour,
	)

	assert.Equal(t, "token-id-123", token.ID)
	assert.Equal(t, "user-id-456", token.UserID)
	assert.Equal(t, "hashed_token_value", token.TokenHash)
	assert.NotEmpty(t, token.FamilyID)
	assert.Nil(t, token.ReplacedBy)
	assert.Equal(t, "device-id-789", token.DeviceID)
	assert.Equal(t, "Chrome on Windows", token.DeviceInfo)
	assert.Equal(t, "192.168.1.1", token.IPAddress)
	assert.Nil(t, token.RevokedAt)
	assert.False(t, token.CreatedAt.IsZero())
	assert.False(t, token.ExpiresAt.IsZero())

	expectedExpiry := time.Now().Add(24 * time.Hour)
	assert.WithinDuration(t, expectedExpiry, token.ExpiresAt, 2*time.Second)
}

func TestRefreshToken_IsExpired(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id",
		"user-id",
		"hash",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	assert.False(t, token.IsExpired())

	token.ExpiresAt = time.Now().Add(-1 * time.Hour)
	assert.True(t, token.IsExpired())
}

func TestRefreshToken_IsActive(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id",
		"user-id",
		"hash",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	assert.True(t, token.IsActive())
	assert.False(t, token.IsRevoked())
	assert.False(t, token.IsExpired())

	token.Revoke()
	assert.False(t, token.IsActive())
	assert.True(t, token.IsRevoked())
}

func TestRefreshToken_IsRevoked(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id",
		"user-id",
		"hash",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	assert.False(t, token.IsRevoked())

	token.Revoke()
	assert.True(t, token.IsRevoked())
}

func TestRefreshToken_Revoke(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id",
		"user-id",
		"hash",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	assert.Nil(t, token.RevokedAt)

	token.Revoke()

	assert.NotNil(t, token.RevokedAt)
	assert.WithinDuration(t, time.Now(), *token.RevokedAt, time.Second)
}

func TestRefreshToken_WithinGracePeriod(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id",
		"user-id",
		"hash",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	assert.False(t, token.WithinGracePeriod())

	token.Revoke()
	assert.True(t, token.WithinGracePeriod())

	oldTime := time.Now().Add(-15 * time.Second)
	token.RevokedAt = &oldTime
	assert.False(t, token.WithinGracePeriod())
}

func TestRefreshToken_GracePeriodExpiresAt(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id",
		"user-id",
		"hash",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	zeroTime := time.Time{}
	assert.Equal(t, zeroTime, token.GracePeriodExpiresAt())

	revokeTime := time.Now()
	token.RevokedAt = &revokeTime
	expectedExpiry := revokeTime.Add(10 * time.Second)
	assert.WithinDuration(t, expectedExpiry, token.GracePeriodExpiresAt(), time.Second)
}

func TestRefreshToken_MarkReplaced(t *testing.T) {
	token := entity.NewRefreshToken(
		"token-id",
		"user-id",
		"hash",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	assert.Nil(t, token.ReplacedBy)

	token.MarkReplaced("new-token-id")

	assert.NotNil(t, token.ReplacedBy)
	assert.Equal(t, "new-token-id", *token.ReplacedBy)
}

func TestRefreshToken_FamilyIDUniqueness(t *testing.T) {
	// Test that tokens created with a delay have unique family IDs
	token1 := entity.NewRefreshToken(
		"token-1",
		"user-id",
		"hash1",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	// Small delay to ensure different timestamp
	time.Sleep(time.Nanosecond)

	token2 := entity.NewRefreshToken(
		"token-2",
		"user-id",
		"hash2",
		"device",
		"info",
		"ip",
		24*time.Hour,
	)

	// Family IDs should be different (may occasionally fail due to nanosecond resolution)
	// In production, use UUID library for guaranteed uniqueness
	assert.NotEqual(t, token1.FamilyID, token2.FamilyID, "Family IDs should be different")
}
