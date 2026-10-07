package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/user-service/internal/api/dto/request"
	"github.com/ecommerce/user-service/internal/api/dto/response"
	"github.com/ecommerce/user-service/internal/api/httpx"
	"github.com/ecommerce/user-service/internal/api/middleware"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/service"
)

// addressPathParam is the name of the address id path parameter.
const addressPathParam = "address_id"

// AddressHandler serves the address book endpoints.
type AddressHandler struct {
	addresses *service.AddressService
}

// NewAddressHandler wires the address endpoints.
func NewAddressHandler(addresses *service.AddressService) *AddressHandler {
	return &AddressHandler{addresses: addresses}
}

// List handles GET /api/v1/users/me/addresses.
func (h *AddressHandler) List(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	addresses, err := h.addresses.List(c.Request.Context(), identity.UserID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewAddressList(addresses))
}

// Create handles POST /api/v1/users/me/addresses. The operation has no
// idempotency key in v1, which the contract states explicitly.
func (h *AddressHandler) Create(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	input, err := decodeAddress(c)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	address, err := h.addresses.Create(c.Request.Context(), identity.UserID, input)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteCreated(c, "/api/v1/users/me/addresses/"+address.ID.String(), response.NewAddress(address))
}

// Replace handles PUT /api/v1/users/me/addresses/{address_id}.
func (h *AddressHandler) Replace(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	addressID, err := httpx.PathUUID(c, addressPathParam)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	input, err := decodeAddress(c)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	address, err := h.addresses.Replace(c.Request.Context(), identity.UserID, addressID, input)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewAddress(address))
}

// SetDefault handles PUT /api/v1/users/me/addresses/{address_id}/default. It is
// idempotent: repeating it on the current default changes nothing.
func (h *AddressHandler) SetDefault(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	addressID, err := httpx.PathUUID(c, addressPathParam)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	address, err := h.addresses.SetDefault(c.Request.Context(), identity.UserID, addressID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewAddress(address))
}

// Delete handles DELETE /api/v1/users/me/addresses/{address_id}. Deleting the
// current default is refused with 409.
func (h *AddressHandler) Delete(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	addressID, err := httpx.PathUUID(c, addressPathParam)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	if err := h.addresses.Delete(c.Request.Context(), identity.UserID, addressID); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.NoContent(c)
}

// decodeAddress reads the shared write payload of create and replace.
func decodeAddress(c *gin.Context) (entity.AddressInput, error) {
	var payload request.AddressWrite
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		return entity.AddressInput{}, err
	}
	return payload.ToInput()
}
