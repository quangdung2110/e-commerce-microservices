package cartredis

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
)

// Options are the lifecycle durations the scripts need as arguments.
type Options struct {
	// AbandonedAfter is the inactivity window before a cart is marked
	// abandoned (CART-BR-009, default 30 days).
	AbandonedAfter time.Duration
	// ActiveTTL is the Redis TTL of an active cart. It intentionally exceeds
	// AbandonedAfter so the marker loop has a buffer to run in.
	ActiveTTL time.Duration
	// AbandonedTTL is the retention window after the abandoned transition.
	AbandonedTTL time.Duration
	// IdempotencyTTL is how long an HTTP idempotency record stays replayable.
	IdempotencyTTL time.Duration
}

// Store is the Redis-backed implementation of port.CartStore.
type Store struct {
	client redis.UniversalClient
	keys   Keys
	opts   Options
	now    func() time.Time
}

// NewStore builds a cart store over an existing Redis client.
func NewStore(client redis.UniversalClient, opts Options) *Store {
	return &Store{client: client, opts: opts, now: time.Now}
}

var _ port.CartStore = (*Store)(nil)

// LoadItems reads every SKU line of a cart.
//
// A missing hash is an empty cart, not an error: Cart has no cold store to
// fall back to and never rehydrates (WF-CART-002).
func (s *Store) LoadItems(ctx context.Context, id domain.Identity) ([]domain.Item, error) {
	raw, err := s.client.HGetAll(ctx, s.keys.Items(id)).Result()
	if err != nil {
		return nil, storeUnavailable("read cart items", err)
	}

	items := make([]domain.Item, 0, len(raw))
	for skuID, value := range raw {
		var stored domain.StoredItem
		if err := json.Unmarshal([]byte(value), &stored); err != nil {
			// One corrupt field must not take down the whole cart read. Skip
			// it; that SKU simply does not render.
			continue
		}
		items = append(items, domain.Item{SkuID: skuID, StoredItem: stored})
	}
	return items, nil
}

// Upsert applies one delta to one SKU inside upsert_item.lua.
func (s *Store) Upsert(ctx context.Context, cmd port.UpsertCommand) (port.UpsertResult, error) {
	snapshotJSON := ""
	if cmd.Snapshot != nil {
		encoded, err := json.Marshal(cmd.Snapshot)
		if err != nil {
			return port.UpsertResult{}, fmt.Errorf("encode product snapshot: %w", err)
		}
		snapshotJSON = string(encoded)
	}

	keys := []string{
		s.keys.Items(cmd.Identity),
		s.keys.Meta(cmd.Identity),
		s.keys.Index(cmd.Identity.Bucket),
		s.keys.Idempotency(cmd.Identity, "adjust", cmd.IdempotencyKey),
	}
	args := []any{
		cmd.SkuID,
		cmd.Delta,
		snapshotJSON,
		domain.MaxQuantityPerItem,
		domain.MaxSKUsPerCart,
		s.nowMillis(),
		cmd.Identity.UserID,
		cmd.Identity.CartID,
		cmd.Identity.UserToken,
		int64(s.opts.ActiveTTL.Seconds()),
		s.opts.AbandonedAfter.Milliseconds(),
		cmd.Fingerprint,
		int64(s.opts.IdempotencyTTL.Seconds()),
	}

	raw, err := s.runScript(ctx, upsertItemScript, keys, args, "upsert item")
	if err != nil {
		return port.UpsertResult{}, err
	}

	var result port.UpsertResult
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		return port.UpsertResult{}, fmt.Errorf("decode upsert result %q: %w", raw, err)
	}
	return result, nil
}

// Remove deletes a batch of SKUs inside remove_items.lua.
func (s *Store) Remove(ctx context.Context, cmd port.RemoveCommand) (port.RemoveResult, error) {
	skuIDsJSON, err := json.Marshal(cmd.SkuIDs)
	if err != nil {
		return port.RemoveResult{}, fmt.Errorf("encode sku ids: %w", err)
	}

	touch := "0"
	if cmd.TouchActivity {
		touch = "1"
	}

	keys := []string{
		s.keys.Items(cmd.Identity),
		s.keys.Meta(cmd.Identity),
		s.keys.Index(cmd.Identity.Bucket),
	}
	idempotencyTTL := ""
	if cmd.IdempotencyKey != "" {
		keys = append(keys, s.keys.Idempotency(cmd.Identity, "delete", cmd.IdempotencyKey))
		idempotencyTTL = strconv.FormatInt(int64(s.opts.IdempotencyTTL.Seconds()), 10)
	}
	// The ORDER_PAID path passes three keys rather than an empty fourth one.
	// An empty string hashes to its own Redis Cluster slot, so declaring it as
	// a key would make every invocation CROSSSLOT. The script already guards
	// KEYS[4] with a nil check.

	args := []any{
		string(skuIDsJSON),
		cmd.Identity.UserToken,
		s.nowMillis(),
		touch,
		int64(s.opts.ActiveTTL.Seconds()),
		s.opts.AbandonedAfter.Milliseconds(),
		cmd.Fingerprint,
		idempotencyTTL,
	}

	raw, err := s.runScript(ctx, removeItemsScript, keys, args, "remove items")
	if err != nil {
		return port.RemoveResult{}, err
	}

	var result port.RemoveResult
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		return port.RemoveResult{}, fmt.Errorf("decode remove result %q: %w", raw, err)
	}
	return result, nil
}

