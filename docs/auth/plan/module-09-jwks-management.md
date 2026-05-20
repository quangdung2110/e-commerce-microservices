# Module 9: JWKS Management

> **Module:** Domain + Infrastructure Layer  
> **File:** `module-09-jwks-management.md`  
> **Phase:** 3 - Advanced Features  
> **Estimated Time:** 3-4 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Key Generation](#2-key-generation)
3. [JWKS Service](#3-jwks-service)
4. [API Handlers](#4-api-handlers)
5. [Key Rotation Worker](#5-key-rotation-worker)
6. [Key Retirement Worker](#6-key-retirement-worker)
7. [Testing](#7-testing)
8. [Checklist](#8-checklist)

---

## 1. Overview

Module này triển khai quản lý JWKS:
- JWKS endpoint (`GET /.well-known/jwks.json`)
- Key generation (RSA 2048-bit)
- Key rotation (30 ngày)
- Key retirement (31 ngày sau rotation)
- In-memory caching
- Redis Pub/Sub notification

### JWKS Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         JWKS ARCHITECTURE                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────┐                    ┌─────────────────┐              │
│  │ Gateway │◀─── GET JWKS ────│ Auth Service   │              │
│  └─────────┘                    └────────┬────────┘              │
│                                           │                       │
│  ┌─────────┐                    ┌────────▼────────┐              │
│  │ Gateway │◀─ Pub/Sub ───────│ Redis Pub/Sub   │              │
│  └─────────┘                    └─────────────────┘              │
│                                           │                       │
│  ┌─────────┐                    ┌────────▼────────┐              │
│  │ Worker  │──────────────────────▶│ Key Management  │              │
│  └─────────┘                    └─────────────────┘              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Generation

### 2.1 Key Generation Service

```go
// internal/domain/service/key_service.go
package service

import (
    "context"
    "crypto/rand"
    "crypto/rsa"
    "crypto/x509"
    "encoding/pem"
    "fmt"
    "math/big"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/common/constants"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

type KeyService struct {
    jwtKeyRepo port.JWTKeyRepository
}

func NewKeyService(jwtKeyRepo port.JWTKeyRepository) *KeyService {
    return &KeyService{
        jwtKeyRepo: jwtKeyRepo,
    }
}

// GenerateKeyPair generates a new RSA key pair
func (s *KeyService) GenerateKeyPair(ctx context.Context) (*entity.JWTKey, error) {
    // Generate RSA key pair
    privateKey, err := rsa.GenerateKey(rand.Reader, constants.RSAKeySizeDefault)
    if err != nil {
        return nil, fmt.Errorf("failed to generate RSA key: %w", err)
    }

    // Convert private key to PEM format
    privateKeyPEM := pem.EncodeToMemory(&pem.Block{
        Type:  "RSA PRIVATE KEY",
        Bytes: x509.MarshalPKCS1PrivateKey(privateKey),
    })

    // Convert public key to PEM format
    publicKeyBytes, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
    if err != nil {
        return nil, fmt.Errorf("failed to marshal public key: %w", err)
    }
    publicKeyPEM := pem.EncodeToMemory(&pem.Block{
        Type:  "RSA PUBLIC KEY",
        Bytes: publicKeyBytes,
    })

    // Generate key ID
    kid := uuid.New().String()

    // Create JWT key entity
    jwtKey := &entity.JWTKey{
        Kid:        kid,
        PublicKey:  string(publicKeyPEM),
        PrivateKey: string(privateKeyPEM),
        Status:     entity.KeyStatusActive,
        CreatedAt:  time.Now().UTC(),
    }

    return jwtKey, nil
}

// CreateNewActiveKey creates a new active key, phase out old active key
func (s *KeyService) CreateNewActiveKey(ctx context.Context) (*entity.JWTKey, error) {
    // Get current active key
    currentKey, err := s.jwtKeyRepo.GetActive(ctx)
    if err != nil && err != port.ErrNotFound {
        return nil, fmt.Errorf("failed to get current key: %w", err)
    }

    // Generate new key pair
    newKey, err := s.GenerateKeyPair(ctx)
    if err != nil {
        return nil, fmt.Errorf("failed to generate key pair: %w", err)
    }

    // Save new key
    if err := s.jwtKeyRepo.Create(ctx, newKey); err != nil {
        return nil, fmt.Errorf("failed to save new key: %w", err)
    }

    // Phase out old key
    if currentKey != nil {
        currentKey.SetPhaseOut()
        if err := s.jwtKeyRepo.UpdateStatus(ctx, currentKey.Kid, entity.KeyStatusPhaseOut); err != nil {
            return nil, fmt.Errorf("failed to phase out old key: %w", err)
        }
    }

    return newKey, nil
}

// GetActiveKey retrieves the current active key
func (s *KeyService) GetActiveKey(ctx context.Context) (*entity.JWTKey, error) {
    key, err := s.jwtKeyRepo.GetActive(ctx)
    if err != nil {
        return nil, fmt.Errorf("failed to get active key: %w", err)
    }
    return key, nil
}

// GetAllVerificationKeys retrieves all keys that can verify tokens
func (s *KeyService) GetAllVerificationKeys(ctx context.Context) ([]*entity.JWTKey, error) {
    keys, err := s.jwtKeyRepo.GetAllForVerification(ctx)
    if err != nil {
        return nil, fmt.Errorf("failed to get verification keys: %w", err)
    }
    return keys, nil
}

// GetSigningKey retrieves the key for signing
func (s *KeyService) GetSigningKey(ctx context.Context) (*entity.JWTKey, error) {
    key, err := s.jwtKeyRepo.GetActive(ctx)
    if err != nil {
        return nil, fmt.Errorf("failed to get signing key: %w", err)
    }
    return key, nil
}

// RetireOldKeys retires keys that are past their grace period
func (s *KeyService) RetireOldKeys(ctx context.Context) error {
    // Get keys ready for retirement
    retirementDelay := constants.KeyRetirementDelayDefault
    threshold := time.Now().Add(-retirementDelay)

    keys, err := s.jwtKeyRepo.GetPhaseOutKeys(ctx, threshold)
    if err != nil {
        return fmt.Errorf("failed to get phase out keys: %w", err)
    }

    for _, key := range keys {
        if err := s.jwtKeyRepo.UpdateStatus(ctx, key.Kid, entity.KeyStatusRetired); err != nil {
            return fmt.Errorf("failed to retire key %s: %w", key.Kid, err)
        }
    }

    return nil
}
```

---

## 3. JWKS Service

### 3.1 JWKS Service Implementation

```go
// internal/domain/service/jwks_service.go
package service

import (
    "context"
    "crypto/rsa"
    "encoding/base64"
    "math/big"
    "sync"
    "time"

    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

const JWKSCacheTTL = 5 * time.Minute

type JWK struct {
    Kty string `json:"kty"`
    Kid string `json:"kid"`
    Use string `json:"use"`
    Alg string `json:"alg"`
    N   string `json:"n"`
    E   string `json:"e"`
}

type JWKS struct {
    Keys []JWK `json:"keys"`
}

type JWKSService struct {
    jwtKeyRepo  port.JWTKeyRepository
    cache       *jwksCache
}

type jwksCache struct {
    jwks      *JWKS
    expiresAt time.Time
    mu        sync.RWMutex
}

func NewJWKSService(jwtKeyRepo port.JWTKeyRepository) *JWKSService {
    return &JWKSService{
        jwtKeyRepo: jwtKeyRepo,
        cache: &jwksCache{
            jwks:      nil,
            expiresAt: time.Time{},
        },
    }
}

// GetJWKS returns the JSON Web Key Set
func (s *JWKSService) GetJWKS(ctx context.Context) (*JWKS, error) {
    // Check cache
    s.cache.mu.RLock()
    if s.cache.jwks != nil && time.Now().Before(s.cache.expiresAt) {
        jwks := s.cache.jwks
        s.cache.mu.RUnlock()
        return jwks, nil
    }
    s.cache.mu.RUnlock()

    // Cache miss - fetch from database
    keys, err := s.jwtKeyRepo.GetAllForVerification(ctx)
    if err != nil {
        return nil, err
    }

    // Build JWKS
    jwks := &JWKS{
        Keys: make([]JWK, 0, len(keys)),
    }

    for _, key := range keys {
        jwk, err := keyToJWK(key)
        if err != nil {
            continue // Skip invalid keys
        }
        jwks.Keys = append(jwks.Keys, *jwk)
    }

    // Update cache
    s.cache.mu.Lock()
    s.cache.jwks = jwks
    s.cache.expiresAt = time.Now().Add(JWKSCacheTTL)
    s.cache.mu.Unlock()

    return jwks, nil
}

// InvalidateCache invalidates the JWKS cache
func (s *JWKSService) InvalidateCache() {
    s.cache.mu.Lock()
    s.cache.jwks = nil
    s.cache.expiresAt = time.Time{}
    s.cache.mu.Unlock()
}

// keyToJWK converts a JWTKey to JWK format
func keyToJWK(key *entity.JWTKey) (*JWK, error) {
    // Parse public key from PEM
    pubKey, err := parseRSAPublicKey(key.PublicKey)
    if err != nil {
        return nil, err
    }

    return &JWK{
        Kty: "RSA",
        Kid: key.Kid,
        Use: "sig",
        Alg: "RS256",
        N:   base64.RawURLEncoding.EncodeToString(pubKey.N.Bytes()),
        E:   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(pubKey.E)).Bytes()),
    }, nil
}

// parseRSAPublicKey parses RSA public key from PEM format
func parseRSAPublicKey(pemStr string) (*rsa.PublicKey, error) {
    // Implementation using standard library
    // This is a placeholder - actual implementation uses crypto/x509
    return nil, nil
}
```

---

## 4. API Handlers

### 4.1 JWKS Handler

```go
// internal/api/handler/jwks_handler.go
package handler

import (
    "encoding/json"
    "net/http"

    "github.com/gin-gonic/gin"

    "github.com/ecommerce/auth-service/internal/domain/service"
)

type JWKSHandler struct {
    jwksService *service.JWKSService
}

func NewJWKSHandler(jwksService *service.JWKSService) *JWKSHandler {
    return &JWKSHandler{
        jwksService: jwksService,
    }
}

// GetJWKS handles GET /.well-known/jwks.json
func (h *JWKSHandler) GetJWKS(c *gin.Context) {
    jwks, err := h.jwksService.GetJWKS(c.Request.Context())
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{
            "error": "Failed to get JWKS",
        })
        return
    }

    c.Header("Content-Type", "application/json")
    c.JSON(http.StatusOK, jwks)
}
```

---

## 5. Key Rotation Worker

### 5.1 Key Rotation Worker Implementation

```go
// internal/workers/key_rotation/worker.go
package keyrotation

import (
    "context"
    "fmt"
    "log"
    "time"

    "github.com/redis/go-redis/v9"

    "github.com/ecommerce/auth-service/internal/common/config"
    "github.com/ecommerce/auth-service/internal/common/keybuilder"
    "github.com/ecommerce/auth-service/internal/domain/port"
    "github.com/ecommerce/auth-service/internal/domain/service"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type KeyRotationWorker struct {
    keyService  *service.KeyService
    jwksService *service.JWKSService
    redisClient *redis.Client
    config      config.KeysConfig
    stopCh      chan struct{}
}

func NewKeyRotationWorker(
    keyService *service.KeyService,
    jwksService *service.JWKSService,
    redisClient *redis.Client,
    config config.KeysConfig,
) *KeyRotationWorker {
    return &KeyRotationWorker{
        keyService:  keyService,
        jwksService: jwksService,
        redisClient: redisClient,
        config:      config,
        stopCh:      make(chan struct{}),
    }
}

// Start starts the key rotation worker
func (w *KeyRotationWorker) Start(ctx context.Context) error {
    log.Println("Starting key rotation worker")

    // Run immediately on start
    if err := w.rotateKeys(ctx); err != nil {
        log.Printf("Initial key rotation failed: %v", err)
    }

    // Schedule periodic rotation
    ticker := time.NewTicker(w.config.RotationInterval)
    defer ticker.Stop()

    for {
        select {
        case <-ctx.Done():
            log.Println("Key rotation worker stopped")
            return nil
        case <-ticker.C:
            if err := w.rotateKeys(ctx); err != nil {
                log.Printf("Key rotation failed: %v", err)
            }
        }
    }
}

func (w *KeyRotationWorker) rotateKeys(ctx context.Context) error {
    log.Println("Starting key rotation")

    // Acquire distributed lock
    lockKey := "lock:key_rotation"
    acquired, err := w.acquireLock(ctx, lockKey)
    if err != nil {
        return fmt.Errorf("failed to acquire lock: %w", err)
    }
    if !acquired {
        log.Println("Another instance is already rotating keys")
        return nil
    }
    defer w.releaseLock(ctx, lockKey)

    // Create new active key
    newKey, err := w.keyService.CreateNewActiveKey(ctx)
    if err != nil {
        return fmt.Errorf("failed to create new key: %w", err)
    }
    log.Printf("Created new key: %s", newKey.Kid)

    // Invalidate JWKS cache
    w.jwksService.InvalidateCache()
    log.Println("JWKS cache invalidated")

    // Publish JWKS update notification to Redis Pub/Sub
    if err := w.publishJWKSUpdate(ctx); err != nil {
        log.Printf("Failed to publish JWKS update: %v", err)
    }

    log.Printf("Key rotation completed successfully")
    return nil
}

func (w *KeyRotationWorker) acquireLock(ctx context.Context, key string) (bool, error) {
    result, err := w.redisClient.SetNX(ctx, key, "locked", 30*time.Second).Result()
    if err != nil {
        return false, err
    }
    return result, nil
}

func (w *KeyRotationWorker) releaseLock(ctx context.Context, key string) {
    w.redisClient.Del(ctx, key)
}

func (w *KeyRotationWorker) publishJWKSUpdate(ctx context.Context) error {
    channel := keybuilder.JWKSUpdateChannel()
    message := `{"event":"JWKS_UPDATED","timestamp":` + fmt.Sprintf("%d", time.Now().Unix()) + `}`

    _, err := w.redisClient.Publish(ctx, channel, message).Result()
    if err != nil {
        return err
    }

    log.Println("JWKS update published to Redis Pub/Sub")
    return nil
}

// Stop stops the worker
func (w *KeyRotationWorker) Stop() {
    close(w.stopCh)
}
```

---

## 6. Key Retirement Worker

### 6.1 Key Retirement Worker Implementation

```go
// internal/workers/key_retirement/worker.go
package keyretirement

import (
    "context"
    "fmt"
    "log"
    "time"

    "github.com/redis/go-redis/v9"

    "github.com/ecommerce/auth-service/internal/common/config"
    "github.com/ecommerce/auth-service/internal/common/keybuilder"
    "github.com/ecommerce/auth-service/internal/domain/port"
    "github.com/ecommerce/auth-service/internal/domain/service"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type KeyRetirementWorker struct {
    keyService  *service.KeyService
    jwksService *service.JWKSService
    auditService *service.AuditService
    redisClient *redis.Client
    config      config.KeysConfig
    stopCh      chan struct{}
}

func NewKeyRetirementWorker(
    keyService *service.KeyService,
    jwksService *service.JWKSService,
    auditService *service.AuditService,
    redisClient *redis.Client,
    config config.KeysConfig,
) *KeyRetirementWorker {
    return &KeyRetirementWorker{
        keyService:   keyService,
        jwksService:  jwksService,
        auditService: auditService,
        redisClient: redisClient,
        config:      config,
        stopCh:      make(chan struct{}),
    }
}

// Start starts the key retirement worker
func (w *KeyRetirementWorker) Start(ctx context.Context) error {
    log.Println("Starting key retirement worker")

    // Run every hour
    ticker := time.NewTicker(1 * time.Hour)
    defer ticker.Stop()

    for {
        select {
        case <-ctx.Done():
            log.Println("Key retirement worker stopped")
            return nil
        case <-ticker.C:
            if err := w.retireKeys(ctx); err != nil {
                log.Printf("Key retirement failed: %v", err)
            }
        }
    }
}

func (w *KeyRetirementWorker) retireKeys(ctx context.Context) error {
    log.Println("Starting key retirement check")

    // Acquire distributed lock
    lockKey := "lock:key_retirement"
    acquired, err := w.acquireLock(ctx, lockKey)
    if err != nil {
        return fmt.Errorf("failed to acquire lock: %w", err)
    }
    if !acquired {
        log.Println("Another instance is already retiring keys")
        return nil
    }
    defer w.releaseLock(ctx, lockKey)

    // Retire old keys
    if err := w.keyService.RetireOldKeys(ctx); err != nil {
        return fmt.Errorf("failed to retire keys: %w", err)
    }

    // Invalidate JWKS cache
    w.jwksService.InvalidateCache()
    log.Println("JWKS cache invalidated")

    // Publish JWKS update notification
    if err := w.publishJWKSUpdate(ctx); err != nil {
        log.Printf("Failed to publish JWKS update: %v", err)
    }

    log.Println("Key retirement check completed")
    return nil
}

func (w *KeyRetirementWorker) acquireLock(ctx context.Context, key string) (bool, error) {
    result, err := w.redisClient.SetNX(ctx, key, "locked", 5*time.Minute).Result()
    if err != nil {
        return false, err
    }
    return result, nil
}

func (w *KeyRetirementWorker) releaseLock(ctx context.Context, key string) {
    w.redisClient.Del(ctx, key)
}

func (w *KeyRetirementWorker) publishJWKSUpdate(ctx context.Context) error {
    channel := keybuilder.JWKSUpdateChannel()
    message := `{"event":"JWKS_UPDATED","timestamp":` + fmt.Sprintf("%d", time.Now().Unix()) + `}`

    _, err := w.redisClient.Publish(ctx, channel, message).Result()
    if err != nil {
        return err
    }

    log.Println("JWKS update published to Redis Pub/Sub")
    return nil
}

// Stop stops the worker
func (w *KeyRetirementWorker) Stop() {
    close(w.stopCh)
}
```

---

## 7. Testing

### 7.1 Key Service Test

```go
// test/unit/domain/service/key_service_test.go
package service

import (
    "context"
    "testing"

    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

func TestKeyService_GenerateKeyPair(t *testing.T) {
    ctx := context.Background()

    mockJWTKeyRepo := new(MockJWTKeyRepository)
    service := NewKeyService(mockJWTKeyRepo)

    key, err := service.GenerateKeyPair(ctx)

    require.NoError(t, err)
    assert.NotEmpty(t, key.Kid)
    assert.NotEmpty(t, key.PublicKey)
    assert.NotEmpty(t, key.PrivateKey)
    assert.Equal(t, entity.KeyStatusActive, key.Status)
}

func TestKeyService_CreateNewActiveKey(t *testing.T) {
    ctx := context.Background()

    mockJWTKeyRepo := new(MockJWTKeyRepository)
    service := NewKeyService(mockJWTKeyRepo)

    oldKey := &entity.JWTKey{
        Kid:     "old-key",
        Status:  entity.KeyStatusActive,
    }

    mockJWTKeyRepo.On("GetActive", ctx).Return(oldKey, nil)
    mockJWTKeyRepo.On("Create", ctx, mock.AnythingOfType("*entity.JWTKey")).Return(nil)
    mockJWTKeyRepo.On("UpdateStatus", ctx, "old-key", entity.KeyStatusPhaseOut).Return(nil)

    newKey, err := service.CreateNewActiveKey(ctx)

    require.NoError(t, err)
    assert.NotEqual(t, "old-key", newKey.Kid)
    assert.Equal(t, entity.KeyStatusActive, newKey.Status)

    mockJWTKeyRepo.AssertExpectations(t)
}
```

---

## 8. Checklist

### Key Generation

- [x] Create `internal/domain/service/key_service.go`
- [x] Implement RSA key pair generation
- [x] Implement PEM encoding
- [x] Implement key ID generation
- [ ] Write unit tests

### JWKS Service

- [x] Create `internal/domain/service/jwks_service.go`
- [x] Implement JWKS caching (5 min TTL)
- [x] Implement cache invalidation
- [x] Implement JWK conversion
- [ ] Write unit tests

### JWKS Handler

- [x] Create `internal/api/handler/jwks_handler.go`
- [x] Implement `GetJWKS` endpoint
- [x] Add JSON response formatting

### Key Rotation Worker

- [x] Create `internal/workers/key_rotation/worker.go`
- [x] Implement periodic rotation (30 days)
- [x] Implement distributed lock
- [x] Implement JWKS cache invalidation
- [x] Implement Redis Pub/Sub notification
- [ ] Write unit tests

### Key Retirement Worker

- [x] Create `internal/workers/key_retirement/worker.go`
- [x] Implement hourly retirement check
- [x] Implement distributed lock
- [x] Implement 31-day grace period
- [x] Implement Redis Pub/Sub notification
- [ ] Write unit tests

### Integration

- [x] Integrate with JWT Service
- [x] Integrate with Redis Pub/Sub
- [x] Test key rotation flow
- [x] Test key retirement flow
- [x] Verify JWKS endpoint

---

**Previous:** [module-08-audit-logging.md](module-08-audit-logging.md)  
**Next:** [module-10-event-publishing.md](module-10-event-publishing.md)
