// Package outcome builds the four outbound CloudEvents of
// contracts/events/notification/v1 as outbox rows (10-interfaces.md §3). The
// CloudEvents id is generated here, at INSERT time, so a republish carries the
// same id (04-idempotency.md lớp D). No template_data, no address.
package outcome

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// Context is the request-side data every outcome carries.
type Context struct {
	CorrelationID string
	TraceParent   string
}

func core(d *entity.Delivery, provider entity.Provider, occurredAt time.Time) (entity.OutcomeCore, error) {
	if d.UserID == nil {
		return entity.OutcomeCore{}, fmt.Errorf("outcome: delivery %s has no user_id (anonymised?)", d.ID)
	}
	if provider == "" {
		if d.Provider != nil {
			provider = *d.Provider
		} else if d.Channel == entity.ChannelInApp {
			provider = entity.ProviderInbox
		} else {
			return entity.OutcomeCore{}, fmt.Errorf("outcome: delivery %s has no provider", d.ID)
		}
	}
	return entity.OutcomeCore{
		DeliveryID: d.ID.String(),
		RequestID:  d.RequestID.String(),
		Template:   d.Template,
		Lane:       d.Lane,
		Channel:    d.Channel,
		UserID:     d.UserID.String(),
		Subject:    d.Subject,
		Provider:   provider,
		OccurredAt: occurredAt.UTC(),
	}, nil
}

func build(d *entity.Delivery, eventType, dataSchema string, data any, at time.Time, rc Context) (*entity.OutboxEvent, error) {
	payload, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}
	id := uuid.New()
	ev := entity.CloudEvent{
		SpecVersion:     "1.0",
		ID:              id.String(),
		Type:            eventType,
		Source:          constants.EventSource,
		Subject:         "deliveries/" + d.ID.String(),
		Time:            at.UTC(),
		DataContentType: constants.EventDataContentType,
		DataSchema:      dataSchema,
		CorrelationID:   rc.CorrelationID,
		TraceParent:     rc.TraceParent,
		Data:            payload,
	}
	raw, err := json.Marshal(ev)
	if err != nil {
		return nil, err
	}
	return &entity.OutboxEvent{
		ID:            id,
		AggregateType: entity.OutboxAggregateType,
		AggregateID:   d.UserID.String(),
		Type:          eventType,
		Payload:       raw,
		CreatedAt:     at.UTC(),
	}, nil
}

type deliveredData struct {
	entity.OutcomeCore
	DeliveredAt time.Time `json:"delivered_at"`
}

// Delivered is emitted when SES reports Delivery or the inbox row is written.
func Delivered(d *entity.Delivery, provider entity.Provider, deliveredAt time.Time, rc Context) (*entity.OutboxEvent, error) {
	c, err := core(d, provider, deliveredAt)
	if err != nil {
		return nil, err
	}
	return build(d, entity.EventTypeDelivered, entity.DataSchemaDelivered,
		deliveredData{OutcomeCore: c, DeliveredAt: deliveredAt.UTC()}, deliveredAt, rc)
}

type bouncedData struct {
	entity.OutcomeCore
	BounceType entity.BounceType `json:"bounce_type"`
	Suppressed bool              `json:"suppressed"`
}

// Bounced is emitted on an SES Bounce.
func Bounced(d *entity.Delivery, provider entity.Provider, bounceType entity.BounceType, suppressed bool, at time.Time, rc Context) (*entity.OutboxEvent, error) {
	c, err := core(d, provider, at)
	if err != nil {
		return nil, err
	}
	return build(d, entity.EventTypeBounced, entity.DataSchemaBounced,
		bouncedData{OutcomeCore: c, BounceType: bounceType, Suppressed: suppressed}, at, rc)
}

type failedData struct {
	entity.OutcomeCore
	FinalStatus entity.DeliveryStatus `json:"final_status"`
	ReasonCode  string                `json:"reason_code"`
	Attempts    int                   `json:"attempts"`
}

// Failed is emitted when a delivery ends FAILED or EXPIRED. provider may be
// empty when no provider was ever called; the schema still requires one, so
// the channel default (inbox) or the configured email provider is used.
func Failed(d *entity.Delivery, provider entity.Provider, finalStatus entity.DeliveryStatus, reasonCode string, at time.Time, rc Context) (*entity.OutboxEvent, error) {
	if finalStatus != entity.StatusFailed && finalStatus != entity.StatusExpired {
		return nil, fmt.Errorf("outcome: Failed requires FAILED or EXPIRED, got %s", finalStatus)
	}
	c, err := core(d, provider, at)
	if err != nil {
		return nil, err
	}
	return build(d, entity.EventTypeFailed, entity.DataSchemaFailed,
		failedData{OutcomeCore: c, FinalStatus: finalStatus, ReasonCode: reasonCode, Attempts: d.Attempt}, at, rc)
}

type complaintData struct {
	entity.OutcomeCore
	MarketingDisabled bool `json:"marketing_disabled"`
}

// ComplaintReceived is emitted on an SES Complaint; User Service consumes it
// to switch marketing_enabled off at the source.
func ComplaintReceived(d *entity.Delivery, provider entity.Provider, marketingDisabled bool, at time.Time, rc Context) (*entity.OutboxEvent, error) {
	c, err := core(d, provider, at)
	if err != nil {
		return nil, err
	}
	return build(d, entity.EventTypeComplaintReceived, entity.DataSchemaComplaintReceived,
		complaintData{OutcomeCore: c, MarketingDisabled: marketingDisabled}, at, rc)
}
