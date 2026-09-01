// Package handler holds the HTTP adapters. They read the trusted identity,
// validate request syntax and map domain outcomes onto the OpenAPI contract.
// No business decision is made here.
package handler

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"cart-service/internal/api/dto/request"
	"cart-service/internal/api/dto/response"
	"cart-service/internal/api/middleware"
	"cart-service/internal/domain"
	"cart-service/internal/service"
)

// Cart serves the cart routes.
type Cart struct {
	service *service.CartService
	logger  *zap.Logger
}

// NewCart builds the cart handler.
func NewCart(cartService *service.CartService, logger *zap.Logger) *Cart {
	return &Cart{service: cartService, logger: logger}
}

// Get handles GET /api/v1/carts/mine.
//
// A Redis miss returns an empty cart with 200. A Product failure returns 200
// with catalog_freshness=DEGRADED. Only a cart store failure produces 503.
func (h *Cart) Get(c *gin.Context) {
	view, err := h.service.GetCart(c.Request.Context(), middleware.GetIdentity(c))
	if err != nil {
		h.fail(c, err)
		return
	}

	// The cart is personal data and must never sit in a shared cache.
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, response.NewCart(view))
}

// AdjustItem handles POST /api/v1/carts/mine/items.
//
// It returns 201 when the SKU entered the cart and 200 when an existing line
// changed. A replayed idempotency key returns the stored outcome, and
// therefore the same status as the original call.
func (h *Cart) AdjustItem(c *gin.Context) {
	var body request.AdjustItem
	if err := c.ShouldBindJSON(&body); err != nil {
		h.badRequest(c, []request.FieldError{{Field: "body", Message: "the request body is not valid JSON"}})
		return
	}
	if fieldErrors := body.Validate(); len(fieldErrors) > 0 {
		h.badRequest(c, fieldErrors)
		return
	}

	result, err := h.service.AdjustItem(
		c.Request.Context(),
		middleware.GetIdentity(c),
		body.SkuID,
		*body.Delta,
		middleware.GetIdempotencyKey(c),
	)
	if err != nil {
		h.fail(c, err)
		return
	}

	status := http.StatusOK
	if result.Created {
		status = http.StatusCreated
	}
	c.JSON(status, response.NewCartMutationResult(result))
}

// DeleteItems handles DELETE /api/v1/carts/mine/items.
func (h *Cart) DeleteItems(c *gin.Context) {
	var body request.DeleteItems
	if err := c.ShouldBindJSON(&body); err != nil {
		h.badRequest(c, []request.FieldError{{Field: "body", Message: "the request body is not valid JSON"}})
		return
	}
	if fieldErrors := body.Validate(); len(fieldErrors) > 0 {
		h.badRequest(c, fieldErrors)
		return
	}

	result, err := h.service.DeleteItems(
		c.Request.Context(),
		middleware.GetIdentity(c),
		body.SkuIDs,
		middleware.GetIdempotencyKey(c),
	)
	if err != nil {
		h.fail(c, err)
		return
	}

	c.JSON(http.StatusOK, response.NewDeleteItemsResult(result))
}

func (h *Cart) badRequest(c *gin.Context, fieldErrors []request.FieldError) {
	middleware.AbortWithProblem(c, response.NewValidationProblem(
		c.Request.URL.Path,
		middleware.GetRequestID(c),
		fieldErrors,
	))
}

// fail maps an error onto a problem document. A recognised domain error keeps
// its code and status; anything else is an internal fault and is logged in
// full rather than shown to the caller.
func (h *Cart) fail(c *gin.Context, err error) {
	var domainErr *domain.Error
	if errors.As(err, &domainErr) {
		if domainErr.Code == domain.CodeCartStoreUnavailable || domainErr.Code == domain.CodeProductUnavailable {
			h.logger.Error("cart dependency unavailable",
				zap.String("code", string(domainErr.Code)),
				zap.String("request_id", middleware.GetRequestID(c)),
				zap.Error(err),
			)
		}
		middleware.AbortWithDomainError(c, domainErr)
		return
	}

	h.logger.Error("unhandled cart error",
		zap.String("request_id", middleware.GetRequestID(c)),
		zap.Error(err),
	)
	middleware.AbortWithProblem(c, response.NewProblem(
		http.StatusInternalServerError,
		domain.CodeInternal,
		"the request could not be completed",
		c.Request.URL.Path,
		middleware.GetRequestID(c),
	))
}
