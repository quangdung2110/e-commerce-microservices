package redis

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/clock"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.DeliveryStore = (*DeliveryStore)(nil)

// deliveryRecord is the stored render state. It carries PII and, for the OTP and
// reset templates, a secret; it only ever leaves the cluster through the
// internal mTLS API and is never logged.
type deliveryRecord struct {
	DeliveryID     string            `json:"delivery_id"`
	AccountID      string            `json:"account_id"`
	RecipientEmail string            `json:"recipient_email"`
	Template       string            `json:"template"`
	ExpiresAt      time.Time         `json:"expires_at"`
	TemplateData   map[string]string `json:"template_data"`
}

// DeliveryStore holds the notification render state resolved by Notification
// Service.
type DeliveryStore struct {
	store
	clock clock.Clock
}

// NewDeliveryStore wires the delivery adapter.
func NewDeliveryStore(client *redis.Client, keys *keybuilder.Builder, clk clock.Clock) *DeliveryStore {
	return &DeliveryStore{store: store{client: client, keys: keys}, clock: clk}
}

// Put stores the render state under its delivery id.
func (s *DeliveryStore) Put(ctx context.Context, delivery *entity.NotificationDelivery, ttl time.Duration) error {
	record := deliveryRecord{
		DeliveryID:     delivery.DeliveryID.String(),
		AccountID:      delivery.AccountID.String(),
		RecipientEmail: delivery.RecipientEmail,
		Template:       delivery.Template,
		ExpiresAt:      delivery.ExpiresAt.UTC(),
		TemplateData:   delivery.TemplateData,
	}
	payload, err := json.Marshal(record)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}

	key := s.keys.Delivery(delivery.DeliveryID.String())
	return mapError(s.client.Set(ctx, key, payload, ttl).Err())
}

// Get returns the render state. The lookup is idempotent inside the TTL: it does
// not consume the OTP or the reset token it carries.
func (s *DeliveryStore) Get(ctx context.Context, deliveryID uuid.UUID) (*entity.NotificationDelivery, error) {
	raw, err := s.client.Get(ctx, s.keys.Delivery(deliveryID.String())).Bytes()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return nil, apperr.ErrDeliveryNotFound
		}
		return nil, mapError(err)
	}

	var record deliveryRecord
	if err := json.Unmarshal(raw, &record); err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}

	accountID, err := uuid.Parse(record.AccountID)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}

	delivery := &entity.NotificationDelivery{
		DeliveryID:     deliveryID,
		AccountID:      accountID,
		RecipientEmail: record.RecipientEmail,
		Template:       record.Template,
		ExpiresAt:      record.ExpiresAt,
		TemplateData:   record.TemplateData,
	}
	if delivery.Expired(s.clock.Now()) {
		// The key TTL and the embedded expiry can drift; the embedded value wins
		// so an old secret is never handed out again.
		return nil, apperr.ErrDeliveryExpired
	}
	return delivery, nil
}
