// Package event builds the CloudEvents 1.0 messages defined by
// contracts/events/user/v1/asyncapi.yaml and decodes the Auth messages this
// service consumes.
//
// Payloads stay minimal on purpose: `USER_PROFILE_UPDATED` carries the derived
// display name only, `USER_PREFERENCES_UPDATED` the three flags Notification
// replicates. Date of birth, gender, phone numbers and addresses never reach a
// topic (docs/user/interfaces.md §3).
package event

import (
	"fmt"
	"time"

	"github.com/google/uuid"
)

// CloudEvents envelope constants required by the shared schema.
const (
	specVersion     = "1.0"
	source          = "/services/user"
	dataContentType = "application/json"
	schemaBase      = "/contracts/events/user/v1/schemas/"
)

// Event types published by User Service, versioned on the wire.
const (
	TypeUserProfileUpdated     = "com.ecommerce.user.profile-updated.v1"
	TypeUserPreferencesUpdated = "com.ecommerce.user.preferences-updated.v1"
)

// Envelope is the structured CloudEvents JSON message. Key is routing metadata
// and is not serialized: it becomes the Kafka partition key, which is what
// makes per-user ordering the only ordering the contract promises.
type Envelope struct {
	SpecVersion     string `json:"specversion"`
	ID              string `json:"id"`
	Type            string `json:"type"`
	Source          string `json:"source"`
	Subject         string `json:"subject"`
	Time            string `json:"time"`
	DataContentType string `json:"datacontenttype"`
	DataSchema      string `json:"dataschema"`
	CorrelationID   string `json:"correlationid,omitempty"`
	TraceParent     string `json:"traceparent,omitempty"`
	Data            any    `json:"data"`

	Key string `json:"-"`
}

// Metadata carries the correlation identifiers of the originating request or of
// the Auth event being processed.
type Metadata struct {
	CorrelationID string
	TraceParent   string
}

// FormatTime renders an RFC 3339 UTC timestamp as required by the conventions.
//
// Sub-second precision is kept on purpose: consumers drop a snapshot that is
// not newer than what they already hold, so truncating to whole seconds would
// make two updates inside the same second indistinguishable and silently lose
// the second one until the next reconciliation sweep.
func FormatTime(t time.Time) string {
	return t.UTC().Format(time.RFC3339Nano)
}

func newEnvelope(eventType, schemaFile string, userID uuid.UUID, occurredAt time.Time, meta Metadata, data any) *Envelope {
	correlationID := meta.CorrelationID
	if correlationID == "" {
		// The schema constrains correlationid to a UUID, so fall back to the
		// user rather than propagating an arbitrary edge request id.
		correlationID = userID.String()
	}
	return &Envelope{
		SpecVersion: specVersion,
		// A fresh id per envelope. A reconciliation sweep publishes a corrective
		// snapshot, not a redelivery, so it gets a new id too (ADR-007).
		ID:              uuid.NewString(),
		Type:            eventType,
		Source:          source,
		Subject:         fmt.Sprintf("users/%s", userID),
		Time:            FormatTime(occurredAt),
		DataContentType: dataContentType,
		DataSchema:      schemaBase + schemaFile,
		CorrelationID:   correlationID,
		TraceParent:     meta.TraceParent,
		Data:            data,
		Key:             userID.String(),
	}
}

// ProfileUpdatedData is the payload of USER_PROFILE_UPDATED.
type ProfileUpdatedData struct {
	UserID     string `json:"user_id"`
	SenderName string `json:"sender_name"`
	UpdatedAt  string `json:"updated_at"`
}

// NewProfileUpdated reports a committed profile change to Chat Service.
// updatedAt is the value stored in PostgreSQL, so a consumer can drop a
// snapshot older than the one it already applied.
func NewProfileUpdated(userID uuid.UUID, senderName string, updatedAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(TypeUserProfileUpdated, "user-profile-updated.schema.json",
		userID, updatedAt, meta, ProfileUpdatedData{
			UserID:     userID.String(),
			SenderName: senderName,
			UpdatedAt:  FormatTime(updatedAt),
		})
}

// PreferencesUpdatedData is the payload of USER_PREFERENCES_UPDATED. Language
// and currency are fixed by the contract and are deliberately not published.
type PreferencesUpdatedData struct {
	UserID           string `json:"user_id"`
	EmailEnabled     bool   `json:"email_enabled"`
	InAppEnabled     bool   `json:"in_app_enabled"`
	MarketingEnabled bool   `json:"marketing_enabled"`
	UpdatedAt        string `json:"updated_at"`
}

// NewPreferencesUpdated reports a committed preference change to Notification
// Service.
func NewPreferencesUpdated(userID uuid.UUID, emailEnabled, inAppEnabled, marketingEnabled bool, updatedAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(TypeUserPreferencesUpdated, "user-preferences-updated.schema.json",
		userID, updatedAt, meta, PreferencesUpdatedData{
			UserID:           userID.String(),
			EmailEnabled:     emailEnabled,
			InAppEnabled:     inAppEnabled,
			MarketingEnabled: marketingEnabled,
			UpdatedAt:        FormatTime(updatedAt),
		})
}
