// Package jwt_auth_plugin provides JWT authentication middleware for Traefik v3.x
// This plugin validates JWT tokens using JWKS, checks Redis denylist, and enriches headers.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/lestrrat-go/jwx/v2/jwa"
	"github.com/lestrrat-go/jwx/v2/jwk"
	"github.com/lestrrat-go/jwx/v2/jwt"
	"github.com/redis/go-redis/v9"
)

// Plugin configuration passed from Traefik
type PluginConfig struct {
	AuthServiceURL         string   `json:"authServiceURL"`
	JWKSPath               string   `json:"jwksPath"`
	RedisAddr              string   `json:"redisAddr"`
	RedisPassword          string   `json:"redisPassword"`
	RedisDB                int      `json:"redisDB"`
	JWKSCacheTTLMinutes    int      `json:"jwksCacheTTLMinutes"`
	DenylistKeyPrefix      string   `json:"denylistKeyPrefix"`
	RevokedBeforePrefix    string   `json:"revokedBeforePrefix"`
	JWKSInvalidateChannel  string   `json:"jwksInvalidateChannel"`
	PublicPaths            []string `json:"publicPaths"`
	UserIdHeader           string   `json:"userIdHeader"`
	UserRolesHeader        string   `json:"userRolesHeader"`
	UserEmailHeader        string   `json:"userEmailHeader"`
	UserDeviceHeader       string   `json:"userDeviceHeader"`
	UserSessionHeader      string   `json:"userSessionHeader"`
}

// JWT claims extracted from token
type Claims struct {
	Subject   string
	Email     string
	Roles     []string
	DeviceID  string
	SessionID string
	JTI       string
	IAT       int64
	EXP       int64
}

// JWTAuthMiddleware is the main plugin struct
type JWTAuthMiddleware struct {
	next           http.Handler
	config         *PluginConfig
	cache          *JWKSCache
	redis          *redis.Client
	httpClient     *http.Client
	publicPathsMap map[string]bool
	logger         *log.Logger
}

// JWKS invalidation event
type JWKSInvalidationEvent struct {
	Type      string `json:"type"`
	Kid       string `json:"kid,omitempty"`
	Timestamp string `json:"timestamp"`
}

// JWKS cache for storing public keys
type JWKSCache struct {
	keys       map[string]jwk.Key
	fetchedAt  time.Time
	ttl        time.Duration
	mu         sync.RWMutex
	httpClient *http.Client
	authURL    string
	logger     *log.Logger
}

// JWKSResponse represents the JWKS response from Auth Service
type JWKSResponse struct {
	Keys []json.RawMessage `json:"keys"`
}

// NewJWKSCache creates a new JWKS cache
func NewJWKSCache(authURL string, ttlMinutes int, logger *log.Logger) *JWKSCache {
	return &JWKSCache{
		keys:       make(map[string]jwk.Key),
		ttl:        time.Duration(ttlMinutes) * time.Minute,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		authURL:    authURL,
		logger:     logger,
	}
}

// GetKey retrieves a key by kid from cache, fetching from Auth Service if needed
func (c *JWKSCache) GetKey(ctx context.Context, kid string) (jwk.Key, error) {
	// Try cache first
	c.mu.RLock()
	if key, ok := c.keys[kid]; ok && time.Since(c.fetchedAt) < c.ttl {
		c.mu.RUnlock()
		c.logger.Printf("JWKS cache hit for kid: %s", kid)
		return key, nil
	}
	c.mu.RUnlock()

	c.logger.Printf("JWKS cache miss for kid: %s, refreshing cache", kid)

	// Cache miss or expired - refresh
	if err := c.Refresh(ctx); err != nil {
		return nil, fmt.Errorf("failed to refresh JWKS cache: %w", err)
	}

	// Try again
	c.mu.RLock()
	defer c.mu.RUnlock()

	if key, ok := c.keys[kid]; ok {
		return key, nil
	}

	return nil, fmt.Errorf("key with kid %s not found in JWKS", kid)
}

