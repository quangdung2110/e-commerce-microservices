// Package jwks keeps the Auth Service public keys for signature verification.
//
// Policy (docs/gateway/runtime-contract.md section 6):
//   - Refresh on a fixed interval, single-flight, bounded by a fetch timeout.
//   - An unknown kid triggers a controlled refresh, at most once per
//     MinRefreshInterval, so forged kids cannot hammer the Auth Service.
//   - A key that disappears from the JWKS stays usable for RetiredKeyGrace, long
//     enough for tokens it signed to expire.
//   - When the JWKS cannot be fetched, cached keys keep verifying until MaxStale
//     after the last successful fetch; with no usable key the source fails closed.
package jwks

import (
	"context"
	"crypto"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"sort"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"

	"github.com/ecommerce/gateway-auth/internal/domain"
)

// Options configures a Cache.
type Options struct {
	URL                string
	RefreshInterval    time.Duration
	FetchTimeout       time.Duration
	MinRefreshInterval time.Duration
	RetiredKeyGrace    time.Duration
	MaxStale           time.Duration
	MaxKeys            int
	HTTPClient         *http.Client
	Now                func() time.Time
}

type entry struct {
	key crypto.PublicKey
	// retiredAt is set when a successful fetch no longer lists the key.
	retiredAt time.Time
}

// Cache is a port.KeySource backed by the Auth Service JWKS endpoint.
type Cache struct {
	opts   Options
	client *http.Client
	now    func() time.Time
	flight singleflight.Group

	mu          sync.RWMutex
	keys        map[string]*entry
	lastSuccess time.Time
	lastAttempt time.Time
	lastErr     error
	// forceRefresh is set by a key-rotation signal: the next lookup refreshes
	// even inside RefreshInterval.
	forceRefresh bool
}

// New builds an empty cache; the first lookup fetches the JWKS.
func New(opts Options) *Cache {
	client := opts.HTTPClient
	if client == nil {
		client = &http.Client{}
	}
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	return &Cache{opts: opts, client: client, now: now, keys: map[string]*entry{}}
}

// PublicKey implements port.KeySource.
func (c *Cache) PublicKey(ctx context.Context, kid string) (crypto.PublicKey, error) {
	now := c.now()

	c.mu.RLock()
	due := c.forceRefresh || now.Sub(c.lastSuccess) >= c.opts.RefreshInterval
	_, known := c.keys[kid]
	throttled := now.Sub(c.lastAttempt) < c.opts.MinRefreshInterval
	c.mu.RUnlock()

	// Scheduled refresh, or an unknown kid (possibly a freshly rotated key).
	// Both are rate limited by MinRefreshInterval except the very first fetch.
	if (due || !known) && (!throttled || c.neverFetched()) {
		// The error is kept in lastErr; a cached key may still answer below.
		_ = c.Refresh(ctx)
	}

	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.lastSuccess.IsZero() || now.Sub(c.lastSuccess) > c.opts.MaxStale {
		return nil, fmt.Errorf("%w: no usable JWKS (last error: %v)", domain.ErrDependencyUnavailable, c.lastErr)
	}
	if e, ok := c.keys[kid]; ok && c.usable(e, now) {
		return e.key, nil
	}
	// The kid is absent. If the latest refresh attempt failed we cannot prove it
	// is unpublished, so this is a dependency problem rather than a bad token.
	if c.lastErr != nil && c.lastAttempt.After(c.lastSuccess) {
		return nil, fmt.Errorf("%w: JWKS refresh failed: %v", domain.ErrDependencyUnavailable, c.lastErr)
	}
	return nil, fmt.Errorf("%w: kid %q", domain.ErrUnknownKey, kid)
}

func (c *Cache) neverFetched() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.lastSuccess.IsZero()
}

func (c *Cache) usable(e *entry, now time.Time) bool {
	return e.retiredAt.IsZero() || now.Sub(e.retiredAt) < c.opts.RetiredKeyGrace
}

// Ready reports whether at least one key can verify right now, fetching once if
// the cache is empty. Used by the readiness probe.
func (c *Cache) Ready(ctx context.Context) bool {
	if c.neverFetched() {
		if err := c.Refresh(ctx); err != nil {
			return false
		}
	}
	now := c.now()
	c.mu.RLock()
	defer c.mu.RUnlock()
	if now.Sub(c.lastSuccess) > c.opts.MaxStale {
		return false
	}
	for _, e := range c.keys {
		if c.usable(e, now) {
			return true
		}
	}
	return false
}

