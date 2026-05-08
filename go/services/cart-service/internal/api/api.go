package api

import (
	"context"
	"fmt"
	"time"

	goredis "github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/redis"
)

// Dependencies holds all dependencies for the API layer
type Dependencies struct {
	// Redis operations
	RedisClient    *goredis.Client
	CartOps        CartOperations
	IntentIndex    IntentIndexManager
	NegativeCache  NegativeCacheManager
	Idempotency    IdempotencyManager

	// Product Service
	ProductClient ProductServiceClient

	// Database (for cache miss)
	DB DBClient

	// Config
	Config *config.Config

	// Logger
	Logger *zap.Logger
}

// CartOperations defines Redis cart operations
type CartOperations interface {
	AddOrUpdateItem(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error)
	BatchDeleteItems(ctx context.Context, userID string, skuIDs []string, clearEventType string, eventTimeMs int64) (int64, []string, error)
	SilentRehydrate(ctx context.Context, userID string, items []entity.RehydrateItem) error
	GetCart(ctx context.Context, userID string) (map[string]string, error)
	UpdateItemAvailability(ctx context.Context, userID, skuID string, isAvailable bool) error
	LoadScripts(ctx context.Context) error
}

// IntentIndexManager manages the Intent Index protocol
type IntentIndexManager interface {
	PreRegisterIntent(ctx context.Context, streamKey string) error
	MarkActive(ctx context.Context, streamKey string) error
	ClearIntent(ctx context.Context, streamKey string) error
	ExecuteWithIntent(ctx context.Context, streamKey string, operation func() error) error
}

// NegativeCacheManager manages negative caching
type NegativeCacheManager interface {
	HasCachedResult(ctx context.Context, userID string) (bool, error)
	SetNotFound(ctx context.Context, userID string) error
	Invalidate(ctx context.Context, userID string) error
}

// IdempotencyManager manages idempotency keys
type IdempotencyManager interface {
	CheckAndLock(ctx context.Context, orderID string) (bool, error)
	MarkDone(ctx context.Context, orderID string) error
	IsProcessed(ctx context.Context, orderID string) (bool, error)
}

// ProductServiceClient defines Product Service operations
type ProductServiceClient interface {
	GetProductSku(ctx context.Context, skuID string) (*ProductInfo, error)
	GetBatchProductSkus(ctx context.Context, skuIDs []string) (*BatchProductSkusResponse, error)
	Close() error
}

// ProductInfo represents product information
type ProductInfo struct {
	SkuID             string  `json:"sku_id"`
	ProductID         string  `json:"product_id"`
	ProductName       string  `json:"product_name"`
	VariantInfo       string  `json:"variant_info"`
	ImageURL          string  `json:"image_url"`
	OriginalPrice     float64 `json:"original_price"`
	PromotionalPrice  float64 `json:"promotional_price"`
	CurrentPrice      float64 `json:"current_price"`
	DisplayQuantity   int     `json:"display_quantity"`
	SkuStatus        string  `json:"sku_status"`
	ProductStatus    string  `json:"product_status"`
	IsAvailable      bool    `json:"is_available"`
}

// BatchProductSkusResponse represents batch product response
type BatchProductSkusResponse struct {
	Products map[string]*ProductInfo `json:"products"`
	Errors   map[string]string      `json:"errors,omitempty"`
}

// DBClient defines database operations for cache miss path
type DBClient interface {
	GetCartStatus(ctx context.Context, cartID string) (string, error)
	GetCartItems(ctx context.Context, cartID string) ([]CartItemRecord, error)
	Close() error
}

// CartItemRecord represents a cart item from DB
type CartItemRecord struct {
	ItemID         string  `json:"item_id"`
	CartID         string  `json:"cart_id"`
	ProductID      string  `json:"product_id"`
	SkuID          string  `json:"sku_id"`
	Quantity       int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	ProductSnapshot string  `json:"product_snapshot"`
	RedisStreamID  string  `json:"redis_stream_id"`
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
}

// GrpcResult wraps gRPC call results
type GrpcResult struct {
	Success bool
	Products map[string]*ProductInfo
	Error error
}

// Initialize initializes the API dependencies
func Initialize(cfg *config.Config, redisClient *goredis.Client, logger *zap.Logger) (*Dependencies, error) {
	deps := &Dependencies{
		RedisClient: redisClient,
		Config:     cfg,
		Logger:     logger,
	}

	// Initialize key builder
	keyBuilder := keybuilder.NewCartKeyBuilder()

	// Initialize cart operations
	deps.CartOps = redis.NewCartOperations(
		redisClient,
		keyBuilder,
		cfg.Cart.MaxQuantityPerItem,
		cfg.Cart.MaxUniqueSKUs,
		cfg.Cart.TTL,
	)

	// Load Lua scripts
	ctx := context.Background()
	if err := deps.CartOps.LoadScripts(ctx); err != nil {
		return nil, fmt.Errorf("failed to load Lua scripts: %w", err)
	}
	logger.Info("Lua scripts loaded successfully")

	// Initialize intent index manager
	deps.IntentIndex = redis.NewIntentIndexManager(redisClient, keyBuilder)

	// Initialize negative cache manager
	deps.NegativeCache = redis.NewNegativeCacheManager(redisClient, keyBuilder, cfg.Cart.NegativeCacheTTL)

	// Initialize idempotency manager
	deps.Idempotency = redis.NewIdempotencyManager(redisClient, keyBuilder)

	// Initialize Product Service client (placeholder - would need gRPC setup)
	// deps.ProductClient = NewProductServiceClient(cfg.ProductService, logger)

	// Initialize DB client (placeholder - would need GORM setup)
	// deps.DB = NewDBClient(cfg.Postgres, logger)

	return deps, nil
}