// Refresh fetches JWKS from Auth Service and updates the cache
func (c *JWKSCache) Refresh(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	resp, err := c.httpClient.Get(c.authURL)
	if err != nil {
		return fmt.Errorf("failed to fetch JWKS: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("JWKS endpoint returned status %d", resp.StatusCode)
	}

	var jwks JWKSResponse
	if err := json.NewDecoder(resp.Body).Decode(&jwks); err != nil {
		return fmt.Errorf("failed to decode JWKS: %w", err)
	}

	// Clear old keys and populate new ones
	newKeys := make(map[string]jwk.Key)

	for i, keyData := range jwks.Keys {
		// Parse the key data to get kid
		var rawKey struct {
			Kid string `json:"kid"`
		}
		if err := json.Unmarshal(keyData, &rawKey); err != nil {
			c.logger.Printf("Skipping invalid key at index %d: %v", i, err)
			continue
		}

		kid := rawKey.Kid
		if kid == "" {
			kid = fmt.Sprintf("key-%d", i)
		}

		// jwk.Parse returns jwk.Set in newer versions
		parsed, err := jwk.Parse(keyData)
		if err != nil {
			c.logger.Printf("Skipping invalid key at index %d: %v", i, err)
			continue
		}

		// Extract key from jwk.Set
		set, ok := parsed.(jwk.Set)
		if !ok {
			c.logger.Printf("Skipping invalid key at index %d: not a jwk.Set", i)
			continue
		}

		// Extract key from jwk.Set using iterator
		var foundKey jwk.Key
		ctx := context.Background()
		for iter := set.Iterate(ctx); iter.Next(ctx); {
			pair := iter.Pair()
			if k, ok := pair.Value.(jwk.Key); ok {
				foundKey = k
				break
			}
		}

		if foundKey != nil {
			newKeys[kid] = foundKey
			c.logger.Printf("Cached JWKS key: %s", kid)
		}
	}

	c.keys = newKeys
	c.fetchedAt = time.Now()

	c.logger.Printf("JWKS cache refreshed with %d keys", len(c.keys))
	return nil
}

// ClearCache invalidates the entire JWKS cache
func (c *JWKSCache) ClearCache() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.keys = make(map[string]jwk.Key)
	c.fetchedAt = time.Time{}
	if c.logger != nil {
		c.logger.Printf("JWKS cache cleared")
	}
}

// IsExpired checks if the cache is expired
func (c *JWKSCache) IsExpired() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.fetchedAt.IsZero() {
		return true
	}
	return time.Since(c.fetchedAt) >= c.ttl
}

// InvalidateKey removes a specific key from cache
func (c *JWKSCache) InvalidateKey(kid string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, ok := c.keys[kid]; ok {
		delete(c.keys, kid)
		if c.logger != nil {
			c.logger.Printf("Invalidated key: %s", kid)
		}
	}
}

// CreateConfig creates the default plugin configuration
func CreateConfig() *PluginConfig {
	return &PluginConfig{}
}

