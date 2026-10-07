package handlers

import (
	"context"
	"encoding/json"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
)

// TypeNotificationRequested is the only type the lane topics carry.
const TypeNotificationRequested = "com.ecommerce.notification.requested.v1"

type requestData struct {
	Template     string          `json:"template"`
	UserID       string          `json:"user_id"`
	DedupKey     string          `json:"dedup_key"`
	ExpiresAt    *time.Time      `json:"expires_at"`
	RequestedAt  *time.Time      `json:"requested_at"`
	TemplateData json.RawMessage `json:"template_data"`
}

// Request handles t.notification.{lane}.v1 (contracts/events/notification/v1
// notification-requested). The topic implies the lane; Decide checks it
// against the catalog (lane_mismatch). The envelope is not JSON-schema
// validated here: Decide validates template_data and every other contract
// error becomes a FAILED request row — the table is the DLQ for producer
// mistakes, so a data block without `template` is still ingested.
func Request(deps HandlerDeps, lane entity.Lane) kafka.MessageHandler {
	return func(ctx context.Context, msg *sarama.ConsumerMessage) error {
		ctx, env, err := parse(ctx, deps, msg)
		if err != nil {
			return err
		}
		if env.Type != TypeNotificationRequested {
			// Wrong type on a request topic is a producer error, recorded as
			// FAILED unknown_template rather than parked.
			logging.FromContext(ctx).Warn("unexpected event type on request topic", zap.String("lane", string(lane)))
		}
		var data requestData
		if err := decodeData(env, &data); err != nil {
			return err
		}
		in := base(env, entity.InputRequest)
		in.Lane = lane
		in.Template = data.Template
		in.UserID = data.UserID
		in.DedupKey = data.DedupKey
		in.ExpiresAt = data.ExpiresAt
		in.TemplateData = data.TemplateData
		in.PayloadSHA256 = payloadDigest(env.Data)
		if env.Type != TypeNotificationRequested {
			in.Template = ""
		}
		return runIngest(ctx, deps, in)
	}
}
