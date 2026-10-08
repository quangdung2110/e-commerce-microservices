package event

import (
	"encoding/json"
	"fmt"

	"github.com/google/uuid"
)

// TypeNotificationComplaintReceived is the only Notification outcome this
// service reacts to. t.notification.outcomes.v1 also carries delivered, bounced
// and failed outcomes for Analytics; those are acknowledged without action.
const TypeNotificationComplaintReceived = "com.ecommerce.notification.complaint-received.v1"

const notificationSource = "/services/notification"

// ParseNotificationEnvelope decodes and structurally validates a message of
// t.notification.outcomes.v1. A message that is not a CloudEvent 1.0 from
// Notification is rejected permanently.
func ParseNotificationEnvelope(payload []byte) (*IncomingEnvelope, error) {
	return parseEnvelope(payload, notificationSource)
}

// ComplaintReceived is the part of the complaint payload User needs. The
// delivery, template and channel members stay with Notification and Analytics.
type ComplaintReceived struct {
	UserID uuid.UUID
}

// ParseComplaintReceived reads the data member of a complaint-received event.
func ParseComplaintReceived(data json.RawMessage) (ComplaintReceived, error) {
	var payload struct {
		UserID string `json:"user_id"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		return ComplaintReceived{}, fmt.Errorf("event: complaint-received data is malformed: %w", err)
	}

	userID, err := uuid.Parse(payload.UserID)
	if err != nil || userID == uuid.Nil {
		return ComplaintReceived{}, fmt.Errorf("event: complaint-received user_id is not a UUID")
	}
	return ComplaintReceived{UserID: userID}, nil
}