// New creates a new JWT auth middleware plugin
func New(ctx context.Context, next http.Handler, config *json.RawMessage) (http.Handler, error) {
	// Parse config
	cfg := CreateConfig()
	if config != nil {
		if err := json.Unmarshal(*config, cfg); err != nil {
			return nil, fmt.Errorf("failed to parse config: %w", err)
		}
	}

	// Set defaults
	if cfg.AuthServiceURL == "" {
		cfg.AuthServiceURL = "http://auth-service:8081"
	}
	if cfg.JWKSPath == "" {
		cfg.JWKSPath = "/.well-known/jwks.json"
	}
	if cfg.RedisAddr == "" {
		cfg.RedisAddr = "redis:6379"
	}
	if cfg.JWKSCacheTTLMinutes == 0 {
		cfg.JWKSCacheTTLMinutes = 10
	}
	if cfg.DenylistKeyPrefix == "" {
		cfg.DenylistKeyPrefix = "auth:denylist"
	}
	if cfg.RevokedBeforePrefix == "" {
		cfg.RevokedBeforePrefix = "auth:revoked_before"
	}
	if cfg.JWKSInvalidateChannel == "" {
		cfg.JWKSInvalidateChannel = "auth:jwks:update"
	}
	if cfg.UserIdHeader == "" {
		cfg.UserIdHeader = "X-User-Id"
	}
	if cfg.UserRolesHeader == "" {
		cfg.UserRolesHeader = "X-User-Roles"
	}
	if cfg.UserEmailHeader == "" {
		cfg.UserEmailHeader = "X-User-Email"
	}
	if cfg.UserDeviceHeader == "" {
		cfg.UserDeviceHeader = "X-User-Device-Id"
	}
	if cfg.UserSessionHeader == "" {
		cfg.UserSessionHeader = "X-User-Session-Id"
	}
	if len(cfg.PublicPaths) == 0 {
		cfg.PublicPaths = []string{"/health", "/.well-known/", "/api/auth/"}
	}

	// Initialize logger
	logger := log.New(os.Stdout, "[jwt-auth] ", log.LstdFlags|log.Lmicroseconds)

	logger.Printf("Initializing JWT Auth Plugin:")
	logger.Printf("  Auth Service URL: %s", cfg.AuthServiceURL)
	logger.Printf("  JWKS Path: %s", cfg.JWKSPath)
	logger.Printf("  Redis: %s", cfg.RedisAddr)
	logger.Printf("  JWKS Cache TTL: %d minutes", cfg.JWKSCacheTTLMinutes)
	logger.Printf("  Public Paths: %v", cfg.PublicPaths)

	// Initialize JWKS cache
	cache := NewJWKSCache(
		cfg.AuthServiceURL+cfg.JWKSPath,
		cfg.JWKSCacheTTLMinutes,
		logger,
	)

	// Initialize Redis client
	rdb := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
		DB:       cfg.RedisDB,
		PoolSize: 10,
	})

	// Test Redis connection
	testCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := rdb.Ping(testCtx).Err(); err != nil {
		logger.Printf("Warning: Redis connection failed: %v (denylist checks will fail)", err)
	} else {
		logger.Printf("Redis connected successfully")
	}

	// Build public paths map
	publicPathsMap := make(map[string]bool)
	for _, path := range cfg.PublicPaths {
		publicPathsMap[path] = true
	}

	m := &JWTAuthMiddleware{
		next:           next,
		config:         cfg,
		cache:          cache,
		redis:          rdb,
		httpClient:     &http.Client{Timeout: 10 * time.Second},
		publicPathsMap: publicPathsMap,
		logger:         logger,
	}

	// Start Pub/Sub listener for JWKS invalidation
	go m.subscribeToJWKSUpdates()

	// Pre-fetch JWKS
	go func() {
		if err := cache.Refresh(context.Background()); err != nil {
			logger.Printf("Warning: Initial JWKS fetch failed: %v", err)
		} else {
			logger.Printf("Initial JWKS fetched successfully")
		}
	}()

	return m, nil
}

// ServeHTTP handles incoming requests
func (m *JWTAuthMiddleware) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
	// Get trace ID from context
	traceID := req.Header.Get("X-Request-Id")
	if traceID == "" {
		traceID = generateTraceID()
	}

	m.logger.Printf("[%s] %s %s", traceID, req.Method, req.URL.Path)

	// Check if path is public
	if m.isPublicPath(req.URL.Path) {
		m.logger.Printf("[%s] Public path, skipping auth", traceID)
		m.next.ServeHTTP(rw, req)
		return
	}

	// Extract Authorization header
	authHeader := req.Header.Get("Authorization")
	if authHeader == "" {
		m.logger.Printf("[%s] Missing Authorization header", traceID)
		m.writeError(rw, http.StatusUnauthorized, "missing_authorization", "Missing authorization header", traceID)
		return
	}

	// Extract bearer token
	token, err := extractBearerToken(authHeader)
	if err != nil {
		m.logger.Printf("[%s] Invalid authorization header: %v", traceID, err)
		m.writeError(rw, http.StatusUnauthorized, "invalid_authorization", "Invalid authorization header format", traceID)
		return
	}

	// Parse and validate JWT
	claims, err := m.validateAndParseToken(req.Context(), token, traceID)
	if err != nil {
		m.logger.Printf("[%s] Token validation failed: %v", traceID, err)
		m.writeError(rw, http.StatusUnauthorized, "invalid_token", err.Error(), traceID)
		return
	}

	// Check denylist
	if err := m.checkDenylist(req.Context(), claims.JTI, traceID); err != nil {
		m.logger.Printf("[%s] Token is denylisted: %v", traceID, err)
		m.writeError(rw, http.StatusUnauthorized, "token_revoked", "Token has been revoked", traceID)
		return
	}

	// Check revocation (user force logout / password change)
	if err := m.checkRevocation(req.Context(), claims.Subject, claims.IAT, traceID); err != nil {
		m.logger.Printf("[%s] Token revoked due to user action: %v", traceID, err)
		m.writeError(rw, http.StatusUnauthorized, "token_revoked", "Token has been revoked", traceID)
		return
	}

	// Enrich headers
	m.enrichHeaders(req, claims)

	m.logger.Printf("[%s] Auth successful: user=%s", traceID, claims.Subject)

	// Continue to next handler
	m.next.ServeHTTP(rw, req)
}

