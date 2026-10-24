// Package service holds the application service: the layer that decides when
// to call Product and when to call a Lua script, and nothing else. All cart
// state changes happen inside the scripts.
package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
)

// Config holds the application-level knobs.
type Config struct {
	// ProductValidationTTL is how long a stored product snapshot may be reused
	// for a positive delta before Cart refreshes it (CART-BR-004).
	ProductValidationTTL time.Duration
}

// CartService implements the cart use cases.
type CartService struct {
	store   port.CartStore
	catalog port.ProductCatalog
	cfg     Config
	now     func() time.Time
}

// New builds the application service.
func New(store port.CartStore, catalog port.ProductCatalog, cfg Config) *CartService {
	return &CartService{store: store, catalog: catalog, cfg: cfg, now: time.Now}
}

// GetCart reads a cart and enriches it with live Product data (WF-CART-002).
//
// A Redis miss is an empty cart. A Product failure degrades the response
// instead of failing it: the cart page must not die with Product Service.
// This path never extends a TTL, touches the scheduler ZSET or emits an event.
func (s *CartService) GetCart(ctx context.Context, identity domain.Identity) (domain.CartView, error) {
	items, err := s.store.LoadItems(ctx, identity)
	if err != nil {
		return domain.CartView{}, err
	}
	if len(items) == 0 {
		return domain.CartView{Items: []domain.ViewItem{}, Freshness: domain.FreshnessCurrent}, nil
	}

	// A stable order keeps the response deterministic; Redis hash iteration is
	// not ordered.
	sort.Slice(items, func(i, j int) bool { return items[i].SkuID < items[j].SkuID })

	skuIDs := make([]string, 0, len(items))
	for _, item := range items {
		skuIDs = append(skuIDs, item.SkuID)
	}

	snapshots, err := s.catalog.GetBatchSkus(ctx, skuIDs)
	if err != nil {
		return degradedView(items), nil
	}

	view := domain.CartView{Items: make([]domain.ViewItem, 0, len(items)), Freshness: domain.FreshnessCurrent}
	for _, item := range items {
		line := domain.ViewItem{Item: item, Available: item.IsAvailable}
		if snapshot, ok := snapshots[item.SkuID]; ok {
			line.Available = snapshot.IsAvailable
			if snapshot.IsAvailable {
				// Refresh display text in the response only; never mutate the Redis snapshot on GET.
				line.VariantName = snapshot.VariantName
			}
			if snapshot.IsAvailable || snapshot.Price.Currency != "" {
				price := snapshot.Price
				line.CurrentPrice = &price
			}
		}
		view.Items = append(view.Items, line)
	}
	return view, nil
}

// CheckoutSnapshot returns the identifiers and quantities Checkout needs (contracts/http/cart/v1,
// getCheckoutCartSnapshot): the same Redis source of truth as GetCart, ordered by SKU, and nothing
// else. It never calls Product, never extends a TTL, never touches the scheduler ZSET and emits no
// event, so Checkout reading the cart cannot postpone abandonment. A Redis miss is an empty cart.
func (s *CartService) CheckoutSnapshot(ctx context.Context, identity domain.Identity) ([]domain.Item, error) {
	items, err := s.store.LoadItems(ctx, identity)
	if err != nil {
		return nil, err
	}
	sort.Slice(items, func(i, j int) bool { return items[i].SkuID < items[j].SkuID })
	return items, nil
}

// AdjustItem applies a delta to one SKU (WF-CART-001).
//
// The script decides whether a fresh product snapshot is needed, because only
// it can read the stored validation expiry atomically with the write. Cart
// therefore attempts the write first and refreshes Product only when the
// script asks for it. That costs one extra round trip on the rare stale path
// and saves a Product call on every repeated click.
func (s *CartService) AdjustItem(
	ctx context.Context,
	identity domain.Identity,
	skuID string,
	delta int,
	idempotencyKey string,
) (port.UpsertResult, error) {
	cmd := port.UpsertCommand{
		Identity:       identity,
		SkuID:          skuID,
		Delta:          delta,
		Fingerprint:    adjustFingerprint(identity, skuID, delta),
		IdempotencyKey: idempotencyKey,
	}

	result, err := s.store.Upsert(ctx, cmd)
	if err == nil {
		return result, nil
	}
	if !hasCode(err, domain.CodeProductSnapshotRequired) {
		return port.UpsertResult{}, err
	}

	// PRODUCT_SNAPSHOT_REQUIRED is an internal signal, never a client-visible
	// outcome: the SKU is new, or its validation expired on a positive delta.
	snapshot, err := s.catalog.GetSku(ctx, skuID)
	if err != nil {
		return port.UpsertResult{}, err
	}

	validatedAt := domain.MillisOf(s.now())
	expiresAt := domain.MillisOf(s.now().Add(s.cfg.ProductValidationTTL))
	stored := snapshot.ToStoredItem(validatedAt, expiresAt)
	cmd.Snapshot = &stored

	return s.store.Upsert(ctx, cmd)
}

