package infrastructure

import (
	"context"
	"time"

	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/entity"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/config"
)

// ProductServiceClient implements port.ProductServiceClient using gRPC
type ProductServiceClient struct {
	conn    *grpc.ClientConn
	client  ProductServiceProtoClient
	timeout time.Duration
	logger  *zap.Logger
}

// ProductServiceProtoClient is the interface for the generated gRPC client
type ProductServiceProtoClient interface {
	GetProductSku(ctx context.Context, in *GetProductSkuRequest, opts ...grpc.CallOption) (*GetProductSkuResponse, error)
	GetBatchProductSkus(ctx context.Context, in *GetBatchProductSkusRequest, opts ...grpc.CallOption) (*GetBatchProductSkusResponse, error)
}

// GetProductSkuRequest is the request for GetProductSku
type GetProductSkuRequest struct {
	SkuID string `protobuf:"bytes,1,opt,name=sku_id,json=skuId,proto3" json:"sku_id,omitempty"`
}

// GetProductSkuResponse is the response for GetProductSku
type GetProductSkuResponse struct {
	SkuID            string  `protobuf:"bytes,1,opt,name=sku_id,json=skuId,proto3" json:"sku_id,omitempty"`
	ProductID        string  `protobuf:"bytes,2,opt,name=product_id,json=productId,proto3" json:"product_id,omitempty"`
	ProductName      string  `protobuf:"bytes,3,opt,name=product_name,json=productName,proto3" json:"product_name,omitempty"`
	SkuStatus        string  `protobuf:"bytes,4,opt,name=sku_status,json=skuStatus,proto3" json:"sku_status,omitempty"`
	ProductStatus    string  `protobuf:"bytes,5,opt,name=product_status,json=productStatus,proto3" json:"product_status,omitempty"`
	OriginalPrice    float64 `protobuf:"fixed64,6,opt,name=original_price,json=originalPrice,proto3" json:"original_price,omitempty"`
	PromotionalPrice float64 `protobuf:"fixed64,7,opt,name=promotional_price,json=promotionalPrice,proto3" json:"promotional_price,omitempty"`
	DisplayQuantity  int32   `protobuf:"varint,8,opt,name=display_quantity,json=displayQuantity,proto3" json:"display_quantity,omitempty"`
	ImageURL         string  `protobuf:"bytes,9,opt,name=image_url,json=imageUrl,proto3" json:"image_url,omitempty"`
	VariantInfo      string  `protobuf:"bytes,10,opt,name=variant_info,json=variantInfo,proto3" json:"variant_info,omitempty"`
	IsAvailable      bool    `protobuf:"varint,11,opt,name=is_available,json=isAvailable,proto3" json:"is_available,omitempty"`
}

// GetBatchProductSkusRequest is the request for GetBatchProductSkus
type GetBatchProductSkusRequest struct {
	SkuIDs []string `protobuf:"bytes,1,rep,name=sku_ids,json=skuIds,proto3" json:"sku_ids,omitempty"`
}

// GetBatchProductSkusResponse is the response for GetBatchProductSkus
type GetBatchProductSkusResponse struct {
	Products map[string]*ProductInfo `protobuf:"bytes,1,rep,name=products,proto3" json:"products,omitempty" protobuf_key:"bytes,1,opt,name=key,proto3" protobuf_val:"bytes,2,opt,name=value,proto3"`
}

// ProductInfo represents product information
type ProductInfo struct {
	SkuID            string  `protobuf:"bytes,1,opt,name=sku_id,json=skuId,proto3" json:"sku_id,omitempty"`
	ProductID        string  `protobuf:"bytes,2,opt,name=product_id,json=productId,proto3" json:"product_id,omitempty"`
	ProductName      string  `protobuf:"bytes,3,opt,name=product_name,json=productName,proto3" json:"product_name,omitempty"`
	OriginalPrice    float64 `protobuf:"fixed64,4,opt,name=original_price,json=originalPrice,proto3" json:"original_price,omitempty"`
	PromotionalPrice float64 `protobuf:"fixed64,5,opt,name=promotional_price,json=promotionalPrice,proto3" json:"promotional_price,omitempty"`
	CurrentPrice     float64 `protobuf:"fixed64,6,opt,name=current_price,json=currentPrice,proto3" json:"current_price,omitempty"`
	DisplayQuantity  int32   `protobuf:"varint,7,opt,name=display_quantity,json=displayQuantity,proto3" json:"display_quantity,omitempty"`
	ImageURL         string  `protobuf:"bytes,8,opt,name=image_url,json=imageUrl,proto3" json:"image_url,omitempty"`
	VariantInfo      string  `protobuf:"bytes,9,opt,name=variant_info,json=variantInfo,proto3" json:"variant_info,omitempty"`
	IsAvailable      bool    `protobuf:"varint,10,opt,name=is_available,json=isAvailable,proto3" json:"is_available,omitempty"`
	SkuStatus        string  `protobuf:"bytes,11,opt,name=sku_status,json=skuStatus,proto3" json:"sku_status,omitempty"`
	ProductStatus    string  `protobuf:"bytes,12,opt,name=product_status,json=productStatus,proto3" json:"product_status,omitempty"`
}

