package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/entity"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/port"
)

// Redis key constants - NOTE: These MUST match the constants in port/interfaces.go
// Using port constants directly for consistency
const (
	KeyPrefixCart        = port.KeyPrefixCart         // "cart:user:" (from keybuilder)
	KeySuffixOutbox      = port.KeySuffixOutbox      // ":outbox"
	KeySuffixNotFound    = port.KeySuffixNotFound    // ":notfound"
	KeyPrefixIdempotency = port.KeyPrefixIdempotency // "idem:"

	// Outbox Intent Index keys - using port package constants
	KeyOutboxPublishIntents = port.KeyOutboxPublishIntents // "outbox:intents"
	KeyActiveOutboxStreams  = port.KeyActiveOutboxStreams  // "outbox:active"
	KeyOutboxNotifications  = port.KeyOutboxNotifications  // "outbox:notifications"
	KeyOutboxDLQ            = port.KeyOutboxDLQ            // "outbox:dlq"
)

// IdempotencyStatusProcessing and Done are in port/errors.go

// Connection wraps Redis client
type Connection struct {
	client *redis.Client
}

// NewConnection creates a new Redis connection
func NewConnection(addr, password string, db int) (*Connection, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: password,
		DB:       db,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	return &Connection{client: client}, nil
}

// Client returns the underlying Redis client
func (c *Connection) Client() *redis.Client {
	return c.client
}

// Close closes the Redis connection
func (c *Connection) Close() error {
	return c.client.Close()
}

// CartRepository implements port.CartRepository
type CartRepository struct {
	redis       *redis.Client
	keyBuilder  *CartKeyBuilder
	scriptCache map[string]string
	addUpdateSHA       string
	batchDeleteSHA     string
	silentRehydrateSHA string
	streamTrimSHA      string
	maxQty     int
	maxSKUs    int
	cartTTL    time.Duration
}

// NewCartRepository creates a new CartRepository
func NewCartRepository(
	redisClient *redis.Client,
	keyBuilder *CartKeyBuilder,
	maxQty int,
	maxSKUs int,
	cartTTLSeconds int,
) *CartRepository {
	return &CartRepository{
		redis:       redisClient,
		keyBuilder:  keyBuilder,
		scriptCache: make(map[string]string),
		maxQty:     maxQty,
		maxSKUs:    maxSKUs,
		cartTTL:    time.Duration(cartTTLSeconds) * time.Second,
	}
}

// Ensure cartRepository implements CartRepository interface
var _ port.CartRepository = (*CartRepository)(nil)

// LoadScripts loads all Lua scripts into Redis cache
func (r *CartRepository) LoadScripts(ctx context.Context) error {
	scripts := map[string]string{
		"add_update":        AddUpdateScript,
		"batch_delete":      BatchDeleteScript,
		"silent_rehydrate":  SilentRehydrateScript,
		"stream_trim":       StreamTrimScript,
	}

	for name, script := range scripts {
		cmd := r.redis.ScriptLoad(ctx, script)
		sha, err := cmd.Result()
		if err != nil {
			return fmt.Errorf("failed to load script %s: %w", name, err)
		}
		r.scriptCache[name] = sha

		switch name {
		case "add_update":
			r.addUpdateSHA = sha
		case "batch_delete":
			r.batchDeleteSHA = sha
		case "silent_rehydrate":
			r.silentRehydrateSHA = sha
		case "stream_trim":
			r.streamTrimSHA = sha
		}
	}

	return nil
}

// AddOrUpdateItem adds or updates an item in the cart
func (r *CartRepository) AddOrUpdateItem(
	ctx context.Context,
	userID, skuID string,
	payloadJSON string,
	step int64,
	eventTimeMs int64,
) (string, string, error) {
	cartKey, err := r.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return "", "", fmt.Errorf("failed to build cart key: %w", err)
	}

	outboxKey, err := r.keyBuilder.BuildOutboxStreamKey(userID)
	if err != nil {
		return "", "", fmt.Errorf("failed to build outbox key: %w", err)
	}

	cmd := r.redis.EvalSha(
		ctx,
		r.addUpdateSHA,
		[]string{cartKey, outboxKey},
		skuID,
		payloadJSON,
		r.maxQty,
		r.maxSKUs,
		step,
		eventTimeMs,
		userID,
	)

	result, err := cmd.Result()
	if err != nil {
		if isScriptNotFoundError(err) {
			if reloadErr := r.LoadScripts(ctx); reloadErr != nil {
				return "", "", fmt.Errorf("failed to reload scripts: %w", reloadErr)
			}
			return r.AddOrUpdateItem(ctx, userID, skuID, payloadJSON, step, eventTimeMs)
		}
		return "", "", parseLuaError(err)
	}

	if result != nil {
		if resultStr, ok := result.(string); ok {
			if strings.HasPrefix(resultStr, "ERR:") {
				return "", "", parseLuaErrorFromString(resultStr)
			}
		}
	}

	streamID, _ := r.getLastStreamID(ctx, outboxKey)
	return fmt.Sprintf("%v", result), streamID, nil
}

