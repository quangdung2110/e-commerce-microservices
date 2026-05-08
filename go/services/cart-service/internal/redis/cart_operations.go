package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
)

// CartOperations defines Redis cart operations
type CartOperations interface {
	// AddOrUpdateItem executes Lua script for atomic add/update
	// Returns the updated item JSON and stream_id
	AddOrUpdateItem(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error)

	// BatchDeleteItems executes Lua script for batch delete
	// Returns deleted count and stream_ids
	BatchDeleteItems(ctx context.Context, userID string, skuIDs []string, clearEventType string, eventTimeMs int64) (int64, []string, error)

	// SilentRehydrate executes Lua script for cache-miss recovery
	// Does NOT emit events
	SilentRehydrate(ctx context.Context, userID string, items []entity.RehydrateItem) error

	// GetCart retrieves all items from cart
	GetCart(ctx context.Context, userID string) (map[string]string, error)

	// UpdateItemAvailability updates only the is_available field
	// Exception: Only used when gRPC returns is_available=false (no EXPIRE, no XADD)
	UpdateItemAvailability(ctx context.Context, userID, skuID string, isAvailable bool) error

	// ScriptExists checks if a script is loaded in Redis
	ScriptExists(ctx context.Context, sha1 string) (bool, error)

	// LoadScripts loads all Lua scripts into Redis
	LoadScripts(ctx context.Context) error
}

// CartOperationsImpl implements CartOperations using Redis
type CartOperationsImpl struct {
	redis       *redis.Client
	keyBuilder  *keybuilder.CartKeyBuilder
	scriptCache map[string]string
	addUpdateSHA        string
	batchDeleteSHA      string
	silentRehydrateSHA  string
	streamTrimSHA       string
	maxQty      int
	maxSKUs     int
	cartTTL     time.Duration
}

// NewCartOperations creates a new CartOperations implementation
func NewCartOperations(
	redisClient *redis.Client,
	keyBuilder *keybuilder.CartKeyBuilder,
	maxQty int,
	maxSKUs int,
	cartTTLSeconds int,
) *CartOperationsImpl {
	return &CartOperationsImpl{
		redis:       redisClient,
		keyBuilder:  keyBuilder,
		scriptCache: make(map[string]string),
		maxQty:      maxQty,
		maxSKUs:     maxSKUs,
		cartTTL:     time.Duration(cartTTLSeconds) * time.Second,
	}
}

// LoadScripts loads all Lua scripts into Redis cache
func (c *CartOperationsImpl) LoadScripts(ctx context.Context) error {
	// Load add_update script
	addUpdateSHA, err := c.loadScript(ctx, ScriptNameAddUpdate)
	if err != nil {
		return fmt.Errorf("failed to load add_update script: %w", err)
	}
	c.addUpdateSHA = addUpdateSHA

	// Load batch_delete script
	batchDeleteSHA, err := c.loadScript(ctx, ScriptNameBatchDelete)
	if err != nil {
		return fmt.Errorf("failed to load batch_delete script: %w", err)
	}
	c.batchDeleteSHA = batchDeleteSHA

	// Load silent_rehydrate script
	silentRehydrateSHA, err := c.loadScript(ctx, ScriptNameSilentRehydrate)
	if err != nil {
		return fmt.Errorf("failed to load silent_rehydrate script: %w", err)
	}
	c.silentRehydrateSHA = silentRehydrateSHA

	// Load stream_trim script
	streamTrimSHA, err := c.loadScript(ctx, ScriptNameStreamTrim)
	if err != nil {
		return fmt.Errorf("failed to load stream_trim script: %w", err)
	}
	c.streamTrimSHA = streamTrimSHA

	return nil
}

// loadScript loads a single script into Redis and returns its SHA
func (c *CartOperationsImpl) loadScript(ctx context.Context, scriptName string) (string, error) {
	script, ok := scripts[scriptName]
	if !ok {
		return "", fmt.Errorf("script not found: %s", scriptName)
	}

	cmd := c.redis.ScriptLoad(ctx, script)
	sha, err := cmd.Result()
	if err != nil {
		return "", err
	}

	c.scriptCache[scriptName] = sha
	return sha, nil
}

// ScriptExists checks if a script is loaded in Redis
func (c *CartOperationsImpl) ScriptExists(ctx context.Context, sha1 string) (bool, error) {
	result, err := c.redis.ScriptExists(ctx, sha1).Result()
	if err != nil {
		return false, err
	}
	if len(result) > 0 {
		return result[0], nil
	}
	return false, nil
}

