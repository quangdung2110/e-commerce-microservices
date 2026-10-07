package event

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Event types consumed from t.auth.events. Auth publishes both directly to
// every service that owns data of the user (ADR-006).
const (
	TypeAuthUserVerifiedSuccess = "com.ecommerce.auth.user-verified-success.v1"
	TypeAuthUserDeleted         = "com.ecommerce.auth.user-deleted.v1"
)

const authSource = "/services/auth"

// AuthEnvelope is the incoming CloudEvent. Unknown optional members are kept in
// Data as raw JSON, so a producer adding a field cannot break this consumer
// (contracts/conventions/events.md).
type AuthEnvelope struct {
	SpecVersion   string          `json:"specversion"`
	ID            string          `json:"id"`
	Type          string          `json:"type"`
	Source        string          `json:"source"`
	Subject       string          `json:"subject"`
	Time          string          `json:"time"`
	CorrelationID string          `json:"correlationid"`
	TraceParent   string          `json:"traceparent"`
	Data          json.RawMessage `json:"data"`
}

// Metadata carries the correlation identifiers of the consumed event forward,
// so a downstream event stays attached to the same business flow.
func (e *AuthEnvelope) Metadata() Metadata {
	meta := Metadata{TraceParent: e.TraceParent}
	if _, err := uuid.Parse(e.CorrelationID); err == nil {
		meta.CorrelationID = e.CorrelationID
	}
	return meta
}

// ParseAuthEnvelope decodes and structurally validates an incoming message.
// A message that is not a CloudEvent 1.0 from Auth is rejected permanently: a
// retry would decode exactly the same bytes.
func ParseAuthEnvelope(payload []byte) (*AuthEnvelope, error) {
	var envelope AuthEnvelope
	if err := json.Unmarshal(payload, &envelope); err != nil {
		return nil, fmt.Errorf("event: payload is not a JSON CloudEvent: %w", err)
	}
	if envelope.SpecVersion != specVersion {
		return nil, fmt.Errorf("event: unsupported CloudEvents specversion %q", envelope.SpecVersion)
	}
	if envelope.Source != authSource {
		return nil, fmt.Errorf("event: unexpected source %q", envelope.Source)
	}
	if envelope.ID == "" || envelope.Type == "" {
		return nil, fmt.Errorf("event: id and type are required")
	}
	return &envelope, nil
}

// UserVerifiedSuccess is the payload of the bootstrap trigger. Roles are part
// of the contract but User Service does not store them: authorization is the
// gateway's and Auth's concern.
type UserVerifiedSuccess struct {
	UserID     uuid.UUID
	VerifiedAt time.Time
}

// ParseUserVerifiedSuccess reads the data member of USER_VERIFIED_SUCCESS.
func ParseUserVerifiedSuccess(data json.RawMessage) (UserVerifiedSuccess, error) {
	var payload struct {
		UserID     string `json:"user_id"`
		VerifiedAt string `json:"verified_at"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		return UserVerifiedSuccess{}, fmt.Errorf("event: USER_VERIFIED_SUCCESS data is malformed: %w", err)
	}

	userID, err := uuid.Parse(payload.UserID)
	if err != nil {
		return UserVerifiedSuccess{}, fmt.Errorf("event: USER_VERIFIED_SUCCESS user_id is not a UUID")
	}
	verifiedAt, err := time.Parse(time.RFC3339, payload.VerifiedAt)
	if err != nil {
		return UserVerifiedSuccess{}, fmt.Errorf("event: USER_VERIFIED_SUCCESS verified_at is not RFC 3339")
	}
	return UserVerifiedSuccess{UserID: userID, VerifiedAt: verifiedAt.UTC()}, nil
}

// UserDeleted is the payload of the deletion trigger. hard_delete_after is
// decided by Auth and stored verbatim.
type UserDeleted struct {
	UserID          uuid.UUID
	DeletedAt       time.Time
	Reason          string
	HardDeleteAfter time.Time
}

// ParseUserDeleted reads the data member of USER_DELETED.
func ParseUserDeleted(data json.RawMessage) (UserDeleted, error) {
	var payload struct {
		UserID          string `json:"user_id"`
		DeletedAt       string `json:"deleted_at"`
		Reason          string `json:"reason"`
		HardDeleteAfter string `json:"hard_delete_after"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		return UserDeleted{}, fmt.Errorf("event: USER_DELETED data is malformed: %w", err)
	}

	userID, err := uuid.Parse(payload.UserID)
	if err != nil {
		return UserDeleted{}, fmt.Errorf("event: USER_DELETED user_id is not a UUID")
	}
	deletedAt, err := time.Parse(time.RFC3339, payload.DeletedAt)
	if err != nil {
		return UserDeleted{}, fmt.Errorf("event: USER_DELETED deleted_at is not RFC 3339")
	}
	hardDeleteAfter, err := time.Parse(time.RFC3339, payload.HardDeleteAfter)
	if err != nil {
		return UserDeleted{}, fmt.Errorf("event: USER_DELETED hard_delete_after is not RFC 3339")
	}
	if payload.Reason == "" {
		return UserDeleted{}, fmt.Errorf("event: USER_DELETED reason is required")
	}

	return UserDeleted{
		UserID:          userID,
		DeletedAt:       deletedAt.UTC(),
		Reason:          payload.Reason,
		HardDeleteAfter: hardDeleteAfter.UTC(),
	}, nil
}
