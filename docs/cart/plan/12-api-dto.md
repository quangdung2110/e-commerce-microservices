# Cart Service — Module 12: API Layer — DTOs

**Document:** 12 | **Module:** API Layer — Data Transfer Objects  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Module 02 (domain models), Module 04 (domain events)

---

## 1. Mục tiêu Module

Định nghĩa tất cả DTOs (Data Transfer Objects) cho HTTP request và response. DTOs tách biệt API contract khỏi domain entities, cho phép thay đổi internal structure mà không ảnh hưởng API.

---

## 2. Request DTOs

### 2.1. Add Item Request (`internal/api/dto/request/add_item.go`)

```go
package request

import (
    "fmt"

    "github.com/go-playground/validator/v10"
)

// AddItemRequest represents POST /api/v1/carts/items
// Ref: cart_workflow.md §2
type AddItemRequest struct {
    SkuID    string `json:"sku_id" validate:"required,max=64"`
    Quantity int    `json:"quantity" validate:"required,min=1,max=100"`
}

// Validate validates the request.
func (r *AddItemRequest) Validate() error {
    validate := validator.New()
    return validate.Struct(r)
}

// AddItemResponse represents the response for add item.
type AddItemResponse struct {
    Success bool   `json:"success"`
    SkuID  string `json:"sku_id"`
    Quantity int  `json:"quantity"`
    UpdatedAt string `json:"updated_at"`
    Message string `json:"message,omitempty"`
}
```

### 2.2. Batch Delete Request (`internal/api/dto/request/batch_delete.go`)

```go
package request

import (
    "github.com/go-playground/validator/v10"
)

// BatchDeleteRequest represents DELETE /api/v1/carts/items
// Ref: cart_workflow.md §4
type BatchDeleteRequest struct {
    SkuIDs []string `json:"sku_ids" validate:"required,min=1,max=100,dive,required,max=64"`
}

// Validate validates the request.
func (r *BatchDeleteRequest) Validate() error {
    validate := validator.New()
    return validate.Struct(r)
}
```

### 2.3. Get Cart Request

GetCart không có body — chỉ cần user ID từ header. Placeholder struct nếu cần query params.

```go
package request

// GetCartRequest has no body — user ID comes from X-User-Id header.
// Pagination (optional): GET /api/v1/carts/mine?page=1&limit=20
type GetCartRequest struct {
    // Placeholder for future pagination if needed
}
```

---

## 3. Response DTOs

### 3.1. Cart Response (`internal/api/dto/response/cart.go`)

```go
package response

import (
    "cart-service/internal/domain/entity"
)

// CartResponse represents the GET /api/v1/carts/mine response.
// Ref: cart_workflow.md §3.3
type CartResponse struct {
    Items           []ItemGroupResponse `json:"items"`
    IsPriceDegraded bool                `json:"is_price_degraded"`
    TotalItems      int                 `json:"total_items"`
    TotalUniqueSKUs int                 `json:"total_unique_skus"`
    Message         string              `json:"message,omitempty"`
}

// ItemGroupResponse groups items by platform/store (if needed).
type ItemGroupResponse struct {
    Items []ItemResponse `json:"items"`
}

// ItemResponse represents a single cart item in the response.
// Ref: cart_workflow.md §3.3
type ItemResponse struct {
    SkuID          string  `json:"sku_id"`
    ProductID       string  `json:"product_id"`
    ProductName    string  `json:"product_name"`
    VariantInfo    string  `json:"variant_info,omitempty"`
    ImageURL       string  `json:"image_url,omitempty"`
    Quantity       int     `json:"quantity"`
    UnitPriceAtAdd float64 `json:"unit_price_at_add"`
    CurrentPrice   float64 `json:"current_price"`
    IsAvailable    bool    `json:"is_available"`
    AddedAt        string  `json:"added_at"`
    UpdatedAt      string  `json:"updated_at"`
}

// FromCartView converts domain CartView to response DTO.
func FromCartView(view *entity.CartView) *CartResponse {
    // Group items — for now, flatten into single group
    items := make([]ItemResponse, len(view.Items))
    for i, item := range view.Items {
        items[i] = ItemResponse{
            SkuID:          item.SkuID,
            ProductID:       item.ProductID,
            ProductName:     item.ProductName,
            VariantInfo:     item.VariantInfo,
            ImageURL:        item.ImageURL,
            Quantity:        item.Quantity,
            UnitPriceAtAdd:  item.UnitPriceAtAdd,
            CurrentPrice:    item.CurrentPrice,
            IsAvailable:     item.IsAvailable,
            AddedAt:        item.AddedAt,
            UpdatedAt:       item.UpdatedAt,
        }
    }

    resp := &CartResponse{
        Items:           []ItemGroupResponse{{Items: items}},
        IsPriceDegraded: view.IsPriceDegraded,
        TotalItems:      view.TotalItems,
        TotalUniqueSKUs: view.TotalUniqueSKUs,
    }

    if view.IsPriceDegraded {
        resp.Message = "Hệ thống đang quá tải, giá sản phẩm có thể chưa được cập nhật mới nhất."
    }

    return resp
}

// NewEmptyCartResponse returns an empty cart response.
func NewEmptyCartResponse(cartID string) *CartResponse {
    return &CartResponse{
        Items:           []ItemGroupResponse{},
        IsPriceDegraded: false,
        TotalItems:      0,
        TotalUniqueSKUs: 0,
    }
}
```

