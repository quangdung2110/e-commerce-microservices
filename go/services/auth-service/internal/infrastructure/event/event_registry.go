package event

import (
	"time"

	"github.com/google/uuid"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// EventRegistry provides factory methods to create outbox events with proper payloads
type EventRegistry struct{}

// NewEventRegistry creates a new EventRegistry
func NewEventRegistry() *EventRegistry {
	return &EventRegistry{}
}

// BuildOtpRequestedEvent creates an OTP requested event
func (er *EventRegistry) BuildOtpRequestedEvent(userID uuid.UUID, email string, otpType string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeOtpRequested),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":   userID.String(),
			"email":     email,
			"otp_type":  otpType,
			"occurred":  time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserVerifiedEvent creates a user verified event
func (er *EventRegistry) BuildUserVerifiedEvent(userID uuid.UUID, email string, roles []string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserActivated),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":   userID.String(),
			"email":     email,
			"roles":     roles,
			"occurred":  time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserDeletedEvent creates a user deleted event
func (er *EventRegistry) BuildUserDeletedEvent(userID uuid.UUID, reason string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserDeleted),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":  userID.String(),
			"reason":   reason,
			"occurred": time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildPasswordResetRequestedEvent creates a password reset requested event
func (er *EventRegistry) BuildPasswordResetRequestedEvent(userID uuid.UUID, email string, resetURL string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypePasswordResetRequested),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":   userID.String(),
			"email":     email,
			"reset_url": resetURL,
			"occurred":  time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildTokenTheftDetectedEvent creates a token theft detected event
func (er *EventRegistry) BuildTokenTheftDetectedEvent(userID uuid.UUID, theftSessionID string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeTokenTheftDetected),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":                userID.String(),
			"theft_refresh_token_id": theftSessionID,
			"action_taken":           "ALL_TOKENS_REVOKED",
			"occurred":               time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserLoggedInEvent creates a user logged in event
func (er *EventRegistry) BuildUserLoggedInEvent(userID uuid.UUID, email string, deviceID, ipAddress string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserLoggedIn),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":    userID.String(),
			"email":      email,
			"device_id":  deviceID,
			"ip_address": ipAddress,
			"occurred":   time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserLoggedOutEvent creates a user logged out event
func (er *EventRegistry) BuildUserLoggedOutEvent(userID uuid.UUID, sessionID, deviceID string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserLoggedOut),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":    userID.String(),
			"session_id": sessionID,
			"device_id":  deviceID,
			"occurred":   time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildAllSessionsRevokedEvent creates an all sessions revoked event
func (er *EventRegistry) BuildAllSessionsRevokedEvent(userID uuid.UUID, reason string, actorID string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeAllSessionsRevoked),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":  userID.String(),
			"reason":   reason,
			"actor_id": actorID,
			"occurred": time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildPasswordChangedEvent creates a password changed event
func (er *EventRegistry) BuildPasswordChangedEvent(userID uuid.UUID, email string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypePasswordChanged),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":  userID.String(),
			"email":    email,
			"occurred": time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildAdminForceLogoutEvent creates an admin force logout event (for publishing to Kafka)
func (er *EventRegistry) BuildAdminForceLogoutEvent(userID uuid.UUID, reason string, actorID string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeAdminForceLogout),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":  userID.String(),
			"reason":   reason,
			"actor_id": actorID,
			"occurred": time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserBannedEvent creates a user banned event
func (er *EventRegistry) BuildUserBannedEvent(userID uuid.UUID, reason string, actorID string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserBanned),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":  userID.String(),
			"reason":   reason,
			"actor_id": actorID,
			"occurred": time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserAccountLockedEvent creates a user account locked event (due to failed login attempts)
// This event notifies Notification Service to send Email to user
func (er *EventRegistry) BuildUserAccountLockedEvent(userID uuid.UUID, email string, lockedUntil time.Time) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserAccountLocked),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":      userID.String(),
			"email":        email,
			"reason":       "FAILED_LOGIN_ATTEMPTS",
			"locked_until": lockedUntil.Format(time.RFC3339),
			"occurred":     time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserUnlockedEvent creates a user unlocked event (auto-unlock after 30 minutes)
// This event notifies Notification Service to send Email to user
func (er *EventRegistry) BuildUserUnlockedEvent(userID uuid.UUID, email string, unlockType string, unlockedBy string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserUnlocked),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":    userID.String(),
			"email":      email,
			"unlock_type": unlockType, // "AUTO" or "ADMIN"
			"unlocked_by": unlockedBy, // "SYSTEM" or admin user ID
			"occurred":   time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserEmailChangedEvent creates a user email changed event
// This event notifies Notification Service to send In-app push notification
func (er *EventRegistry) BuildUserEmailChangedEvent(userID uuid.UUID, oldEmail, newEmail string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserEmailChanged),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":   userID.String(),
			"old_email": oldEmail,
			"new_email": newEmail,
			"occurred":  time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserPasswordChangedEvent creates a user password changed event
// This event notifies Notification Service to send In-app push notification
func (er *EventRegistry) BuildUserPasswordChangedEvent(userID uuid.UUID, email, deviceInfo string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserPasswordChanged),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":     userID.String(),
			"email":       email,
			"device_info": deviceInfo,
			"changed_at":  time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildUserBannedNotificationEvent creates a user banned notification event
// This event is sent to Notification Service to send Email notification to user
func (er *EventRegistry) BuildUserBannedNotificationEvent(userID uuid.UUID, email, reason, bannedBy string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeUserBannedNotification),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":   userID.String(),
			"email":     email,
			"reason":    reason,
			"banned_by": bannedBy,
			"banned_at": time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildSuspiciousActivityEvent creates a suspicious activity event
func (er *EventRegistry) BuildSuspiciousActivityEvent(userID uuid.UUID, activityType string, details map[string]any) *entity.OutboxEvent {
	payload := map[string]any{
		"user_id":       userID.String(),
		"activity_type": activityType,
		"occurred":      time.Now().UTC().Format(time.RFC3339),
	}
	for k, v := range details {
		payload[k] = v
	}

	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeSuspiciousActivity),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload:       payload,
		Metadata:      make(map[string]any),
		Status:        types.OutboxEventStatusPending,
	}
}

// BuildImpossibleTravelEvent creates an impossible travel detected event
func (er *EventRegistry) BuildImpossibleTravelEvent(userID uuid.UUID, currentIP, previousIP string, currentCountry, previousCountry string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     string(types.EventTypeImpossibleTravel),
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":           userID.String(),
			"current_ip":        currentIP,
			"previous_ip":       previousIP,
			"current_country":    currentCountry,
			"previous_country":  previousCountry,
			"occurred":          time.Now().UTC().Format(time.RFC3339),
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}

// BuildManagedAccountCreatedEvent creates a managed account created event
func (er *EventRegistry) BuildManagedAccountCreatedEvent(userID, creatorID uuid.UUID, role, username string) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     "MANAGED_ACCOUNT_CREATED",
		AggregateType: "User",
		AggregateID:   userID,
		PartitionKey:  userID,
		Payload: map[string]any{
			"user_id":    userID.String(),
			"creator_id": creatorID.String(),
			"role":       role,
			"username":   username,
		},
		Metadata: make(map[string]any),
		Status:   types.OutboxEventStatusPending,
	}
}