// AddOrUpdateItem adds or updates an item in the cart
// Returns updated item JSON and stream_id
func (c *CartOperationsImpl) AddOrUpdateItem(
	ctx context.Context,
	userID, skuID string,
	payloadJSON string,
	step int64,
	eventTimeMs int64,
) (string, string, error) {
	cartKey, err := c.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return "", "", fmt.Errorf("failed to build cart key: %w", err)
	}

	outboxKey, err := c.keyBuilder.BuildOutboxStreamKey(userID)
	if err != nil {
		return "", "", fmt.Errorf("failed to build outbox key: %w", err)
	}

	// Execute Lua script using EVALSHA
	cmd := c.redis.EvalSha(
		ctx,
		c.addUpdateSHA,
		[]string{cartKey, outboxKey},
		skuID,
		payloadJSON,
		c.maxQty,
		c.maxSKUs,
		step,
		eventTimeMs,
		userID,
	)

	result, err := cmd.Result()
	if err != nil {
		// Check if script was not found - need to reload
		if err == redis.Nil || isScriptNotFoundError(err) {
			if reloadErr := c.LoadScripts(ctx); reloadErr != nil {
				return "", "", fmt.Errorf("failed to reload scripts: %w", reloadErr)
			}
			// Retry with reloaded script
			cmd = c.redis.EvalSha(
				ctx,
				c.addUpdateSHA,
				[]string{cartKey, outboxKey},
				skuID,
				payloadJSON,
				c.maxQty,
				c.maxSKUs,
				step,
				eventTimeMs,
				userID,
			)
			result, err = cmd.Result()
		}
	}

	// Check if result is an error string (Lua returns "ERR:..." as string)
	if result != nil {
		if resultStr, ok := result.(string); ok {
			if strings.HasPrefix(resultStr, "ERR:") {
				return "", "", parseLuaErrorFromString(resultStr)
			}
		}
	}

	if err != nil {
		return "", "", c.parseLuaError(err)
	}

	// Parse result - should be JSON string
	if result == nil {
		return "", "", fmt.Errorf("unexpected nil result from Lua script")
	}

	resultStr, ok := result.(string)
	if !ok {
		return "", "", fmt.Errorf("unexpected result type: %T", result)
	}

	// Get stream ID from result metadata if available
	streamID, err := c.getLastStreamID(ctx, outboxKey)
	if err != nil {
		// Log but don't fail - stream_id is for tracing
		streamID = ""
	}

	return resultStr, streamID, nil
}

// BatchDeleteItems deletes items from the cart in batch
// Returns deleted count and stream_ids
func (c *CartOperationsImpl) BatchDeleteItems(
	ctx context.Context,
	userID string,
	skuIDs []string,
	clearEventType string,
	eventTimeMs int64,
) (int64, []string, error) {
	cartKey, err := c.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return 0, nil, fmt.Errorf("failed to build cart key: %w", err)
	}

	outboxKey, err := c.keyBuilder.BuildOutboxStreamKey(userID)
	if err != nil {
		return 0, nil, fmt.Errorf("failed to build outbox key: %w", err)
	}

	// Get count before for stream tracking
	streamIDs := make([]string, 0, len(skuIDs))

	// Marshal SKU IDs to JSON
	skuIDsJSON, err := json.Marshal(skuIDs)
	if err != nil {
		return 0, nil, fmt.Errorf("failed to marshal SKU IDs: %w", err)
	}

	// Execute Lua script using EVALSHA
	cmd := c.redis.EvalSha(
		ctx,
		c.batchDeleteSHA,
		[]string{cartKey, outboxKey},
		eventTimeMs,
		clearEventType,
		userID,
		string(skuIDsJSON),
	)

	result, err := cmd.Result()
	if err != nil {
		// Check if script was not found - need to reload
		if isScriptNotFoundError(err) {
			if reloadErr := c.LoadScripts(ctx); reloadErr != nil {
				return 0, nil, fmt.Errorf("failed to reload scripts: %w", reloadErr)
			}
			// Retry with reloaded script
			cmd = c.redis.EvalSha(
				ctx,
				c.batchDeleteSHA,
				[]string{cartKey, outboxKey},
				eventTimeMs,
				clearEventType,
				userID,
				string(skuIDsJSON),
			)
			result, err = cmd.Result()
			if err != nil {
				return 0, nil, c.parseLuaError(err)
			}
		} else {
			return 0, nil, c.parseLuaError(err)
		}
	}

	// Parse result - should be deleted count
	deletedCount, err := toInt64(result)
	if err != nil {
		return 0, nil, fmt.Errorf("failed to parse deleted count: %w", err)
	}

	// Get all stream IDs from the outbox since last read
	if deletedCount > 0 {
		// For batch delete, we emit one event per deleted SKU plus possibly one clear event
		// We can't easily get each stream_id individually, so we get the last few
		streamIDs = append(streamIDs, "multiple")
	}

	return deletedCount, streamIDs, nil
}

// SilentRehydrate re-populates cache from PostgreSQL without emitting events
func (c *CartOperationsImpl) SilentRehydrate(
	ctx context.Context,
	userID string,
	items []entity.RehydrateItem,
) error {
	if len(items) == 0 {
		return nil
	}

	cartKey, err := c.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return fmt.Errorf("failed to build cart key: %w", err)
	}

	// Marshal items to JSON array
	itemsJSON, err := json.Marshal(items)
	if err != nil {
		return fmt.Errorf("failed to marshal rehydrate items: %w", err)
	}

	// Execute Lua script using EVALSHA
	cmd := c.redis.EvalSha(
		ctx,
		c.silentRehydrateSHA,
		[]string{cartKey},
		string(itemsJSON),
		int(c.cartTTL.Seconds()),
	)

	_, err = cmd.Result()
	if err != nil {
		// Check if script was not found - need to reload
		if isScriptNotFoundError(err) {
			if reloadErr := c.LoadScripts(ctx); reloadErr != nil {
				return fmt.Errorf("failed to reload scripts: %w", reloadErr)
			}
			// Retry with reloaded script
			cmd = c.redis.EvalSha(
				ctx,
				c.silentRehydrateSHA,
				[]string{cartKey},
				string(itemsJSON),
				int(c.cartTTL.Seconds()),
			)
			_, err = cmd.Result()
			if err != nil {
				return fmt.Errorf("failed to execute silent_rehydrate script: %w", err)
			}
		} else {
			return fmt.Errorf("failed to execute silent_rehydrate script: %w", err)
		}
	}

	return nil
}