### 3.2. Error Response (`internal/api/dto/response/error.go`)

```go
package response

import (
    "net/http"
    "time"

    "cart-service/internal/domain/entity"
)

// ErrorCode represents API error codes.
type ErrorCode string

const (
    ErrInvalidRequest     ErrorCode = "INVALID_REQUEST"
    ErrProductNotFound   ErrorCode = "PRODUCT_NOT_FOUND"
    ErrProductUnavailable ErrorCode = "PRODUCT_UNAVAILABLE"
    ErrInsufficientStock  ErrorCode = "INSUFFICIENT_STOCK"
    ErrCartFull          ErrorCode = "CART_FULL"
    ErrCartNotFound      ErrorCode = "CART_NOT_FOUND"
    ErrInternal          ErrorCode = "INTERNAL_ERROR"
    ErrUnauthorized      ErrorCode = "UNAUTHORIZED"
)

// ErrorResponse represents a standardized error response.
type ErrorResponse struct {
    Code      ErrorCode `json:"code"`
    Message   string    `json:"message"`
    Details   string    `json:"details,omitempty"`
    RequestID string    `json:"request_id,omitempty"`
    Timestamp string    `json:"timestamp"`
}

// NewErrorResponse creates a new error response.
func NewErrorResponse(code ErrorCode, message string, requestID string) *ErrorResponse {
    return &ErrorResponse{
        Code:      code,
        Message:   message,
        RequestID: requestID,
        Timestamp: time.Now().UTC().Format(time.RFC3339),
    }
}

// FromDomainError converts domain errors to API error responses.
func FromDomainError(err error, requestID string) *ErrorResponse {
    switch err {
    case entity.ErrProductNotFound:
        return NewErrorResponse(ErrProductNotFound, "Sản phẩm không tồn tại", requestID)
    case entity.ErrProductUnavailable:
        return NewErrorResponse(ErrProductUnavailable, "Sản phẩm không còn khả dụng", requestID)
    case entity.ErrInsufficientStock:
        return NewErrorResponse(ErrInsufficientStock, "Số lượng tồn kho không đủ", requestID)
    case entity.ErrCartFull:
        return NewErrorResponse(ErrCartFull, "Giỏ hàng đã đầy", requestID)
    case entity.ErrCartNotFound:
        return NewErrorResponse(ErrCartNotFound, "Giỏ hàng không tồn tại", requestID)
    case entity.ErrInvalidQuantity, entity.ErrQuantityExceedsLimit,
        entity.ErrMissingRequiredField:
        return NewErrorResponse(ErrInvalidRequest, "Yêu cầu không hợp lệ", requestID)
    default:
        return NewErrorResponse(ErrInternal, "Lỗi hệ thống nội bộ", requestID)
    }
}

// HTTPStatusCode returns the HTTP status code for an error code.
func HTTPStatusCode(code ErrorCode) int {
    switch code {
    case ErrUnauthorized:
        return http.StatusUnauthorized
    case ErrProductNotFound, ErrCartNotFound:
        return http.StatusNotFound
    case ErrInvalidRequest, ErrCartFull:
        return http.StatusBadRequest
    case ErrProductUnavailable, ErrInsufficientStock:
        return http.StatusUnprocessableEntity
    default:
        return http.StatusInternalServerError
    }
}
```

---

## 4. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `add_item.go` | `internal/api/dto/request/add_item.go` | Add item request/response |
| `batch_delete.go` | `internal/api/dto/request/batch_delete.go` | Batch delete request |
| `get_cart.go` | `internal/api/dto/request/get_cart.go` | Get cart request (placeholder) |
| `cart.go` | `internal/api/dto/response/cart.go` | Cart response DTOs |
| `error.go` | `internal/api/dto/response/error.go` | Error response DTOs |

---

## 5. Checklist triển khai Module 12

```markdown
## 12 - API DTOs
- [ ] Tạo `internal/api/dto/request/add_item.go` với validation
- [ ] Tạo `internal/api/dto/request/batch_delete.go` với validation
- [ ] Tạo `internal/api/dto/request/get_cart.go`
- [ ] Tạo `internal/api/dto/response/cart.go` với CartView converter
- [ ] Tạo `internal/api/dto/response/error.go` với error code mapping
- [ ] Viết unit tests: validation rules
- [ ] Viết unit tests: FromDomainError mapping
- [ ] Verify `go build ./internal/api/dto` compile thành công
```

---

*Tiếp theo: [13-api-middleware.md](13-api-middleware.md) — HTTP middleware stack.*
