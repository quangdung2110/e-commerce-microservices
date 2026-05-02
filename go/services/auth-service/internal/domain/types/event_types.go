package types

// ========================= EVENT TYPES =========================

// EventType represents all possible event types in the auth system
type EventType string

const (
	// User lifecycle events
	EventTypeUserRegistered           EventType = "USER_REGISTERED"
	EventTypeUserActivated            EventType = "USER_ACTIVATED"
	EventTypeUserDeactivated          EventType = "USER_DEACTIVATED"
	EventTypeUserDeleted              EventType = "USER_DELETED"
	EventTypeUserBanned               EventType = "USER_BANNED"
	EventTypeUserUnbanned             EventType = "USER_UNBANNED"

	// Authentication events
	EventTypeUserLoggedIn             EventType = "USER_LOGGED_IN"
	EventTypeUserLoggedOut            EventType = "USER_LOGGED_OUT"
	EventTypeUserLoginFailed         EventType = "USER_LOGIN_FAILED"
	EventTypeTokenRefreshed          EventType = "TOKEN_REFRESHED"
	EventTypeTokenTheftDetected      EventType = "TOKEN_THEFT_DETECTED"

	// OTP events
	EventTypeOtpRequested            EventType = "OTP_REQUESTED"
	EventTypeOtpVerified            EventType = "OTP_VERIFIED"
	EventTypeOtpFailed              EventType = "OTP_FAILED"

	// Password events
	EventTypePasswordChanged         EventType = "PASSWORD_CHANGED"
	EventTypePasswordResetRequested  EventType = "PASSWORD_RESET_REQUESTED"
	EventTypePasswordResetCompleted  EventType = "PASSWORD_RESET_COMPLETED"

	// Session events
	EventTypeSessionCreated          EventType = "SESSION_CREATED"
	EventTypeSessionRevoked         EventType = "SESSION_REVOKED"
	EventTypeAllSessionsRevoked     EventType = "ALL_SESSIONS_REVOKED"
	EventTypeImpossibleTravel       EventType = "IMPOSSIBLE_TRAVEL_DETECTED"

	// Security events
	EventTypeSuspiciousActivity      EventType = "SUSPICIOUS_ACTIVITY"
	EventTypeBruteForceAttack       EventType = "BRUTE_FORCE_ATTACK"
	EventTypeIpBlocked              EventType = "IP_BLOCKED"

	// Admin events
	EventTypeAdminForceLogout       EventType = "ADMIN_FORCE_LOGOUT"
	EventTypeAdminUserLocked        EventType = "ADMIN_USER_LOCKED"
	EventTypeAdminSessionRevoked    EventType = "ADMIN_SESSION_REVOKED"
)

// String returns the string representation of EventType
func (e EventType) String() string {
	return string(e)
}

// IsValid checks if the event type is valid
func (e EventType) IsValid() bool {
	switch e {
	case EventTypeUserRegistered, EventTypeUserActivated, EventTypeUserDeactivated,
		EventTypeUserDeleted, EventTypeUserBanned, EventTypeUserUnbanned,
		EventTypeUserLoggedIn, EventTypeUserLoggedOut, EventTypeUserLoginFailed,
		EventTypeTokenRefreshed, EventTypeTokenTheftDetected,
		EventTypeOtpRequested, EventTypeOtpVerified, EventTypeOtpFailed,
		EventTypePasswordChanged, EventTypePasswordResetRequested, EventTypePasswordResetCompleted,
		EventTypeSessionCreated, EventTypeSessionRevoked, EventTypeAllSessionsRevoked, EventTypeImpossibleTravel,
		EventTypeSuspiciousActivity, EventTypeBruteForceAttack, EventTypeIpBlocked,
		EventTypeAdminForceLogout, EventTypeAdminUserLocked, EventTypeAdminSessionRevoked:
		return true
	default:
		return false
	}
}

// ========================= KAFKA EVENT TYPE MAPPING =========================

// KafkaTopicMapping maps EventType to Kafka topic
var KafkaTopicMapping = map[EventType]string{
	EventTypeUserRegistered:          "user.auth.registered",
	EventTypeUserActivated:           "user.auth.activated",
	EventTypeUserDeactivated:         "user.auth.deactivated",
	EventTypeUserDeleted:             "user.auth.deleted",
	EventTypeUserBanned:              "user.auth.banned",
	EventTypeUserUnbanned:           "user.auth.unbanned",
	EventTypeUserLoggedIn:            "auth.events.login",
	EventTypeUserLoggedOut:          "auth.events.logout",
	EventTypeUserLoginFailed:        "auth.events.login_failed",
	EventTypeTokenRefreshed:         "auth.events.token",
	EventTypeTokenTheftDetected:     "auth.events.security",
	EventTypeOtpRequested:           "auth.events.otp",
	EventTypeOtpVerified:            "auth.events.otp",
	EventTypeOtpFailed:              "auth.events.otp",
	EventTypePasswordChanged:        "user.auth.password",
	EventTypePasswordResetRequested: "user.auth.password",
	EventTypePasswordResetCompleted: "user.auth.password",
	EventTypeSessionCreated:         "auth.events.session",
	EventTypeSessionRevoked:        "auth.events.session",
	EventTypeAllSessionsRevoked:    "auth.events.session",
	EventTypeImpossibleTravel:      "auth.events.security",
	EventTypeSuspiciousActivity:    "auth.events.security",
	EventTypeBruteForceAttack:      "auth.events.security",
	EventTypeIpBlocked:              "auth.events.security",
	EventTypeAdminForceLogout:      "admin.auth.events",
	EventTypeAdminUserLocked:        "admin.auth.events",
	EventTypeAdminSessionRevoked:   "admin.auth.events",
}

// GetKafkaTopic returns the Kafka topic for the event type
func (e EventType) GetKafkaTopic() string {
	if topic, ok := KafkaTopicMapping[e]; ok {
		return topic
	}
	return "t.auth.events" // default topic
}
