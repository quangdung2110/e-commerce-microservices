// Package status answers the support questions of 10-interfaces.md §6 and
// 14-observability.md §5: the timeline of one request and the deliveries of
// one aggregate. It returns entities; the HTTP layer maps them to the
// RequestStatus/DeliveryStatus schemas, which carry neither template_data,
// nor user_id, nor any address (11-privacy.md §5).
package status

import (
	"context"
	"regexp"
	"strings"

	"github.com/google/uuid"

	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// MaxBySubject bounds the subject listing (contract: maxItems 100; more is a
// sign of an abnormal aggregate, not a paging need).
const MaxBySubject = 100

var templatePattern = regexp.MustCompile(`^[A-Z][A-Z0-9_]{2,63}$`)

// Timeline is one request with every delivery it produced.
type Timeline struct {
	Request    entity.Request
	Deliveries []entity.Delivery
}

// Service reads requests and deliveries.
type Service struct {
	requests   port.RequestRepository
	deliveries port.DeliveryRepository
}

// New wires the service.
func New(requests port.RequestRepository, deliveries port.DeliveryRepository) *Service {
	return &Service{requests: requests, deliveries: deliveries}
}

// GetRequest returns the request and its deliveries, or NOT_FOUND.
func (s *Service) GetRequest(ctx context.Context, id uuid.UUID) (*Timeline, error) {
	req, err := s.requests.GetByID(ctx, id)
	if err != nil {
		if apperr.Is(err, apperr.ErrNotFound) {
			return nil, apperr.ErrNotFound
		}
		return nil, err
	}
	if req == nil {
		return nil, apperr.ErrNotFound
	}
	deliveries, err := s.deliveries.ListByRequest(ctx, id)
	if err != nil {
		return nil, err
	}
	if deliveries == nil {
		deliveries = []entity.Delivery{}
	}
	return &Timeline{Request: *req, Deliveries: deliveries}, nil
}

// ListBySubject returns up to MaxBySubject newest deliveries of an aggregate,
// optionally narrowed to one template. subject is required (1..256).
func (s *Service) ListBySubject(ctx context.Context, subject, template string) ([]entity.Delivery, error) {
	subject = strings.TrimSpace(subject)
	var fields []apperr.Field
	if subject == "" || len(subject) > 256 {
		fields = append(fields, apperr.Field{Field: "subject", Message: "is required and must be 1 to 256 characters"})
	}
	template = strings.TrimSpace(template)
	if template != "" && !templatePattern.MatchString(template) {
		fields = append(fields, apperr.Field{Field: "template", Message: "must match ^[A-Z][A-Z0-9_]{2,63}$"})
	}
	if len(fields) > 0 {
		return nil, apperr.New(apperr.CodeValidationFailed, "invalid query parameter").WithFields(fields...)
	}
	deliveries, err := s.deliveries.ListBySubject(ctx, subject, template, MaxBySubject)
	if err != nil {
		return nil, err
	}
	if deliveries == nil {
		deliveries = []entity.Delivery{}
	}
	if len(deliveries) > MaxBySubject {
		deliveries = deliveries[:MaxBySubject]
	}
	return deliveries, nil
}