// isPublicPath checks if the path is in the public paths list
func (m *JWTAuthMiddleware) isPublicPath(path string) bool {
	for _, prefix := range m.config.PublicPaths {
		if strings.HasPrefix(path, prefix) {
			return true
		}
	}
	return false
}

// validateAndParseToken validates JWT and returns claims
func (m *JWTAuthMiddleware) validateAndParseToken(ctx context.Context, tokenString string, traceID string) (*Claims, error) {
	// Extract kid from header
	kid, err := extractKID(tokenString)
	if err != nil {
		return nil, fmt.Errorf("failed to extract kid: %w", err)
	}

	if kid == "" {
		return nil, fmt.Errorf("kid is missing from token header")
	}

	// Get public key from cache
	key, err := m.cache.GetKey(ctx, kid)
	if err != nil {
		return nil, fmt.Errorf("failed to get public key for kid %s: %w", kid, err)
	}

	// Parse and validate JWT
	token, err := jwt.Parse(
		[]byte(tokenString),
		jwt.WithKey(jwa.RS256, key),
		jwt.WithContext(ctx),
		jwt.WithValidate(true),
	)
	if err != nil {
		return nil, fmt.Errorf("JWT validation failed: %w", err)
	}

	claims := &Claims{
		Subject: token.Subject(),
		JTI:     token.JwtID(),
		IAT:     token.IssuedAt().Unix(),
	}

	// Extract optional claims
	if email, ok := token.Get("email"); ok {
		claims.Email, _ = email.(string)
	}
	if roles, ok := token.Get("roles"); ok {
		switch v := roles.(type) {
		case []interface{}:
			for _, r := range v {
				if s, ok := r.(string); ok {
					claims.Roles = append(claims.Roles, s)
				}
			}
		case string:
			claims.Roles = parseRoles(v)
		}
	}
	if deviceID, ok := token.Get("device_id"); ok {
		claims.DeviceID, _ = deviceID.(string)
	}
	if sessionID, ok := token.Get("session_id"); ok {
		claims.SessionID, _ = sessionID.(string)
	}

	return claims, nil
}

// checkDenylist checks if token's JTI is in Redis denylist
func (m *JWTAuthMiddleware) checkDenylist(ctx context.Context, jti string, traceID string) error {
	if jti == "" {
		return nil
	}

	key := fmt.Sprintf("%s:%s", m.config.DenylistKeyPrefix, jti)
	exists, err := m.redis.Exists(ctx, key).Result()
	if err != nil {
		m.logger.Printf("[%s] Redis denylist check error: %v", traceID, err)
		return err
	}

	if exists > 0 {
		return fmt.Errorf("token jti %s is denylisted", jti)
	}

	return nil
}

// checkRevocation checks if user has force-logged out or changed password
func (m *JWTAuthMiddleware) checkRevocation(ctx context.Context, userID string, iat int64, traceID string) error {
	if userID == "" {
		return nil
	}

	key := fmt.Sprintf("%s:%s", m.config.RevokedBeforePrefix, userID)
	revokedAt, err := m.redis.Get(ctx, key).Int64()
	if err != nil && err != redis.Nil {
		m.logger.Printf("[%s] Redis revocation check error: %v", traceID, err)
		return err
	}

	if err == nil && iat < revokedAt {
		return fmt.Errorf("token issued before revocation (revoked_at=%d, iat=%d)", revokedAt, iat)
	}

	return nil
}