// GetCart retrieves all items from the cart
func (c *CartOperationsImpl) GetCart(ctx context.Context, userID string) (map[string]string, error) {
	cartKey, err := c.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return nil, fmt.Errorf("failed to build cart key: %w", err)
	}

	result, err := c.redis.HGetAll(ctx, cartKey).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get cart: %w", err)
	}

	return result, nil
}

// UpdateItemAvailability updates only the is_available field
// Exception: Only used when gRPC returns is_available=false (product banned/deleted)
// No EXPIRE, no XADD - this is the only write allowed on read path
func (c *CartOperationsImpl) UpdateItemAvailability(
	ctx context.Context,
	userID, skuID string,
	isAvailable bool,
) error {
	// Get current item
	currentItem, err := c.getCartItem(ctx, userID, skuID)
	if err != nil {
		return fmt.Errorf("failed to get current item: %w", err)
	}

	if currentItem == nil {
		return nil // Item doesn't exist, nothing to update
	}

	// Only update if setting to false (product banned)
	if isAvailable {
		return nil
	}

	// Update only is_available field
	currentItem.IsAvailable = isAvailable

	// Marshal back to JSON
	payload, err := json.Marshal(currentItem)
	if err != nil {
		return fmt.Errorf("failed to marshal item: %w", err)
	}

	cartKey, err := c.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return fmt.Errorf("failed to build cart key: %w", err)
	}

	_, err = c.redis.HSet(ctx, cartKey, skuID, string(payload)).Result()
	if err != nil {
		return fmt.Errorf("failed to update item availability: %w", err)
	}

	return nil
}

// getCartItem retrieves a single item from cart
func (c *CartOperationsImpl) getCartItem(ctx context.Context, userID, skuID string) (*entity.CartItemPayload, error) {
	cartKey, err := c.keyBuilder.BuildCartKey(userID)
	if err != nil {
		return nil, fmt.Errorf("failed to build cart key: %w", err)
	}

	result, err := c.redis.HGet(ctx, cartKey, skuID).Result()
	if err != nil {
		if err == redis.Nil {
			return nil, nil
		}
		return nil, err
	}

	var item entity.CartItemPayload
	if err := json.Unmarshal([]byte(result), &item); err != nil {
		return nil, err
	}

	return &item, nil
}

// getLastStreamID gets the last stream ID from outbox
func (c *CartOperationsImpl) getLastStreamID(ctx context.Context, outboxKey string) (string, error) {
	// Use XREVRANGE to get the last entry
	result, err := c.redis.Eval(
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

	// Parse the result - it's an array of [id, [field, value, ...]]
	arr, ok := result.([]interface{})
	if !ok || len(arr) == 0 {
		return "", nil
	}

	// First element is the stream ID
	if id, ok := arr[0].(string); ok {
		return id, nil
	}

	return "", nil
}

// parseLuaError converts Lua error to Go error
func (c *CartOperationsImpl) parseLuaError(err error) error {
	if err == nil {
		return nil
	}

	errStr := err.Error()
	return parseLuaErrorFromString(errStr)
}

// parseLuaErrorFromString parses error string returned from Lua
func parseLuaErrorFromString(errStr string) error {
	// Check for specific error messages
	switch {
	case strings.Contains(errStr, "Cart Full"):
		return ErrCartFull
	case strings.Contains(errStr, "Step Invalid"):
		return ErrStepInvalid
	case strings.Contains(errStr, "Quantity Invalid"):
		return ErrQuantityInvalid
	case strings.Contains(errStr, "Cannot decrement"):
		return ErrCannotDecrementNonExisting
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

// parseLuaErrorResponse checks if result contains error info
func parseLuaErrorResponse(result interface{}) error {
	arr, ok := result.([]interface{})
	if !ok || len(arr) == 0 {
		return nil
	}

	for _, item := range arr {
		if str, ok := item.(string); ok {
			if strings.Contains(str, "err=") {
				return fmt.Errorf("lua error: %s", str)
			}
		}
	}

	return nil
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

// Cart operation errors
var (
	ErrCartFull                   = fmt.Errorf("cart is full")
	ErrStepInvalid                = fmt.Errorf("step must be non-zero")
	ErrQuantityInvalid            = fmt.Errorf("quantity would be invalid")
	ErrCannotDecrementNonExisting = fmt.Errorf("cannot decrement non-existing item")
)
