package entity_test

import (
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/stretchr/testify/assert"
)

func TestNewJWTKey(t *testing.T) {
	key := entity.NewJWTKey("key-123", "public_key_content", "private_key_content")

	assert.Equal(t, "key-123", key.Kid)
	assert.Equal(t, "public_key_content", key.PublicKey)
	assert.Equal(t, "private_key_content", key.PrivateKey)
	assert.Equal(t, entity.KeyStatusActive, key.Status)
	assert.False(t, key.CreatedAt.IsZero())
	assert.Nil(t, key.RotatedAt)
	assert.Nil(t, key.ExpiresAt)
}

func TestJWTKey_IsActive(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.True(t, key.IsActive())

	key.Status = entity.KeyStatusPhaseOut
	assert.False(t, key.IsActive())

	key.Status = entity.KeyStatusRetired
	assert.False(t, key.IsActive())
}

func TestJWTKey_IsPhaseOut(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.False(t, key.IsPhaseOut())

	key.Status = entity.KeyStatusPhaseOut
	assert.True(t, key.IsPhaseOut())

	key.Status = entity.KeyStatusRetired
	assert.False(t, key.IsPhaseOut())
}

func TestJWTKey_IsRetired(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.False(t, key.IsRetired())

	key.Status = entity.KeyStatusRetired
	assert.True(t, key.IsRetired())

	key.Status = entity.KeyStatusActive
	assert.False(t, key.IsRetired())
}

func TestJWTKey_CanSign(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.True(t, key.CanSign())

	key.Status = entity.KeyStatusPhaseOut
	assert.False(t, key.CanSign())

	key.Status = entity.KeyStatusRetired
	assert.False(t, key.CanSign())
}

func TestJWTKey_CanVerify(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.True(t, key.CanVerify())

	key.Status = entity.KeyStatusPhaseOut
	assert.True(t, key.CanVerify())

	key.Status = entity.KeyStatusRetired
	assert.False(t, key.CanVerify())
}

func TestJWTKey_SetPhaseOut(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.Equal(t, entity.KeyStatusActive, key.Status)
	assert.Nil(t, key.RotatedAt)

	key.SetPhaseOut()

	assert.Equal(t, entity.KeyStatusPhaseOut, key.Status)
	assert.NotNil(t, key.RotatedAt)
	assert.WithinDuration(t, time.Now(), *key.RotatedAt, time.Second)
}

func TestJWTKey_SetRetired(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")
	key.SetPhaseOut()

	assert.Equal(t, entity.KeyStatusPhaseOut, key.Status)

	key.SetRetired()

	assert.Equal(t, entity.KeyStatusRetired, key.Status)
	assert.NotNil(t, key.RotatedAt)
}

func TestJWTKey_SetExpiresAt(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.Nil(t, key.ExpiresAt)

	expiry := time.Now().Add(30 * 24 * time.Hour)
	key.SetExpiresAt(expiry)

	assert.NotNil(t, key.ExpiresAt)
	assert.Equal(t, expiry, *key.ExpiresAt)
}

func TestJWTKey_Lifecycle(t *testing.T) {
	key := entity.NewJWTKey("key-1", "pub", "priv")

	assert.True(t, key.IsActive())
	assert.True(t, key.CanSign())
	assert.True(t, key.CanVerify())

	key.SetPhaseOut()
	assert.True(t, key.IsPhaseOut())
	assert.False(t, key.CanSign())
	assert.True(t, key.CanVerify())

	key.SetRetired()
	assert.True(t, key.IsRetired())
	assert.False(t, key.CanSign())
	assert.False(t, key.CanVerify())
}