// DeleteItems removes a batch of SKUs on behalf of the user (WF-CART-003).
//
// Deleting a SKU that is not in the cart is a no-op, so the whole operation is
// naturally idempotent (CART-BR-006). No Kafka event is emitted: no consumer
// has been identified for one.
func (s *CartService) DeleteItems(
	ctx context.Context,
	identity domain.Identity,
	skuIDs []string,
	idempotencyKey string,
) (port.RemoveResult, error) {
	return s.store.Remove(ctx, port.RemoveCommand{
		Identity:       identity,
		SkuIDs:         skuIDs,
		TouchActivity:  true,
		Fingerprint:    deleteFingerprint(identity, skuIDs),
		IdempotencyKey: idempotencyKey,
	})
}

// RemovePurchased clears the SKUs an order paid for (WF-CART-004).
//
// It does not touch activity: paying for part of a cart is not the user
// browsing, so it must not push the abandonment deadline out. It also carries
// no idempotency record, because HDEL per SKU is already idempotent and this
// path emits no event.
func (s *CartService) RemovePurchased(
	ctx context.Context,
	identity domain.Identity,
	skuIDs []string,
) (port.RemoveResult, error) {
	return s.store.Remove(ctx, port.RemoveCommand{
		Identity:      identity,
		SkuIDs:        skuIDs,
		TouchActivity: false,
	})
}

// PurgeUser deletes the whole cart of a deleted account (WF-CART-008).
//
// The script is idempotent, so a redelivered USER_DELETED simply reports that
// nothing was left to purge. No Kafka event is emitted and no idempotency
// record is needed.
func (s *CartService) PurgeUser(ctx context.Context, identity domain.Identity) (port.PurgeResult, error) {
	return s.store.PurgeCart(ctx, identity)
}

// degradedView renders a cart from stored snapshots alone.
//
// current_price is deliberately absent rather than guessed: showing a stale
// price as if it were live is worse than showing none.
func degradedView(items []domain.Item) domain.CartView {
	view := domain.CartView{Items: make([]domain.ViewItem, 0, len(items)), Freshness: domain.FreshnessDegraded}
	for _, item := range items {
		view.Items = append(view.Items, domain.ViewItem{Item: item, Available: item.IsAvailable})
	}
	return view
}

// adjustFingerprint is the canonical hash of an adjust request. A replayed
// idempotency key whose fingerprint differs is a conflict, not a replay.
func adjustFingerprint(identity domain.Identity, skuID string, delta int) string {
	return fingerprint("adjust", identity.CartID, skuID, strconv.Itoa(delta))
}

// deleteFingerprint hashes a delete request. The SKU list is sorted first so
// that the same set in a different order replays instead of conflicting.
func deleteFingerprint(identity domain.Identity, skuIDs []string) string {
	sorted := make([]string, len(skuIDs))
	copy(sorted, skuIDs)
	sort.Strings(sorted)
	return fingerprint("delete", identity.CartID, strings.Join(sorted, ","))
}

func fingerprint(parts ...string) string {
	hash := sha256.New()
	for _, part := range parts {
		// The length prefix keeps the encoding unambiguous, so that no two
		// distinct requests can hash the same bytes.
		fmt.Fprintf(hash, "%d:%s\n", len(part), part)
	}
	return hex.EncodeToString(hash.Sum(nil))
}

func hasCode(err error, code domain.Code) bool {
	var domainErr *domain.Error
	return errors.As(err, &domainErr) && domainErr.Code == code
}