// NewProductServiceClient creates a new Product Service gRPC client
func NewProductServiceClient(cfg config.ProductServiceConfig, logger *zap.Logger) (*ProductServiceClient, error) {
	timeout := config.ParseDuration(cfg.Timeout)
	if timeout == 0 {
		timeout = 200 * time.Millisecond
	}

	conn, err := grpc.Dial(
		cfg.Address,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
		grpc.WithTimeout(timeout),
	)
	if err != nil {
		return nil, err
	}

	return &ProductServiceClient{
		conn:    conn,
		client:  nil, // Will be set when real proto is generated
		timeout: timeout,
		logger:  logger,
	}, nil
}

// GetProductSku retrieves a single product SKU
func (c *ProductServiceClient) GetProductSku(ctx context.Context, skuID string) (*entity.ProductInfo, error) {
	if c.client == nil {
		return nil, nil // Mock mode - return nil when no real client
	}

	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	req := &GetProductSkuRequest{SkuID: skuID}
	resp, err := c.client.GetProductSku(ctx, req)
	if err != nil {
		c.logger.Warn("Failed to get product SKU",
			zap.String("sku_id", skuID),
			zap.Error(err),
		)
		return nil, err
	}

	return &entity.ProductInfo{
		SkuID:            resp.SkuID,
		ProductID:        resp.ProductID,
		ProductName:      resp.ProductName,
		SkuStatus:        resp.SkuStatus,
		ProductStatus:    resp.ProductStatus,
		OriginalPrice:    resp.OriginalPrice,
		PromotionalPrice: resp.PromotionalPrice,
		DisplayQuantity:  resp.DisplayQuantity,
		ImageURL:         resp.ImageURL,
		VariantInfo:      resp.VariantInfo,
		IsAvailable:      resp.IsAvailable,
	}, nil
}

// GetBatchProductSkus retrieves multiple product SKUs in batch
func (c *ProductServiceClient) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.BatchProductSkusResponse, error) {
	if c.client == nil {
		// Mock mode - return mock data for testing
		return c.mockBatchResponse(skuIDs)
	}

	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	req := &GetBatchProductSkusRequest{SkuIDs: skuIDs}
	resp, err := c.client.GetBatchProductSkus(ctx, req)
	if err != nil {
		c.logger.Warn("Failed to get batch product SKUs",
			zap.Int("count", len(skuIDs)),
			zap.Error(err),
		)
		return nil, err
	}

	products := make(map[string]*entity.ProductInfo)
	for skuID, product := range resp.Products {
		products[skuID] = &entity.ProductInfo{
			SkuID:            product.SkuID,
			ProductID:        product.ProductID,
			ProductName:      product.ProductName,
			OriginalPrice:    product.OriginalPrice,
			PromotionalPrice: product.PromotionalPrice,
			CurrentPrice:     product.CurrentPrice,
			DisplayQuantity:  product.DisplayQuantity,
			ImageURL:         product.ImageURL,
			VariantInfo:      product.VariantInfo,
			IsAvailable:     product.IsAvailable,
			SkuStatus:       product.SkuStatus,
			ProductStatus:   product.ProductStatus,
		}
	}

	return &entity.BatchProductSkusResponse{Products: products}, nil
}

// mockBatchResponse returns mock data for testing when no real gRPC client
func (c *ProductServiceClient) mockBatchResponse(skuIDs []string) (*entity.BatchProductSkusResponse, error) {
	products := make(map[string]*entity.ProductInfo)
	for _, skuID := range skuIDs {
		products[skuID] = &entity.ProductInfo{
			SkuID:            skuID,
			ProductID:        "mock-product-id",
			ProductName:      "Mock Product",
			OriginalPrice:    100000,
			PromotionalPrice: 90000,
			CurrentPrice:     90000,
			DisplayQuantity:  100,
			ImageURL:         "https://cdn.example.com/mock.jpg",
			VariantInfo:      "Mock Variant",
			IsAvailable:      true,
			SkuStatus:       "ACTIVE",
			ProductStatus:   "ACTIVE",
		}
	}
	return &entity.BatchProductSkusResponse{Products: products}, nil
}

// Close closes the gRPC connection
func (c *ProductServiceClient) Close() error {
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}