// Refresh fetches the JWKS once; concurrent callers share the same fetch.
func (c *Cache) Refresh(ctx context.Context) error {
	_, err, _ := c.flight.Do("refresh", func() (any, error) {
		// Detach from the caller's cancellation so one aborted request does not
		// fail the fetch every other waiting request depends on.
		fetchCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), c.opts.FetchTimeout)
		defer cancel()

		keys, err := c.fetch(fetchCtx)
		now := c.now()

		c.mu.Lock()
		defer c.mu.Unlock()
		c.lastAttempt = now
		c.lastErr = err
		if err != nil {
			return nil, err
		}
		c.merge(keys, now)
		c.lastSuccess = now
		c.forceRefresh = false
		return nil, nil
	})
	return err
}

// merge applies a successful fetch: listed keys are active, unlisted keys start
// their grace period, expired and excess retired keys are dropped.
func (c *Cache) merge(fetched map[string]crypto.PublicKey, now time.Time) {
	for kid, key := range fetched {
		c.keys[kid] = &entry{key: key}
	}
	for kid, e := range c.keys {
		if _, listed := fetched[kid]; listed {
			continue
		}
		if e.retiredAt.IsZero() {
			e.retiredAt = now
		}
		if now.Sub(e.retiredAt) >= c.opts.RetiredKeyGrace {
			delete(c.keys, kid)
		}
	}
	c.enforceLimit()
}

// enforceLimit drops the oldest retired keys first when over MaxKeys.
func (c *Cache) enforceLimit() {
	if len(c.keys) <= c.opts.MaxKeys {
		return
	}
	type retired struct {
		kid string
		at  time.Time
	}
	var candidates []retired
	for kid, e := range c.keys {
		if !e.retiredAt.IsZero() {
			candidates = append(candidates, retired{kid, e.retiredAt})
		}
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].at.Before(candidates[j].at) })
	for _, candidate := range candidates {
		if len(c.keys) <= c.opts.MaxKeys {
			return
		}
		delete(c.keys, candidate.kid)
	}
}

// Invalidate applies a signal from the auth:jwks:update channel.
//
// An empty kid clears the whole cache (the contract's meaning); key_retirement
// removes one key at once; key_rotation keeps the keys and forces the next
// lookup to refresh so the new key is picked up.
func (c *Cache) Invalidate(eventType, kid string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	switch {
	case kid == "" || eventType == "all":
		c.keys = map[string]*entry{}
		c.lastSuccess = time.Time{}
	case eventType == "key_retirement":
		delete(c.keys, kid)
	case eventType == "key_rotation":
		c.forceRefresh = true
		c.lastAttempt = time.Time{}
	}
}

type jwkDocument struct {
	Keys []struct {
		Kty string `json:"kty"`
		Kid string `json:"kid"`
		Use string `json:"use"`
		Alg string `json:"alg"`
		N   string `json:"n"`
		E   string `json:"e"`
	} `json:"keys"`
}

// maxDocumentBytes bounds the JWKS body; a key set is a few kilobytes.
const maxDocumentBytes = 1 << 20

func (c *Cache) fetch(ctx context.Context) (map[string]crypto.PublicKey, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.opts.URL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch JWKS: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fetch JWKS: status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxDocumentBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read JWKS: %w", err)
	}
	if len(body) > maxDocumentBytes {
		return nil, errors.New("JWKS document too large")
	}
	return parseDocument(body)
}

// parseDocument keeps RSA signing keys only. A set with no usable key is an
// error: accepting it would retire every cached key.
func parseDocument(body []byte) (map[string]crypto.PublicKey, error) {
	var doc jwkDocument
	if err := json.Unmarshal(body, &doc); err != nil {
		return nil, fmt.Errorf("decode JWKS: %w", err)
	}
	keys := map[string]crypto.PublicKey{}
	for _, k := range doc.Keys {
		if k.Kid == "" || k.Kty != "RSA" || (k.Use != "" && k.Use != "sig") {
			continue
		}
		if k.Alg != "" && k.Alg != "RS256" {
			continue
		}
		key, err := rsaKey(k.N, k.E)
		if err != nil {
			continue
		}
		keys[k.Kid] = key
	}
	if len(keys) == 0 {
		return nil, errors.New("JWKS contains no usable RSA signing key")
	}
	return keys, nil
}

func rsaKey(n, e string) (*rsa.PublicKey, error) {
	nBytes, err := base64.RawURLEncoding.DecodeString(n)
	if err != nil {
		return nil, err
	}
	eBytes, err := base64.RawURLEncoding.DecodeString(e)
	if err != nil {
		return nil, err
	}
	exponent := new(big.Int).SetBytes(eBytes)
	if !exponent.IsInt64() || exponent.Int64() < 3 || exponent.Int64() > 1<<31-1 {
		return nil, errors.New("invalid RSA exponent")
	}
	modulus := new(big.Int).SetBytes(nBytes)
	if modulus.BitLen() < 2048 {
		return nil, errors.New("RSA key shorter than 2048 bits")
	}
	return &rsa.PublicKey{N: modulus, E: int(exponent.Int64())}, nil
}
