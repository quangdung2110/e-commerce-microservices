package entity_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewAuditLog(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"USER_LOGGED_IN",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	assert.NotEmpty(t, log.ID)
	assert.Equal(t, "user-123", log.ActorID)
	assert.Equal(t, entity.ActorUser, log.ActorType)
	assert.Equal(t, "USER_LOGGED_IN", log.EventType)
	assert.Equal(t, entity.SecurityLevelInfo, log.SecurityLevel)
	assert.Equal(t, entity.LogCategoryUser, log.LogCategory)
	assert.False(t, log.OccurredAt.IsZero())
	assert.Empty(t, log.IPAddress)
	assert.Empty(t, log.DeviceID)
	assert.Empty(t, log.DeviceInfo)
	assert.Nil(t, log.Payload)
}

func TestAuditLog_WithIP(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"USER_LOGGED_IN",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	result := log.WithIP("192.168.1.100")

	assert.Same(t, log, result)
	assert.Equal(t, "192.168.1.100", log.IPAddress)
}

func TestAuditLog_WithIP_Chained(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"USER_LOGGED_IN",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	log.
		WithIP("192.168.1.100").
		WithDevice("device-123", "Chrome on Windows")

	assert.Equal(t, "192.168.1.100", log.IPAddress)
	assert.Equal(t, "device-123", log.DeviceID)
	assert.Equal(t, "Chrome on Windows", log.DeviceInfo)
}

func TestAuditLog_WithDevice(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"USER_LOGGED_IN",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	result := log.WithDevice("device-abc", "Safari on macOS")

	assert.Same(t, log, result)
	assert.Equal(t, "device-abc", log.DeviceID)
	assert.Equal(t, "Safari on macOS", log.DeviceInfo)
}

func TestAuditLog_WithPayload(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"USER_LOGGED_IN",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	payload := map[string]interface{}{
		"browser": "Chrome",
		"os":      "Windows",
		"ip":      "192.168.1.100",
	}

	result := log.WithPayload(payload)

	assert.Same(t, log, result)
	assert.NotNil(t, log.Payload)

	var decoded map[string]interface{}
	err := json.Unmarshal(log.Payload, &decoded)
	require.NoError(t, err)
	assert.Equal(t, "Chrome", decoded["browser"])
	assert.Equal(t, "Windows", decoded["os"])
	assert.Equal(t, "192.168.1.100", decoded["ip"])
}

func TestAuditLog_WithPayload_Invalid(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"USER_LOGGED_IN",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	ch := make(chan int)
	log.WithPayload(ch)

	assert.Nil(t, log.Payload)
}

func TestAuditLog_BuilderPattern(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"PASSWORD_CHANGED",
		entity.SecurityLevelWarning,
		entity.LogCategoryUser,
	).
		WithIP("10.0.0.1").
		WithDevice("phone-xyz", "iOS Safari").
		WithPayload(map[string]string{"method": "settings"})

	assert.Equal(t, "user-123", log.ActorID)
	assert.Equal(t, entity.ActorUser, log.ActorType)
	assert.Equal(t, "PASSWORD_CHANGED", log.EventType)
	assert.Equal(t, entity.SecurityLevelWarning, log.SecurityLevel)
	assert.Equal(t, entity.LogCategoryUser, log.LogCategory)
	assert.Equal(t, "10.0.0.1", log.IPAddress)
	assert.Equal(t, "phone-xyz", log.DeviceID)
	assert.Equal(t, "iOS Safari", log.DeviceInfo)
	assert.NotNil(t, log.Payload)
}

func TestActorType_Constants(t *testing.T) {
	assert.Equal(t, entity.ActorType("USER"), entity.ActorUser)
	assert.Equal(t, entity.ActorType("ADMIN"), entity.ActorAdmin)
	assert.Equal(t, entity.ActorType("SYSTEM"), entity.ActorSystem)
	assert.Equal(t, entity.ActorType("GUEST"), entity.ActorGuest)
}

func TestSecurityLevel_Constants(t *testing.T) {
	assert.Equal(t, entity.SecurityLevel("INFO"), entity.SecurityLevelInfo)
	assert.Equal(t, entity.SecurityLevel("WARNING"), entity.SecurityLevelWarning)
	assert.Equal(t, entity.SecurityLevel("CRITICAL"), entity.SecurityLevelCritical)
}

func TestLogCategory_Constants(t *testing.T) {
	assert.Equal(t, entity.LogCategory("USER"), entity.LogCategoryUser)
	assert.Equal(t, entity.LogCategory("SYSTEM"), entity.LogCategorySystem)
}

func TestAuditLog_OccurredAtIsUTC(t *testing.T) {
	log := entity.NewAuditLog(
		"user-123",
		entity.ActorUser,
		"TEST_EVENT",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	assert.Equal(t, time.UTC, log.OccurredAt.Location())
}
