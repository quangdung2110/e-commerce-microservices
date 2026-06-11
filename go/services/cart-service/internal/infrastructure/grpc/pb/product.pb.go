package pb

import (
	"context"
	"fmt"
	"math"
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
	err := c.cc.Invoke(ctx, "/com.platform.productservice.proto.CartServiceGrpc/GetSku", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *grpcClient) GetBatchSkus(ctx context.Context, in *GetBatchSkusRequest, opts ...grpc.CallOption) (*GetBatchSkusResponse, error) {
	out := new(GetBatchSkusResponse)
	err := c.cc.Invoke(ctx, "/com.platform.productservice.proto.CartServiceGrpc/GetBatchSkus", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ─── Custom Marshalers and Unmarshalers for Hand-Written Protobuf ───

func (x *GetSkuRequest) Marshal() ([]byte, error) {
	strBytes := []byte(x.SkuId)
	length := len(strBytes)

	var lengthBytes []byte
	for length >= 128 {
		lengthBytes = append(lengthBytes, byte(length&0x7f|0x80))
		length >>= 7
	}
	lengthBytes = append(lengthBytes, byte(length))

	buf := make([]byte, 0, 1+len(lengthBytes)+len(strBytes))
	buf = append(buf, 0x0a)
	buf = append(buf, lengthBytes...)
	buf = append(buf, strBytes...)
	return buf, nil
}

func (x *GetSkuResponse) Unmarshal(data []byte) error {
	offset := 0
	for offset < len(data) {
		tag, n := decodeVarint(data[offset:])
		if n == 0 {
			break
		}
		offset += n

		fieldNum := tag >> 3
		wireType := tag & 7

		if wireType == 2 {
			length, n := decodeVarint(data[offset:])
			if n == 0 {
				break
			}
			offset += n

			valueBytes := data[offset : offset+int(length)]
			offset += int(length)

			switch fieldNum {
			case 1:
				sku := &SkuData{}
				if err := sku.Unmarshal(valueBytes); err == nil {
					x.Result = &SkuResult{Sku: sku}
				}
			case 2:
				nf := &SkuNotFound{}
				if err := nf.Unmarshal(valueBytes); err == nil {
					x.Result = &NotFoundResult{NotFound: nf}
				}
			case 3:
				ua := &SkuUnavailable{}
				if err := ua.Unmarshal(valueBytes); err == nil {
					x.Result = &UnavailableResult{Unavailable: ua}
				}
			}
		} else {
			// Skip unknown wire types
		}
	}
	return nil
}

func (x *SkuData) Unmarshal(data []byte) error {
	offset := 0
	for offset < len(data) {
		tag, n := decodeVarint(data[offset:])
		if n == 0 {
			break
		}
		offset += n

		fieldNum := tag >> 3
		wireType := tag & 7

		if wireType == 2 {
			length, n := decodeVarint(data[offset:])
			if n == 0 {
				break
			}
			offset += n

			valueBytes := data[offset : offset+int(length)]
			offset += int(length)

			switch fieldNum {
			case 1:
				x.SkuId = string(valueBytes)
			case 2:
				x.ProductId = string(valueBytes)
			case 3:
				x.ProductName = string(valueBytes)
			case 4:
				x.VariantInfo = string(valueBytes)
			case 5:
				x.ImageUrl = string(valueBytes)
			case 7:
				x.Status = string(valueBytes)
			}
		} else if wireType == 1 {
			if offset+8 <= len(data) {
				bits := uint64(data[offset]) | uint64(data[offset+1])<<8 | uint64(data[offset+2])<<16 | uint64(data[offset+3])<<24 |
					uint64(data[offset+4])<<32 | uint64(data[offset+5])<<40 | uint64(data[offset+6])<<48 | uint64(data[offset+7])<<56
				x.Price = math.Float64frombits(bits)
				offset += 8
			}
		} else if wireType == 0 {
			val, n := decodeVarint(data[offset:])
			if n == 0 {
				break
			}
			offset += n
			if fieldNum == 8 {
				x.StockQty = int64(val)
			}
		}
	}
	return nil
}

func (x *SkuNotFound) Unmarshal(data []byte) error {
	offset := 0
	for offset < len(data) {
		tag, n := decodeVarint(data[offset:])
		if n == 0 {
			break
		}
		offset += n
		fieldNum := tag >> 3
		wireType := tag & 7
		if wireType == 2 {
			length, n := decodeVarint(data[offset:])
			if n == 0 {
				break
			}
			offset += n
			valueBytes := data[offset : offset+int(length)]
			offset += int(length)
			if fieldNum == 1 {
				x.SkuId = string(valueBytes)
			}
		}
	}
	return nil
}

func (x *SkuUnavailable) Unmarshal(data []byte) error {
	offset := 0
	for offset < len(data) {
		tag, n := decodeVarint(data[offset:])
		if n == 0 {
			break
		}
		offset += n
		fieldNum := tag >> 3
		wireType := tag & 7
		if wireType == 2 {
			length, n := decodeVarint(data[offset:])
			if n == 0 {
				break
			}
			offset += n
			valueBytes := data[offset : offset+int(length)]
			offset += int(length)
			switch fieldNum {
			case 1:
				x.SkuId = string(valueBytes)
			case 2:
				x.Reason = string(valueBytes)
			}
		}
	}
	return nil
}

func (x *GetBatchSkusRequest) Marshal() ([]byte, error) {
	var buf []byte
	for _, skuID := range x.SkuIds {
		strBytes := []byte(skuID)
		length := len(strBytes)

		var lengthBytes []byte
		for length >= 128 {
			lengthBytes = append(lengthBytes, byte(length&0x7f|0x80))
			length >>= 7
		}
		lengthBytes = append(lengthBytes, byte(length))

		buf = append(buf, 0x0a)
		buf = append(buf, lengthBytes...)
		buf = append(buf, strBytes...)
	}
	return buf, nil
}

func (x *GetBatchSkusResponse) Unmarshal(data []byte) error {
	if x.Skus == nil {
		x.Skus = make(map[string]*SkuData)
	}
	offset := 0
	for offset < len(data) {
		tag, n := decodeVarint(data[offset:])
		if n == 0 {
			break
		}
		offset += n
		fieldNum := tag >> 3
		wireType := tag & 7

		if wireType == 2 {
			length, n := decodeVarint(data[offset:])
			if n == 0 {
				break
			}
			offset += n
			valueBytes := data[offset : offset+int(length)]
			offset += int(length)

			switch fieldNum {
			case 1:
				key, val := decodeMapEntry(valueBytes)
				if val != nil {
					x.Skus[key] = val
				}
			case 2:
				x.NotFound = append(x.NotFound, string(valueBytes))
			case 3:
				x.Unavailable = append(x.Unavailable, string(valueBytes))
			}
		}
	}
	return nil
}

func decodeMapEntry(data []byte) (string, *SkuData) {
	var key string
	var val *SkuData
	offset := 0
	for offset < len(data) {
		tag, n := decodeVarint(data[offset:])
		if n == 0 {
			break
		}
		offset += n
		fieldNum := tag >> 3
		wireType := tag & 7

		if wireType == 2 {
			length, n := decodeVarint(data[offset:])
			if n == 0 {
				break
			}
			offset += n
			valueBytes := data[offset : offset+int(length)]
			offset += int(length)

			if fieldNum == 1 {
				key = string(valueBytes)
			} else if fieldNum == 2 {
				val = &SkuData{}
				_ = val.Unmarshal(valueBytes)
			}
		}
	}
	return key, val
}

func decodeVarint(data []byte) (uint64, int) {
	var val uint64
	var shift uint
	for i, b := range data {
		val |= uint64(b&0x7f) << shift
		if b&0x80 == 0 {
			return val, i + 1
		}
		shift += 7
	}
	return 0, 0
}