// BatchDeleteItems deletes items from the cart in batch
func (r *CartRepository) BatchDeleteItems(
	ctx context.Context,
	userID string,
	skuIDs []string,
	clearEventType string,
	eventTimeMs int64,
) (int64, []string, error) {
	cartKey, err := r.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return 0, nil, fmt.Errorf("failed to build cart key: %w", err)
	}

	outboxKey, err := r.keyBuilder.BuildOutboxStreamKey(userID)
	if err != nil {
		return 0, nil, fmt.Errorf("failed to build outbox key: %w", err)
	}

	skuIDsJSON := fmt.Sprintf("%v", skuIDs)

	cmd := r.redis.EvalSha(
		ctx,
		r.batchDeleteSHA,
		[]string{cartKey, outboxKey},
		eventTimeMs,
		clearEventType,
		userID,
		skuIDsJSON,
	)

	result, err := cmd.Result()
	if err != nil {
		if isScriptNotFoundError(err) {
			if reloadErr := r.LoadScripts(ctx); reloadErr != nil {
				return 0, nil, fmt.Errorf("failed to reload scripts: %w", reloadErr)
			}
			return r.BatchDeleteItems(ctx, userID, skuIDs, clearEventType, eventTimeMs)
		}
		return 0, nil, parseLuaError(err)
	}

	deletedCount, err := toInt64(result)
	if err != nil {
		return 0, nil, fmt.Errorf("failed to parse deleted count: %w", err)
	}

	return deletedCount, []string{"multiple"}, nil
}

// SilentRehydrate re-populates cache from PostgreSQL without emitting events
func (r *CartRepository) SilentRehydrate(ctx context.Context, userID string, items []entity.RehydrateItem) error {
	if len(items) == 0 {
		return nil
	}

	cartKey, err := r.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return fmt.Errorf("failed to build cart key: %w", err)
	}

	cmd := r.redis.EvalSha(
		ctx,
		r.silentRehydrateSHA,
		[]string{cartKey},
		fmt.Sprintf("%v", items),
		int(r.cartTTL.Seconds()),
	)

	_, err = cmd.Result()
	if err != nil {
		if isScriptNotFoundError(err) {
			if reloadErr := r.LoadScripts(ctx); reloadErr != nil {
				return fmt.Errorf("failed to reload scripts: %w", reloadErr)
			}
			return r.SilentRehydrate(ctx, userID, items)
		}
		return fmt.Errorf("failed to execute silent_rehydrate script: %w", err)
	}

	return nil
}

// GetCart retrieves all items from the cart
func (r *CartRepository) GetCart(ctx context.Context, userID string) (map[string]string, error) {
	cartKey, err := r.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return nil, fmt.Errorf("failed to build cart key: %w", err)
	}

	result, err := r.redis.HGetAll(ctx, cartKey).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get cart: %w", err)
	}

	return result, nil
}

// UpdateItemAvailability updates only the is_available field
func (r *CartRepository) UpdateItemAvailability(ctx context.Context, userID, skuID string, isAvailable bool) error {
	cartKey, err := r.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return fmt.Errorf("failed to build cart key: %w", err)
	}

	result, err := r.redis.HGet(ctx, cartKey, skuID).Result()
	if err != nil {
		if err == redis.Nil {
			return nil
		}
		return fmt.Errorf("failed to get current item: %w", err)
	}

	var item map[string]interface{}
	if err := json.Unmarshal([]byte(result), &item); err != nil {
		return fmt.Errorf("failed to parse item: %w", err)
	}

	item["is_available"] = isAvailable

	payload, err := json.Marshal(item)
	if err != nil {
		return fmt.Errorf("failed to marshal item: %w", err)
	}

	_, err = r.redis.HSet(ctx, cartKey, skuID, string(payload)).Result()
	if err != nil {
		return fmt.Errorf("failed to update item availability: %w", err)
	}

	return nil
}

// getLastStreamID gets the last stream ID from outbox
func (r *CartRepository) getLastStreamID(ctx context.Context, outboxKey string) (string, error) {
	result, err := r.redis.Eval(
		ctx,
		"return redis.call('XREVRANGE', KEYS[1], '+', '-', 'COUNT', 1)",
		[]string{outboxKey},
	).Result()
	if err != nil {
		return "", err
	}

	if result == nil {
		return "", nil
	}

	arr, ok := result.([]interface{})
	if !ok || len(arr) == 0 {
		return "", nil
	}

	if id, ok := arr[0].(string); ok {
		return id, nil
	}

	return "", nil
}

// parseLuaError converts Lua error to Go error
func parseLuaError(err error) error {
	if err == nil {
		return nil
	}
	return parseLuaErrorFromString(err.Error())
}

// parseLuaErrorFromString parses error string returned from Lua
func parseLuaErrorFromString(errStr string) error {
	switch {
	case strings.Contains(errStr, "Cart Full"):
		return port.ErrCartFull
	case strings.Contains(errStr, "Step Invalid"):
		return port.ErrStepInvalid
	case strings.Contains(errStr, "Quantity Invalid"):
		return port.ErrQuantityInvalid
	case strings.Contains(errStr, "Cannot decrement"):
		return port.ErrCannotDecrementNonExisting
	default:
		return fmt.Errorf("%s", errStr)
	}
}

// isScriptNotFoundError checks if error is "NOSCRIPT"
func isScriptNotFoundError(err error) bool {
	if err == nil {
		return false
	}
	errStr := err.Error()
	return strings.Contains(errStr, "NOSCRIPT") || strings.Contains(errStr, "script does not exist")
}

// toInt64 converts interface{} to int64
func toInt64(v interface{}) (int64, error) {
	switch val := v.(type) {
	case int64:
		return val, nil
	case int:
		return int64(val), nil
	case float64:
		return int64(val), nil
	case string:
		return strconv.ParseInt(val, 10, 64)
	default:
		return 0, fmt.Errorf("cannot convert %T to int64", v)
	}
}
