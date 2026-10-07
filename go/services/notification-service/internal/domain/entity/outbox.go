package entity

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// Outbound CloudEvents types (contracts/events/notification/v1/asyncapi.yaml).
const (
	EventTypeDelivered         = "com.ecommerce.notification.delivered.v1"
	EventTypeBounced           = "com.ecommerce.notification.bounced.v1"
	EventTypeFailed            = "com.ecommerce.notification.failed.v1"
	EventTypeComplaintReceived = "com.ecommerce.notification.complaint-received.v1"

	DataSchemaDelivered         = "/contracts/events/notification/v1/schemas/notification-delivered.schema.json"
	DataSchemaBounced           = "/contracts/events/notification/v1/schemas/notification-bounced.schema.json"
	DataSchemaFailed            = "/contracts/events/notification/v1/schemas/notification-failed.schema.json"
	DataSchemaComplaintReceived = "/contracts/events/notification/v1/schemas/notification-complaint-received.schema.json"

	OutboxAggregateType = "notification"
)

// OutboxEvent is a row of the Debezium-compatible outbox (09-data-model.md §8).
// ID is the CloudEvents id, generated at INSERT time (layer D).
type OutboxEvent struct {
	ID            uuid.UUID
	AggregateType string
	AggregateID   string // user_id → Kafka key
	Type          string
	Payload       json.RawMessage // full CloudEvents envelope
	CreatedAt     time.Time
	PublishedAt   *time.Time
}

// OutcomeCore is the shared data block of every outcome event.
type OutcomeCore struct {
	DeliveryID string    `json:"delivery_id"`
	RequestID  string    `json:"request_id"`
	Template   string    `json:"template"`
	Lane       Lane      `json:"lane"`
	Channel    Channel   `json:"channel"`
	UserID     string    `json:"user_id"`
	Subject    *string   `json:"subject,omitempty"`
	Provider   Provider  `json:"provider"`
	OccurredAt time.Time `json:"occurred_at"`
}

// CloudEvent is the JSON envelope written to outbox.payload.
type CloudEvent struct {
	SpecVersion     string          `json:"specversion"`
	ID              string          `json:"id"`
	Type            string          `json:"type"`
	Source          string          `json:"source"`
	Subject         string          `json:"subject"`
	Time            time.Time       `json:"time"`
	DataContentType string          `json:"datacontenttype"`
	DataSchema      string          `json:"dataschema"`
	CorrelationID   string          `json:"correlationid,omitempty"`
	TraceParent     string          `json:"traceparent,omitempty"`
	Data            json.RawMessage `json:"data"`
}