// MarkAbandoned transitions one cart to ABANDONED and writes its outbox entry.
//
// The state change and the XADD share a single Lua invocation, so a cart can
// never end up abandoned without an event, nor carry an event for a transition
// that did not happen.
func (s *Store) MarkAbandoned(ctx context.Context, cmd port.MarkAbandonedCommand) (port.MarkAbandonedResult, error) {
	keys := []string{
		s.keys.Items(cmd.Identity),
		s.keys.Meta(cmd.Identity),
		s.keys.Index(cmd.Identity.Bucket),
		s.keys.Outbox(cmd.Identity.Bucket),
	}
	args := []any{
		cmd.Identity.UserToken,
		cmd.ExpectedDeadline,
		s.nowMillis(),
		int64(s.opts.AbandonedTTL.Seconds()),
	}

	raw, err := s.runScript(ctx, markAbandonedScript, keys, args, "mark abandoned")
	if err != nil {
		return port.MarkAbandonedResult{}, err
	}

	var result port.MarkAbandonedResult
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		return port.MarkAbandonedResult{}, fmt.Errorf("decode mark abandoned result %q: %w", raw, err)
	}
	return result, nil
}

// PurgeCart deletes the cart of a deleted account inside purge_cart.lua.
//
// It removes the items, the meta hash and the scheduler member together, and
// is idempotent: a user without a cart reports Purged = false. Pending outbox
// entries are left to the relay.
func (s *Store) PurgeCart(ctx context.Context, id domain.Identity) (port.PurgeResult, error) {
	keys := []string{
		s.keys.Items(id),
		s.keys.Meta(id),
		s.keys.Index(id.Bucket),
	}

	raw, err := s.runScript(ctx, purgeCartScript, keys, []any{id.UserToken}, "purge cart")
	if err != nil {
		return port.PurgeResult{}, err
	}

	var result port.PurgeResult
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		return port.PurgeResult{}, fmt.Errorf("decode purge result %q: %w", raw, err)
	}
	return result, nil
}

// DueCarts lists overdue members of one bucket's abandonment index.
func (s *Store) DueCarts(ctx context.Context, bucket int, now time.Time, limit int) ([]port.DueCart, error) {
	entries, err := s.client.ZRangeArgsWithScores(ctx, redis.ZRangeArgs{
		Key:     s.keys.Index(bucket),
		Start:   "0",
		Stop:    strconv.FormatInt(now.UnixMilli(), 10),
		ByScore: true,
		Offset:  0,
		Count:   int64(limit),
	}).Result()
	if err != nil {
		return nil, storeUnavailable("scan abandonment index", err)
	}

	due := make([]port.DueCart, 0, len(entries))
	for _, entry := range entries {
		token, ok := entry.Member.(string)
		if !ok {
			continue
		}
		due = append(due, port.DueCart{UserToken: token, Deadline: int64(entry.Score)})
	}
	return due, nil
}

// DropIndexMember removes a stale member from a bucket's abandonment index.
func (s *Store) DropIndexMember(ctx context.Context, bucket int, userToken string) error {
	if err := s.client.ZRem(ctx, s.keys.Index(bucket), userToken).Err(); err != nil {
		return storeUnavailable("drop abandonment index member", err)
	}
	return nil
}

// Ping reports whether Redis is reachable.
func (s *Store) Ping(ctx context.Context) error {
	if err := s.client.Ping(ctx).Err(); err != nil {
		return storeUnavailable("ping redis", err)
	}
	return nil
}

// runScript executes a script and separates the two kinds of failure that
// matter: a domain code the script deliberately returned, and everything else,
// which is an infrastructure fault and must never be shown as a business
// outcome.
func (s *Store) runScript(ctx context.Context, script *redis.Script, keys []string, args []any, op string) (string, error) {
	raw, err := script.Run(ctx, s.client, keys, args...).Text()
	if err == nil {
		return raw, nil
	}

	if domainErr, ok := domain.ScriptError(scriptErrorMessage(err)); ok {
		return "", domainErr
	}
	return "", storeUnavailable(op, err)
}

// scriptErrorMessage normalises the message go-redis surfaces for a Lua
// `return {err = 'CODE'}`. Redis may prefix the reply with its own error class
// and a script location suffix, so keep only the leading upper-case token.
func scriptErrorMessage(err error) string {
	msg := strings.TrimSpace(err.Error())
	if idx := strings.IndexAny(msg, " \t\r\n"); idx >= 0 {
		msg = msg[:idx]
	}
	return msg
}

// storeUnavailable wraps an infrastructure failure as a retryable domain error
// so the HTTP layer can answer 503 without inspecting driver internals.
// Context cancellation is passed through unchanged: it is the caller giving
// up, not Redis failing.
func storeUnavailable(op string, err error) error {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return fmt.Errorf("%s: %w", op, err)
	}
	return fmt.Errorf("%s: %w (%v)", op, &domain.Error{
		Code:      domain.CodeCartStoreUnavailable,
		Detail:    "cart store is unavailable",
		Retryable: true,
	}, err)
}

func (s *Store) nowMillis() int64 {
	return s.now().UnixMilli()
}
