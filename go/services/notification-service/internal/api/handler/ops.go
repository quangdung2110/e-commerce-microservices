package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/api/dto/request"
	"github.com/ecommerce/notification-service/internal/api/dto/response"
	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/api/middleware"
	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/ops"
)

// OpsService is what the ops handler needs from the domain.
type OpsService interface {
	SetLaneState(ctx context.Context, in ops.LaneStateInput, principal string) (entity.LaneState, error)
	GetLaneState(ctx context.Context, lane string) (entity.LaneState, error)
	RedriveDelivery(ctx context.Context, id uuid.UUID, expiresAt *time.Time, reason string) (*entity.Delivery, error)
	RedriveDLQ(ctx context.Context, in ops.DLQRedriveInput) (ops.DLQRedriveAccepted, error)
	AddManualSuppression(ctx context.Context, userID uuid.UUID, until *time.Time, reason, principal string) (*entity.Suppression, error)
	RemoveSuppression(ctx context.Context, id uuid.UUID) error
	SetReplayMode(ctx context.Context, in ops.ReplayModeInput, principal string) (entity.ReplayMode, error)
}

// OpsHandler serves /internal/v1/notifications/ops.
type OpsHandler struct {
	svc   OpsService
	clock clock.Clock
}

// NewOpsHandler wires the handler.
func NewOpsHandler(svc OpsService, clk clock.Clock) *OpsHandler {
	return &OpsHandler{svc: svc, clock: clk}
}

// SetLaneState handles PUT /ops/lanes/{lane}.
func (h *OpsHandler) SetLaneState(c *gin.Context) {
	var body request.LaneStateUpdate
	if err := httpx.DecodeJSON(c, &body); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	if body.State == nil {
		httpx.WriteProblem(c, apperr.New(apperr.CodeValidationFailed, "invalid body").
			WithFields(apperr.Field{Field: "state", Message: "is required"}))
		return
	}
	state, err := h.svc.SetLaneState(c.Request.Context(), ops.LaneStateInput{
		Lane:    c.Param("lane"),
		State:   *body.State,
		Channel: body.Channel,
		Reason:  body.Reason,
	}, middleware.PrincipalFrom(c))
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.FromLaneState(state))
}

// GetLaneState handles GET /ops/lanes/{lane}. An unknown lane is 404: the
// contract declares no 400 for this read.
func (h *OpsHandler) GetLaneState(c *gin.Context) {
	state, err := h.svc.GetLaneState(c.Request.Context(), c.Param("lane"))
	if err != nil {
		if apperr.From(err).Code == apperr.CodeValidationFailed {
			err = apperr.ErrNotFound
		}
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.FromLaneState(state))
}

// RedriveDelivery handles POST /ops/deliveries/{delivery_id}/redrive.
func (h *OpsHandler) RedriveDelivery(c *gin.Context) {
	id, err := httpx.PathID(c, "delivery_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	var body request.Redrive
	if _, err := httpx.DecodeOptionalJSON(c, &body); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	d, err := h.svc.RedriveDelivery(c.Request.Context(), id, body.ExpiresAt, body.Reason)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.FromDelivery(*d))
}

// RedriveDLQ handles POST /ops/dlq/redrive.
func (h *OpsHandler) RedriveDLQ(c *gin.Context) {
	var body request.DlqRedrive
	if err := httpx.DecodeJSON(c, &body); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	var fields []apperr.Field
	if body.Lane == nil {
		fields = append(fields, apperr.Field{Field: "lane", Message: "is required"})
	}
	if body.Reason == nil {
		fields = append(fields, apperr.Field{Field: "reason", Message: "is required"})
	}
	if len(fields) > 0 {
		httpx.WriteProblem(c, apperr.New(apperr.CodeValidationFailed, "invalid body").WithFields(fields...))
		return
	}
	accepted, err := h.svc.RedriveDLQ(c.Request.Context(), ops.DLQRedriveInput{
		Lane:         *body.Lane,
		ReasonCode:   body.ReasonCode,
		FailedAfter:  body.FailedAfter,
		FailedBefore: body.FailedBefore,
		ExpiresAt:    body.ExpiresAt,
		Reason:       *body.Reason,
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusAccepted, response.DlqRedriveAccepted{
		OperationID: accepted.OperationID.String(),
		Matched:     accepted.Matched,
	})
}

// AddSuppression handles POST /ops/suppressions.
func (h *OpsHandler) AddSuppression(c *gin.Context) {
	var body request.ManualSuppression
	if err := httpx.DecodeJSON(c, &body); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	var fields []apperr.Field
	var userID uuid.UUID
	if body.UserID == nil {
		fields = append(fields, apperr.Field{Field: "user_id", Message: "is required"})
	} else {
		parsed, err := uuid.Parse(*body.UserID)
		if err != nil || parsed == uuid.Nil {
			fields = append(fields, apperr.Field{Field: "user_id", Message: "must be a UUID"})
		}
		userID = parsed
	}
	if body.Reason == nil {
		fields = append(fields, apperr.Field{Field: "reason", Message: "is required"})
	}
	if len(fields) > 0 {
		httpx.WriteProblem(c, apperr.New(apperr.CodeValidationFailed, "invalid body").WithFields(fields...))
		return
	}
	row, err := h.svc.AddManualSuppression(c.Request.Context(), userID, body.Until, *body.Reason, middleware.PrincipalFrom(c))
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusCreated, response.FromSuppression(*row))
}

// RemoveSuppression handles DELETE /ops/suppressions/{suppression_id}.
func (h *OpsHandler) RemoveSuppression(c *gin.Context) {
	id, err := httpx.PathID(c, "suppression_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	if err := h.svc.RemoveSuppression(c.Request.Context(), id); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.NoContent(c)
}

// SetReplayMode handles PUT /ops/replay-mode.
func (h *OpsHandler) SetReplayMode(c *gin.Context) {
	var body request.ReplayMode
	if err := httpx.DecodeJSON(c, &body); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	if body.Enabled == nil {
		httpx.WriteProblem(c, apperr.New(apperr.CodeValidationFailed, "invalid body").
			WithFields(apperr.Field{Field: "enabled", Message: "is required"}))
		return
	}
	mode, err := h.svc.SetReplayMode(c.Request.Context(), ops.ReplayModeInput{
		Enabled:               *body.Enabled,
		IgnoreEventsOlderThan: body.IgnoreEventsOlderThan,
		TTL:                   body.TTL,
	}, middleware.PrincipalFrom(c))
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.FromReplayMode(mode, h.clock.Now()))
}
