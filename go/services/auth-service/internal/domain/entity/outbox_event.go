package entity

import (
	"encoding/json"
	"time"
)

type OutboxEvent struct {
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	AggregateType string          `json:"aggregate_type"`
	AggregateID   string          `json:"aggregate_id"`
	PartitionKey  string          `json:"partition_key"`
	Payload       json.RawMessage `json:"payload"`
	CreatedAt     time.Time       `json:"created_at"`
}

func NewOutboxEvent(
	eventType string,
	aggregateType string,
	aggregateID string,
	payload interface{},
) (*OutboxEvent, error) {
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}

	return &OutboxEvent{
		EventID:       generateUUIDv2(),
		EventType:     eventType,
		AggregateType: aggregateType,
		AggregateID:   aggregateID,
		PartitionKey:  aggregateID,
		Payload:       payloadBytes,
		CreatedAt:     time.Now().UTC(),
	}, nil
}

func generateUUIDv2() string {
	return time.Now().Format("20060102150405.000000000") + "-" + randomString(12)
}
