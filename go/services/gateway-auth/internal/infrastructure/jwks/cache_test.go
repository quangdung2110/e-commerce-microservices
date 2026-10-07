package jwks

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/gateway-auth/internal/domain"
)

// jwksServer serves a mutable key set and can be switched to fail.
type jwksServer struct {
	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey
	failing bool
	hits    atomic.Int32
	server  *httptest.Server
}

func newJWKSServer(t *testing.T) *jwksServer {
	s := &jwksServer{keys: map[string]*rsa.PublicKey{}}
	s.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		s.hits.Add(1)
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.failing {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		doc := map[string]any{"keys": []map[string]string{}}
		for kid, key := range s.keys {
			doc["keys"] = append(doc["keys"].([]map[string]string), map[string]string{
				"kty": "RSA", "use": "sig", "alg": "RS256", "kid": kid,
				"n": base64.RawURLEncoding.EncodeToString(key.N.Bytes()),
				"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes()),
			})
		}
		_ = json.NewEncoder(w).Encode(doc)
	}))
	t.Cleanup(s.server.Close)
	return s
}

func (s *jwksServer) set(keys map[string]*rsa.PublicKey, failing bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.keys = keys
	s.failing = failing
}

func rsaPublic(t *testing.T) *rsa.PublicKey {
	t.Helper()
	private, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	return &private.PublicKey
}

type clock struct{ now time.Time }

func (c *clock) Now() time.Time          { return c.now }
func (c *clock) advance(d time.Duration) { c.now = c.now.Add(d) }

func newCache(url string, clk *clock) *Cache {
	return New(Options{
		URL:                url,
		RefreshInterval:    10 * time.Minute,
		FetchTimeout:       time.Second,
		MinRefreshInterval: 30 * time.Second,
		RetiredKeyGrace:    20 * time.Minute,
		MaxStale:           24 * time.Hour,
		MaxKeys:            4,
		Now:                clk.Now,
	})
}

func TestPublicKey_FetchesOnceAndServesFromCache(t *testing.T) {
	srv := newJWKSServer(t)
	key := rsaPublic(t)
	srv.set(map[string]*rsa.PublicKey{"k1": key}, false)
	clk := &clock{now: time.Now()}
	cache := newCache(srv.server.URL, clk)

	for i := 0; i < 5; i++ {
		got, err := cache.PublicKey(context.Background(), "k1")
		require.NoError(t, err)
		assert.Equal(t, key.N, got.(*rsa.PublicKey).N)
	}
	assert.EqualValues(t, 1, srv.hits.Load())
}

func TestPublicKey_UnknownKidRefreshIsThrottled(t *testing.T) {
	srv := newJWKSServer(t)
	srv.set(map[string]*rsa.PublicKey{"k1": rsaPublic(t)}, false)
	clk := &clock{now: time.Now()}
	cache := newCache(srv.server.URL, clk)
	_, err := cache.PublicKey(context.Background(), "k1")
	require.NoError(t, err)

	// A stream of forged kids must not hammer the Auth Service.
	for i := 0; i < 10; i++ {
		_, err := cache.PublicKey(context.Background(), "forged")
		assert.ErrorIs(t, err, domain.ErrUnknownKey)
	}
	assert.EqualValues(t, 1, srv.hits.Load())

	// After the throttle window a newly published key is picked up.
	srv.set(map[string]*rsa.PublicKey{"k1": rsaPublic(t), "k2": rsaPublic(t)}, false)
	clk.advance(31 * time.Second)
	_, err = cache.PublicKey(context.Background(), "k2")
	assert.NoError(t, err)
}

func TestPublicKey_OutageKeepsVerifyingFromCache(t *testing.T) {
	srv := newJWKSServer(t)
	srv.set(map[string]*rsa.PublicKey{"k1": rsaPublic(t)}, false)
	clk := &clock{now: time.Now()}
	cache := newCache(srv.server.URL, clk)
	_, err := cache.PublicKey(context.Background(), "k1")
	require.NoError(t, err)

	srv.set(nil, true)
	clk.advance(11 * time.Minute) // refresh due, fetch fails
	_, err = cache.PublicKey(context.Background(), "k1")
	assert.NoError(t, err, "cached key must keep verifying while Auth is down")

	// Unknown kid during an outage cannot be proven unpublished.
	clk.advance(31 * time.Second)
	_, err = cache.PublicKey(context.Background(), "k-new")
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)

	// Past MaxStale the cache fails closed.
	clk.advance(25 * time.Hour)
	_, err = cache.PublicKey(context.Background(), "k1")
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)
}

func TestPublicKey_NeverFetchedFailsClosed(t *testing.T) {
	srv := newJWKSServer(t)
	srv.set(nil, true)
	cache := newCache(srv.server.URL, &clock{now: time.Now()})

	_, err := cache.PublicKey(context.Background(), "k1")
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)
}

func TestPublicKey_RetiredKeyHonoursGracePeriod(t *testing.T) {
	srv := newJWKSServer(t)
	srv.set(map[string]*rsa.PublicKey{"old": rsaPublic(t)}, false)
	clk := &clock{now: time.Now()}
	cache := newCache(srv.server.URL, clk)
	_, err := cache.PublicKey(context.Background(), "old")
	require.NoError(t, err)

	// Rotation: "old" leaves the JWKS.
	srv.set(map[string]*rsa.PublicKey{"new": rsaPublic(t)}, false)
	clk.advance(11 * time.Minute)
	_, err = cache.PublicKey(context.Background(), "old")
	assert.NoError(t, err, "tokens signed by a just-retired key are still valid")

	clk.advance(21 * time.Minute)
	_, err = cache.PublicKey(context.Background(), "old")
	assert.ErrorIs(t, err, domain.ErrUnknownKey)
}

func TestInvalidate(t *testing.T) {
	srv := newJWKSServer(t)
	srv.set(map[string]*rsa.PublicKey{"k1": rsaPublic(t), "k2": rsaPublic(t)}, false)
	clk := &clock{now: time.Now()}
	cache := newCache(srv.server.URL, clk)
	_, err := cache.PublicKey(context.Background(), "k1")
	require.NoError(t, err)

	cache.Invalidate("key_retirement", "k1")
	srv.set(map[string]*rsa.PublicKey{"k2": rsaPublic(t)}, false)
	clk.advance(31 * time.Second)
	_, err = cache.PublicKey(context.Background(), "k1")
	assert.ErrorIs(t, err, domain.ErrUnknownKey)

	// Empty kid clears everything; with Auth down that must fail closed.
	cache.Invalidate("key_rotation", "")
	srv.set(nil, true)
	_, err = cache.PublicKey(context.Background(), "k2")
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)
}

func TestParseDocument_RejectsUnusableKeys(t *testing.T) {
	_, err := parseDocument([]byte(`{"keys":[{"kty":"EC","kid":"ec"},{"kty":"RSA","kid":"short","n":"AQAB","e":"AQAB"}]}`))
	assert.Error(t, err)
}
