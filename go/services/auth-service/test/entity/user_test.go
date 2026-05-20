package entity_test

import (
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/vo"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewUser(t *testing.T) {
	email, err := vo.NewEmail("test@example.com")
	require.NoError(t, err)

	user := entity.NewUser(*email, "hashed_password")

	assert.Equal(t, "test@example.com", user.Email.String())
	assert.Equal(t, "hashed_password", user.PasswordHash)
	assert.Equal(t, vo.StatusPendingVerification, user.Status)
	assert.Equal(t, []string{"USER"}, user.Roles)
	assert.Nil(t, user.LockedAt)
	assert.Equal(t, 0, user.FailedLoginAttempts)
	assert.Nil(t, user.EmailVerifiedAt)
	assert.Equal(t, int64(0), user.Revision)
	assert.False(t, user.CreatedAt.IsZero())
	assert.False(t, user.UpdatedAt.IsZero())
}

func TestUser_IsActive(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.False(t, user.IsActive())

	user.Status = vo.StatusActive
	assert.True(t, user.IsActive())

	user.Status = vo.StatusLocked
	assert.False(t, user.IsActive())

	user.Status = vo.StatusBanned
	assert.False(t, user.IsActive())
}

func TestUser_IsLocked(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.False(t, user.IsLocked())

	user.Status = vo.StatusLocked
	assert.True(t, user.IsLocked())

	user.Status = vo.StatusActive
	assert.False(t, user.IsLocked())
}

func TestUser_IsBanned(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.False(t, user.IsBanned())

	user.Status = vo.StatusBanned
	assert.True(t, user.IsBanned())

	user.Status = vo.StatusActive
	assert.False(t, user.IsBanned())
}

func TestUser_IsPendingVerification(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.True(t, user.IsPendingVerification())

	user.Status = vo.StatusActive
	assert.False(t, user.IsPendingVerification())
}

func TestUser_IsEmailVerified(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.False(t, user.IsEmailVerified())

	now := time.Now()
	user.EmailVerifiedAt = &now
	assert.True(t, user.IsEmailVerified())
}

func TestUser_CanLogin(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.False(t, user.CanLogin())
	assert.False(t, user.IsActive())
	assert.False(t, user.IsEmailVerified())

	user.Status = vo.StatusActive
	assert.False(t, user.CanLogin())
	assert.False(t, user.IsEmailVerified())

	now := time.Now()
	user.EmailVerifiedAt = &now
	assert.True(t, user.CanLogin())
	assert.True(t, user.IsActive())
	assert.True(t, user.IsEmailVerified())
}

func TestUser_CanBeUnlocked(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.False(t, user.CanBeUnlocked())
	assert.Nil(t, user.LockedAt)

	user.Status = vo.StatusLocked
	user.LockedAt = nil
	assert.False(t, user.CanBeUnlocked())

	recentLock := time.Now().Add(-15 * time.Minute)
	user.LockedAt = &recentLock
	assert.False(t, user.CanBeUnlocked())

	oldLock := time.Now().Add(-35 * time.Minute)
	user.LockedAt = &oldLock
	assert.True(t, user.CanBeUnlocked())
}

func TestUser_Activate(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	user.Activate()

	assert.Equal(t, vo.StatusActive, user.Status)
	assert.Nil(t, user.EmailVerifiedAt)
	assert.Equal(t, int64(1), user.Revision)
}

func TestUser_SetActive(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")
	user.FailedLoginAttempts = 5
	now := time.Now()
	user.LockedAt = &now

	user.SetActive()

	assert.Equal(t, vo.StatusActive, user.Status)
	assert.Nil(t, user.LockedAt)
	assert.Equal(t, 0, user.FailedLoginAttempts)
	assert.Equal(t, int64(1), user.Revision)
}

func TestUser_SetEmailVerified(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	user.SetEmailVerified()

	assert.Equal(t, vo.StatusActive, user.Status)
	assert.NotNil(t, user.EmailVerifiedAt)
	assert.Equal(t, int64(1), user.Revision)
}

func TestUser_Lock(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	user.Lock()

	assert.Equal(t, vo.StatusLocked, user.Status)
	assert.Equal(t, int64(1), user.Revision)
}

func TestUser_SetLocked(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")
	user.FailedLoginAttempts = 4

	user.SetLocked()

	assert.Equal(t, vo.StatusLocked, user.Status)
	assert.NotNil(t, user.LockedAt)
	assert.Equal(t, 0, user.FailedLoginAttempts)
	assert.Equal(t, int64(1), user.Revision)
}

func TestUser_Unlock(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")
	user.Status = vo.StatusLocked
	now := time.Now()
	user.LockedAt = &now
	user.FailedLoginAttempts = 5

	user.Unlock()

	assert.Equal(t, vo.StatusActive, user.Status)
	assert.Nil(t, user.LockedAt)
	assert.Equal(t, 0, user.FailedLoginAttempts)
	assert.Equal(t, int64(1), user.Revision)
}

func TestUser_Ban(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	user.Ban()

	assert.Equal(t, vo.StatusBanned, user.Status)
	assert.Equal(t, int64(1), user.Revision)
}

func TestUser_IncrementFailedAttempts(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")
	user.Status = vo.StatusActive // Set to ACTIVE so lock can happen
	assert.Equal(t, 0, user.FailedLoginAttempts)
	assert.Equal(t, vo.StatusActive, user.Status)

	// Increment 4 times - should not lock yet
	for i := 0; i < 4; i++ {
		user.IncrementFailedAttempts()
	}
	assert.Equal(t, 4, user.FailedLoginAttempts)
	assert.Equal(t, vo.StatusActive, user.Status)

	// 5th attempt - should lock and reset FailedLoginAttempts to 0
	user.IncrementFailedAttempts()
	assert.Equal(t, vo.StatusLocked, user.Status)
	assert.Equal(t, 0, user.FailedLoginAttempts) // Reset to 0 when locked
	assert.NotNil(t, user.LockedAt)
}

func TestUser_ResetFailedAttempts(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")
	user.FailedLoginAttempts = 5

	user.ResetFailedAttempts()

	assert.Equal(t, 0, user.FailedLoginAttempts)
}

func TestUser_RoleManagement(t *testing.T) {
	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "password")

	assert.True(t, user.HasRole("USER"))
	assert.False(t, user.HasRole("ADMIN"))

	user.AddRole("ADMIN")
	assert.True(t, user.HasRole("USER"))
	assert.True(t, user.HasRole("ADMIN"))
	assert.Len(t, user.Roles, 2)

	user.AddRole("ADMIN")
	assert.Len(t, user.Roles, 2)

	user.RemoveRole("USER")
	assert.False(t, user.HasRole("USER"))
	assert.True(t, user.HasRole("ADMIN"))

	user.RemoveRole("SUPERADMIN")
	assert.Len(t, user.Roles, 1)
}
