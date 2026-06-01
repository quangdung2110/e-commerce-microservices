package pb

import (
	"context"
	"fmt"
	"strings"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// ProductServiceClient is the gRPC client interface for the ProductService.
type ProductServiceClient interface {
	GetSku(ctx context.Context, in *GetSkuRequest, opts ...grpc.CallOption) (*GetSkuResponse, error)
	GetBatchSkus(ctx context.Context, in *GetBatchSkusRequest, opts ...grpc.CallOption) (*GetBatchSkusResponse, error)
}

// ─── Messages ───────────────────────────────────────────────────────────────

type GetSkuRequest struct {
	SkuId string `json:"sku_id"`
}

func (x *GetSkuRequest) Reset()        { *x = GetSkuRequest{} }
func (x *GetSkuRequest) String() string { return fmt.Sprintf("GetSkuRequest{SkuId:%s}", x.SkuId) }
func (x *GetSkuRequest) ProtoMessage() {}

type GetSkuResponse struct {
	Result isGetSkuResponse_Result `json:"-"`
}

type isGetSkuResponse_Result interface {
	isGetSkuResponse_Result()
}

type SkuResult struct {
	Sku *SkuData `json:"sku,omitempty"`
}

func (x *SkuResult) isGetSkuResponse_Result() {}

type NotFoundResult struct {
	NotFound *SkuNotFound `json:"not_found,omitempty"`
}

func (x *NotFoundResult) isGetSkuResponse_Result() {}

type UnavailableResult struct {
	Unavailable *SkuUnavailable `json:"unavailable,omitempty"`
}

func (x *UnavailableResult) isGetSkuResponse_Result() {}

func (x *GetSkuResponse) GetSku() *SkuData {
	if r, ok := x.Result.(*SkuResult); ok {
		return r.Sku
	}
	return nil
}

func (x *GetSkuResponse) GetNotFound() *SkuNotFound {
	if r, ok := x.Result.(*NotFoundResult); ok {
		return r.NotFound
	}
	return nil
}

func (x *GetSkuResponse) GetUnavailable() *SkuUnavailable {
	if r, ok := x.Result.(*UnavailableResult); ok {
		return r.Unavailable
	}
	return nil
}

func (x *GetSkuResponse) Reset()        { *x = GetSkuResponse{} }
func (x *GetSkuResponse) String() string { return fmt.Sprintf("GetSkuResponse{Result:%v}", x.Result) }
func (x *GetSkuResponse) ProtoMessage() {}

type GetBatchSkusRequest struct {
	SkuIds []string `json:"sku_ids"`
}

func (x *GetBatchSkusRequest) Reset()        { *x = GetBatchSkusRequest{} }
func (x *GetBatchSkusRequest) String() string { return fmt.Sprintf("GetBatchSkusRequest{SkuIds:%v}", x.SkuIds) }
func (x *GetBatchSkusRequest) ProtoMessage()  {}

type GetBatchSkusResponse struct {
	Skus        map[string]*SkuData `json:"skus"`
	NotFound    []string             `json:"not_found"`
	Unavailable []string             `json:"unavailable"`
}

func (x *GetBatchSkusResponse) Reset()        { *x = GetBatchSkusResponse{} }
func (x *GetBatchSkusResponse) String() string {
	return fmt.Sprintf("GetBatchSkusResponse{Skus:%d, NotFound:%d, Unavailable:%d}",
		len(x.Skus), len(x.NotFound), len(x.Unavailable))
}
func (x *GetBatchSkusResponse) ProtoMessage() {}

type SkuData struct {
	SkuId       string                 `json:"sku_id"`
	ProductId   string                 `json:"product_id"`
	ProductName string                 `json:"product_name"`
	VariantInfo string                 `json:"variant_info"`
	ImageUrl    string                 `json:"image_url"`
	Price       float64                `json:"price"`
	Status      string                 `json:"status"`
	StockQty    int64                  `json:"stock_qty"`
	CreatedAt   *timestamppb.Timestamp `json:"created_at"`
	UpdatedAt   *timestamppb.Timestamp `json:"updated_at"`
}

func (x *SkuData) Reset()        { *x = SkuData{} }
func (x *SkuData) String() string {
	return fmt.Sprintf("SkuData{SkuId:%s, ProductId:%s, Status:%s, Price:%.2f}",
		x.SkuId, x.ProductId, x.Status, x.Price)
}
func (x *SkuData) ProtoMessage() {}

// IsActive returns true when status equals "ACTIVE" (case-insensitive).
func (x *SkuData) IsActive() bool {
	return strings.EqualFold(x.Status, "ACTIVE")
}

type SkuNotFound struct {
	SkuId string `json:"sku_id"`
}

func (x *SkuNotFound) Reset()        { *x = SkuNotFound{} }
func (x *SkuNotFound) String() string { return fmt.Sprintf("SkuNotFound{SkuId:%s}", x.SkuId) }
func (x *SkuNotFound) ProtoMessage() {}

type SkuUnavailable struct {
	SkuId  string `json:"sku_id"`
	Reason string `json:"reason"`
}

func (x *SkuUnavailable) Reset()        { *x = SkuUnavailable{} }
func (x *SkuUnavailable) String() string { return fmt.Sprintf("SkuUnavailable{SkuId:%s, Reason:%s}", x.SkuId, x.Reason) }
func (x *SkuUnavailable) ProtoMessage()  {}

// NewProductServiceClient creates a gRPC client from a connection.
// It is the generated-code equivalent provided by protoc-gen-go.
func NewProductServiceClient(cc *grpc.ClientConn) ProductServiceClient {
	return &grpcClient{cc: cc}
}

type grpcClient struct {
	cc *grpc.ClientConn
}

func (c *grpcClient) GetSku(ctx context.Context, in *GetSkuRequest, opts ...grpc.CallOption) (*GetSkuResponse, error) {
	out := new(GetSkuResponse)
	err := c.cc.Invoke(ctx, "/product.ProductService/GetSku", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *grpcClient) GetBatchSkus(ctx context.Context, in *GetBatchSkusRequest, opts ...grpc.CallOption) (*GetBatchSkusResponse, error) {
	out := new(GetBatchSkusResponse)
	err := c.cc.Invoke(ctx, "/product.ProductService/GetBatchSkus", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}
