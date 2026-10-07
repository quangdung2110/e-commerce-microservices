// Package handler binds the HTTP routes to the domain services. Handlers
// parse, call one service method and render; every success goes through
// httpx.WriteJSON/NoContent so Cache-Control: no-store is never forgotten.
package handler

import (
	"context"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/api/dto/request"
	"github.com/ecommerce/notification-service/internal/api/dto/response"
	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/api/middleware"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/inbox"
)

// InboxService is what the inbox handler needs from the domain.
type InboxService interface {
	List(ctx context.Context, user uuid.UUID, cursor string, limit int, unreadOnly bool) (*inbox.Page, error)
	UnreadCount(ctx context.Context, user uuid.UUID) (inbox.Unread, error)
	SetRead(ctx context.Context, user, id uuid.UUID, read bool) (*entity.InboxItem, error)
	Archive(ctx context.Context, user, id uuid.UUID) error
	MarkAllRead(ctx context.Context, user uuid.UUID) error
}

// InboxHandler serves /api/v1/notifications/inbox.
type InboxHandler struct {
	svc InboxService
}

// NewInboxHandler wires the handler.
func NewInboxHandler(svc InboxService) *InboxHandler { return &InboxHandler{svc: svc} }

// List handles GET /inbox?cursor&limit&unread_only.
func (h *InboxHandler) List(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}
	var fields []apperr.Field

	limit := 0
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > inbox.MaxLimit {
			fields = append(fields, apperr.Field{Field: "limit", Message: "must be an integer between 1 and 50"})
		} else {
			limit = n
		}
	}
	unreadOnly := false
	if raw := strings.TrimSpace(c.Query("unread_only")); raw != "" {
		b, err := strconv.ParseBool(raw)
		if err != nil {
			fields = append(fields, apperr.Field{Field: "unread_only", Message: "must be true or false"})
		} else {
			unreadOnly = b
		}
	}
	cursor := strings.TrimSpace(c.Query("cursor"))
	if len(cursor) > 256 {
		fields = append(fields, apperr.Field{Field: "cursor", Message: "is not a valid cursor"})
	}
	if len(fields) > 0 {
		httpx.WriteProblem(c, apperr.New(apperr.CodeValidationFailed, "invalid query parameter").WithFields(fields...))
		return
	}

	page, err := h.svc.List(c.Request.Context(), identity.UserID, cursor, limit, unreadOnly)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.InboxPage{
		Items:      response.FromInboxItems(page.Items),
		NextCursor: page.NextCursor,
	})
}

// UnreadCount handles GET /inbox/unread-count.
func (h *InboxHandler) UnreadCount(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}
	unread, err := h.svc.UnreadCount(c.Request.Context(), identity.UserID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.UnreadCount{Count: unread.Count, AsOf: unread.AsOf.UTC()})
}

// Update handles PATCH /inbox/{item_id} {"read": bool}.
func (h *InboxHandler) Update(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}
	id, err := httpx.PathID(c, "item_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	var body request.InboxItemUpdate
	if err := httpx.DecodeJSON(c, &body); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	if body.Read == nil {
		httpx.WriteProblem(c, apperr.New(apperr.CodeValidationFailed, "invalid body").
			WithFields(apperr.Field{Field: "read", Message: "is required"}))
		return
	}
	item, err := h.svc.SetRead(c.Request.Context(), identity.UserID, id, *body.Read)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.FromInboxItem(*item))
}

// Archive handles DELETE /inbox/{item_id}.
func (h *InboxHandler) Archive(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}
	id, err := httpx.PathID(c, "item_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	if err := h.svc.Archive(c.Request.Context(), identity.UserID, id); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.NoContent(c)
}

// MarkAllRead handles POST /inbox/mark-all-read.
func (h *InboxHandler) MarkAllRead(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}
	if err := h.svc.MarkAllRead(c.Request.Context(), identity.UserID); err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.NoContent(c)
}
