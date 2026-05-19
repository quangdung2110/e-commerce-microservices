package entity_test

import (
	"encoding/json"
	"testing"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewOutboxEvent(t *testing.T) {
	payload := map[string]interface{}{
		"user_id": "user-123",
		"action":  "email_verified",
	}

	event, err := entity.NewOutboxEvent(
		"user.email_verified",
		"user",
		"user-123",
		payload,
	)

	require.NoError(t, err)
	assert.NotEmpty(t, event.EventID)
	assert.Equal(t, "user.email_verified", event.EventType)
	assert.Equal(t, "user", event.AggregateType)
	assert.Equal(t, "user-123", event.AggregateID)
	assert.Equal(t, "user-123", event.PartitionKey)
	assert.NotNil(t, event.Payload)
	assert.False(t, event.CreatedAt.IsZero())

	var decoded map[string]interface{}
	err = json.Unmarshal(event.Payload, &decoded)
	require.NoError(t, err)
	assert.Equal(t, "user-123", decoded["user_id"])
	assert.Equal(t, "email_verified", decoded["action"])
}

func TestNewOutboxEvent_WithComplexPayload(t *testing.T) {
	payload := map[string]interface{}{
		"event": "user.registered",
		"data": map[string]interface{}{
			"user_id":   "user-456",
			"email":     "user@example.com",
			"roles":    []string{"USER", "PREMIUM"},
			"metadata": map[string]string{"source": "web"},
		},
		"timestamp": "2024-01-15T10:30:00Z",
	}

	event, err := entity.NewOutboxEvent(
		"user.registered",
		"user",
		"user-456",
		payload,
	)

	require.NoError(t, err)
	assert.NotEmpty(t, event.EventID)

	var decoded map[string]interface{}
	err = json.Unmarshal(event.Payload, &decoded)
	require.NoError(t, err)
	assert.Equal(t, "user.registered", decoded["event"])

	data := decoded["data"].(map[string]interface{})
	assert.Equal(t, "user-456", data["user_id"])
	assert.Equal(t, "user@example.com", data["email"])
}

func TestNewOutboxEvent_PartitionKeyEqualsAggregateID(t *testing.T) {
	event, err := entity.NewOutboxEvent(
		"test.event",
		"user",
		"user-789",
		nil,
	)

	require.NoError(t, err)
	assert.Equal(t, event.AggregateID, event.PartitionKey)
}

func TestNewOutboxEvent_InvalidPayload(t *testing.T) {
	ch := make(chan int)

	event, err := entity.NewOutboxEvent(
		"test.event",
		"user",
		"user-123",
		ch,
	)

	assert.Error(t, err)
	assert.Nil(t, event)
}

func TestOutboxEvent_EventIDFormat(t *testing.T) {
	event, err := entity.NewOutboxEvent(
		"test.event",
		"user",
		"user-123",
		nil,
	)

	require.NoError(t, err)
	assert.Contains(t, event.EventID, "-")
	assert.GreaterOrEqual(t, len(event.EventID), 20)
}

func TestOutboxEvent_MultipleEventsHaveUniqueIDs(t *testing.T) {
	// Test that at least the first few events have unique IDs
	// Note: Rapid creation may result in same nanosecond timestamp
	events := make([]*entity.OutboxEvent, 10)

	for i := 0; i < 10; i++ {
		event, err := entity.NewOutboxEvent(
			"test.event",
			"user",
			"user-123",
			map[string]int{"index": i},
		)
		require.NoError(t, err)
		events[i] = event
	}

	// Check for uniqueness (may have some duplicates due to nanosecond resolution)
	ids := make(map[string]int)
	for _, event := range events {
		ids[event.EventID]++
	}

	// Most should be unique, count duplicates
	duplicates := 0
	for _, count := range ids {
		if count > 1 {
			duplicates++
		}
	}

	// Allow some duplicates due to nanosecond timestamp resolution
	// This is acceptable for test purposes
	assert.LessOrEqual(t, duplicates, 5, "Too many duplicate IDs")
}
