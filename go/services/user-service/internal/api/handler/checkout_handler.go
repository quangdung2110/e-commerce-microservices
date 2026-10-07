package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/user-service/internal/api/dto/response"
	"github.com/ecommerce/user-service/internal/api/httpx"
	"github.com/ecommerce/user-service/internal/domain/service"
)

// userPathParam is the name of the user id path parameter of the internal API.
const userPathParam = "user_id"

// CheckoutHandler serves the internal address snapshot Checkout Service reads
// over mTLS.
//
// Checkout never reads the User database: it asks for one address of one user
// and persists the answer into its own session and later into the order, so a
// later edit or deletion cannot rewrite shipping history (ADR-003).
type CheckoutHandler struct {
	addresses *service.AddressService
}

// NewCheckoutHandler wires the internal endpoint.
func NewCheckoutHandler(addresses *service.AddressService) *CheckoutHandler {
	return &CheckoutHandler{addresses: addresses}
}

// Snapshot handles
// GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot.
//
// The caller supplies both ids and the ownership check happens here, so an
// address that belongs to a different user is reported as missing.
func (h *CheckoutHandler) Snapshot(c *gin.Context) {
	userID, err := httpx.PathUUID(c, userPathParam)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	addressID, err := httpx.PathUUID(c, addressPathParam)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	address, err := h.addresses.Get(c.Request.Context(), userID, addressID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewCheckoutAddressSnapshot(address))
}