// enrichHeaders adds user information to request headers
func (m *JWTAuthMiddleware) enrichHeaders(req *http.Request, claims *Claims) {
	if claims.Subject != "" {
		req.Header.Set(m.config.UserIdHeader, claims.Subject)
	}
	if claims.Email != "" {
		req.Header.Set(m.config.UserEmailHeader, claims.Email)
	}
	if len(claims.Roles) > 0 {
		req.Header.Set(m.config.UserRolesHeader, strings.Join(claims.Roles, ","))
	}
	if claims.DeviceID != "" {
		req.Header.Set(m.config.UserDeviceHeader, claims.DeviceID)
	}
	if claims.SessionID != "" {
		req.Header.Set(m.config.UserSessionHeader, claims.SessionID)
	}
}

// subscribeToJWKSUpdates listens for JWKS invalidation events via Redis Pub/Sub
func (m *JWTAuthMiddleware) subscribeToJWKSUpdates() {
	for {
		func() {
			ctx := context.Background()

			// Subscribe to JWKS invalidation channel
			pubsub := m.redis.Subscribe(ctx, m.config.JWKSInvalidateChannel)
			defer pubsub.Close()

			ch := pubsub.Channel()

			for msg := range ch {
				m.logger.Printf("Received JWKS invalidation: %s", msg.Payload)

				var event JWKSInvalidationEvent
				if err := json.Unmarshal([]byte(msg.Payload), &event); err != nil {
					m.logger.Printf("Failed to parse JWKS invalidation event: %v", err)
					continue
				}

				switch event.Type {
				case "key_rotation", "key_retirement":
					if event.Kid != "" {
						m.cache.InvalidateKey(event.Kid)
						m.logger.Printf("Invalidated specific key: %s", event.Kid)
					} else {
						m.cache.ClearCache()
						m.logger.Printf("Cleared entire JWKS cache")
					}

				case "all":
					m.cache.ClearCache()
					m.logger.Printf("Cleared entire JWKS cache (all)")

				default:
					m.logger.Printf("Unknown JWKS invalidation event type: %s", event.Type)
				}
			}
		}()

		// Reconnect on disconnect with backoff
		m.logger.Printf("Pub/Sub disconnected, reconnecting in 1 second...")
		time.Sleep(time.Second)
	}
}

// writeError writes a JSON error response
func (m *JWTAuthMiddleware) writeError(rw http.ResponseWriter, status int, code, message, traceID string) {
	rw.Header().Set("Content-Type", "application/json")
	rw.Header().Set("X-Request-Id", traceID)
	rw.WriteHeader(status)

	body, _ := json.Marshal(map[string]string{
		"error":      code,
		"message":    message,
		"request_id": traceID,
	})
	rw.Write(body)
}

// Helper functions

func generateTraceID() string {
	const letters = "abcdef0123456789"
	b := make([]byte, 16)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
		time.Sleep(time.Nanosecond)
	}
	return string(b)
}

func parseRoles(roles string) []string {
	if roles == "" {
		return nil
	}
	return strings.Split(roles, ",")
}

// extractBearerToken extracts JWT from Authorization header
func extractBearerToken(authHeader string) (string, error) {
	if authHeader == "" {
		return "", fmt.Errorf("authorization header is empty")
	}

	parts := strings.SplitN(authHeader, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "bearer") {
		return "", fmt.Errorf("invalid authorization header format")
	}

	return strings.TrimSpace(parts[1]), nil
}

// extractKID extracts the Key ID from JWT header
func extractKID(tokenString string) (string, error) {
	parts := strings.Split(tokenString, ".")
	if len(parts) != 3 {
		return "", fmt.Errorf("invalid JWT format: expected 3 parts, got %d", len(parts))
	}

	// Add padding if needed
	header := parts[0]
	switch len(parts[0]) % 4 {
	case 2:
		header += "=="
	case 3:
		header += "="
	}

	// Decode base64
	headerJSON, err := base64.StdEncoding.DecodeString(header)
	if err != nil {
		return "", fmt.Errorf("failed to decode JWT header: %w", err)
	}

	var headerObj struct {
		Kid string `json:"kid"`
		Alg string `json:"alg"`
		Typ string `json:"typ"`
	}

	if err := json.Unmarshal(headerJSON, &headerObj); err != nil {
		return "", fmt.Errorf("failed to parse JWT header: %w", err)
	}

	return headerObj.Kid, nil
}
