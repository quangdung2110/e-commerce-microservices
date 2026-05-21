# Module 11: Background Workers

> **Module:** Workers Layer  
> **File:** `module-11-background-workers.md`  
> **Phase:** 4 - Workers & Operations  
> **Estimated Time:** 3-4 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Token Cleanup Worker](#2-token-cleanup-worker)
3. [Auto-Unlock Worker](#3-auto-unlock-worker)
4. [Audit Log Cleanup Worker](#4-audit-log-cleanup-worker)
5. [Worker Runner](#5-worker-runner)
6. [Testing](#6-testing)
7. [Checklist](#7-checklist)

---

## 1. Overview

Module này triển khai các background workers:
- Token Cleanup Worker: Dọn refresh tokens hết hạn
- Auto-Unlock Worker: Tự động mở khóa tài khoản
- Audit Log Cleanup Worker: Dọn audit logs cũ

### Worker Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    BACKGROUND WORKERS                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐     ┌──────────────────┐                │
│  │ Token Cleanup    │     │ Auto-Unlock      │                │
│  │ (Daily 2 AM)    │     │ (Every 5 min)    │                │
│  └────────┬─────────┘     └────────┬─────────┘                │
│           │                          │                          │
│           ▼                          ▼                          │
│  ┌──────────────────────────────────────────┐                │
│  │           Distributed Lock (Redis)         │                │
│  └──────────────────────────────────────────┘                │
│                                                                  │
│  ┌──────────────────┐                                         │
│  │ Audit Cleanup    │                                         │
│  │ (Daily 3 AM)     │                                         │
│  └──────────────────┘                                         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Token Cleanup Worker

### 2.1 Token Cleanup Worker Implementation

```go
// internal/workers/token_cleanup/worker.go
package tokencleanup

import (
    "context"
    "fmt"
    "log"
    "time"

    "github.com/redis/go-redis/v9"

    "github.com/ecommerce/auth-service/internal/common/config"
    "github.com/ecommerce/auth-service/internal/domain/port"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type TokenCleanupWorker struct {
    refreshTokenRepo port.RefreshTokenRepository
    redisClient      *redis.Client
    config           config.CleanupConfig
    stopCh           chan struct{}
}

func NewTokenCleanupWorker(
    refreshTokenRepo port.RefreshTokenRepository,
    redisClient *redis.Client,
    config config.CleanupConfig,
) *TokenCleanupWorker {
    return &TokenCleanupWorker{
        refreshTokenRepo: refreshTokenRepo,
        redisClient:      redisClient,
        config:           config,
        stopCh:           make(chan struct{}),
    }
}

// Start starts the token cleanup worker
func (w *TokenCleanupWorker) Start(ctx context.Context) error {
    log.Println("Starting token cleanup worker")

    // Run immediately on start
    if err := w.cleanup(ctx); err != nil {
        log.Printf("Initial token cleanup failed: %v", err)
    }

    // Schedule daily cleanup at 2 AM
    ticker := w.newDailyTicker(2, 0) // 2:00 AM
    defer ticker.Stop()

    for {
        select {
        case <-ctx.Done():
            log.Println("Token cleanup worker stopped")
            return nil
        case <-ticker.C:
            if err := w.cleanup(ctx); err != nil {
                log.Printf("Token cleanup failed: %v", err)
            }
        }
    }
}

func (w *TokenCleanupWorker) cleanup(ctx context.Context) error {
    log.Println("Starting token cleanup")

    // Acquire distributed lock
    lockKey := "lock:token_cleanup"
    acquired, err := w.acquireLock(ctx, lockKey)
    if err != nil {
        return fmt.Errorf("failed to acquire lock: %w", err)
    }
    if !acquired {
        log.Println("Another instance is already cleaning up tokens")
        return nil
    }
    defer w.releaseLock(ctx, lockKey)

    // Calculate cutoff time
    cutoff := time.Now().Add(-w.config.RefreshTokenRetention)

    // Delete expired tokens
    deleted, err := w.refreshTokenRepo.DeleteExpired(ctx, cutoff)
    if err != nil {
        return fmt.Errorf("failed to delete expired tokens: %w", err)
    }

    log.Printf("Token cleanup completed: %d tokens deleted", deleted)
    return nil
}

func (w *TokenCleanupWorker) acquireLock(ctx context.Context, key string) (bool, error) {
    result, err := w.redisClient.SetNX(ctx, key, "locked", 5*time.Minute).Result()
    if err != nil {
        return false, err
    }
    return result, nil
}

func (w *TokenCleanupWorker) releaseLock(ctx context.Context, key string) {
    w.redisClient.Del(ctx, key)
}

// newDailyTicker creates a ticker that fires at the specified hour and minute
func (w *TokenCleanupWorker) newDailyTicker(hour, minute int) *time.Ticker {
    now := time.Now()
    target := time.Date(
        now.Year(), now.Month(), now.Day(),
        hour, minute, 0, 0,
        now.Location(),
    )
    
    if target.Before(now) {
        target = target.Add(24 * time.Hour)
    }
    
    duration := target.Sub(now)
    ticker := time.NewTicker(duration)
    
    // Return a ticker that fires every 24 hours after the initial delay
    return ticker
}

// Stop stops the worker
func (w *TokenCleanupWorker) Stop() {
    close(w.stopCh)
}
```

---

## 3. Auto-Unlock Worker

### 3.1 Auto-Unlock Worker Implementation

```go
// internal/workers/auto_unlock/worker.go
package autounlock

import (
    "context"
    "fmt"
    "log"
    "time"

    "github.com/redis/go-redis/v9"

    "github.com/ecommerce/auth-service/internal/common/config"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/event"
    "github.com/ecommerce/auth-service/internal/domain/port"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

const AutoUnlockDuration = 30 * time.Minute

type AutoUnlockWorker struct {
    userRepo       port.UserRepository
    eventPublisher port.EventPublisher
    redisClient    *redis.Client
    config         config.CleanupConfig
    stopCh         chan struct{}
}

func NewAutoUnlockWorker(
    userRepo port.UserRepository,
    eventPublisher port.EventPublisher,
    redisClient *redis.Client,
    config config.CleanupConfig,
) *AutoUnlockWorker {
    return &AutoUnlockWorker{
        userRepo:       userRepo,
        eventPublisher: eventPublisher,
        redisClient:    redisClient,
        config:         config,
        stopCh:         make(chan struct{}),
    }
}

// Start starts the auto-unlock worker
func (w *AutoUnlockWorker) Start(ctx context.Context) error {
    log.Println("Starting auto-unlock worker")

    // Run every 5 minutes
    ticker := time.NewTicker(w.config.AutoUnlockInterval)
    defer ticker.Stop()

    for {
        select {
        case <-ctx.Done():
            log.Println("Auto-unlock worker stopped")
            return nil
        case <-ticker.C:
            if err := w.unlockAccounts(ctx); err != nil {
                log.Printf("Auto-unlock failed: %v", err)
            }
        }
    }
}

func (w *AutoUnlockWorker) unlockAccounts(ctx context.Context) error {
    // Acquire distributed lock
    lockKey := "lock:auto_unlock"
    acquired, err := w.acquireLock(ctx, lockKey)
    if err != nil {
        return fmt.Errorf("failed to acquire lock: %w", err)
    }
    if !acquired {
        return nil
    }
    defer w.releaseLock(ctx, lockKey)

    // Get locked users
    users, err := w.userRepo.GetLockedUsers(ctx, AutoUnlockDuration, 100)
    if err != nil {
        return fmt.Errorf("failed to get locked users: %w", err)
    }

    if len(users) == 0 {
        return nil
    }

    log.Printf("Found %d accounts to auto-unlock", len(users))

    // Unlock each account
    for _, user := range users {
        if err := w.unlockUser(ctx, user); err != nil {
            log.Printf("Failed to unlock user %s: %v", user.ID, err)
            continue
        }
    }

    return nil
}

func (w *AutoUnlockWorker) unlockUser(ctx context.Context, user *entity.User) error {
    // Update user status
    if err := w.userRepo.UnlockAccount(ctx, user.ID); err != nil {
        return fmt.Errorf("failed to unlock account: %w", err)
    }

    log.Printf("Auto-unlocked account: %s", user.Email)

    // Publish unlock event
    unlockEvent := &event.UserUnlocked{
        EventID:       fmt.Sprintf("%d", time.Now().UnixNano()),
        EventType:    "USER_UNLOCKED",
        AggregateType: "USER",
        AggregateID:   user.ID.String(),
        PartitionKey:  user.ID.String(),
        OccurredAt:   time.Now().UTC().Format(time.RFC3339),
        Payload: event.UserUnlockedPayload{
            UserID:    user.ID.String(),
            Email:     user.Email,
            UnlockType: "AUTO",
            UnlockedBy: "SYSTEM",
        },
    }

    if err := w.eventPublisher.Publish(ctx, unlockEvent); err != nil {
        log.Printf("Failed to publish unlock event: %v", err)
    }

    return nil
}

func (w *AutoUnlockWorker) acquireLock(ctx context.Context, key string) (bool, error) {
    result, err := w.redisClient.SetNX(ctx, key, "locked", 1*time.Minute).Result()
    if err != nil {
        return false, err
    }
    return result, nil
}

func (w *AutoUnlockWorker) releaseLock(ctx context.Context, key string) {
    w.redisClient.Del(ctx, key)
}

// Stop stops the worker
func (w *AutoUnlockWorker) Stop() {
    close(w.stopCh)
}
```

---

## 4. Audit Log Cleanup Worker

### 4.1 Audit Log Cleanup Worker Implementation

```go
// internal/workers/audit_cleanup/worker.go
package auditcleanup

import (
    "context"
    "fmt"
    "log"
    "time"

    "github.com/redis/go-redis/v9"

    "github.com/ecommerce/auth-service/internal/common/config"
    "github.com/ecommerce/auth-service/internal/domain/port"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type AuditCleanupWorker struct {
    auditLogRepo port.AuditLogRepository
    redisClient  *redis.Client
    config       config.CleanupConfig
    stopCh       chan struct{}
}

func NewAuditCleanupWorker(
    auditLogRepo port.AuditLogRepository,
    redisClient *redis.Client,
    config config.CleanupConfig,
) *AuditCleanupWorker {
    return &AuditCleanupWorker{
        auditLogRepo: auditLogRepo,
        redisClient:  redisClient,
        config:       config,
        stopCh:       make(chan struct{}),
    }
}

// Start starts the audit log cleanup worker
func (w *AuditCleanupWorker) Start(ctx context.Context) error {
    log.Println("Starting audit cleanup worker")

    // Run immediately on start
    if err := w.cleanup(ctx); err != nil {
        log.Printf("Initial audit cleanup failed: %v", err)
    }

    // Schedule daily cleanup at 3 AM
    ticker := w.newDailyTicker(3, 0) // 3:00 AM
    defer ticker.Stop()

    for {
        select {
        case <-ctx.Done():
            log.Println("Audit cleanup worker stopped")
            return nil
        case <-ticker.C:
            if err := w.cleanup(ctx); err != nil {
                log.Printf("Audit cleanup failed: %v", err)
            }
        }
    }
}

func (w *AuditCleanupWorker) cleanup(ctx context.Context) error {
    log.Println("Starting audit log cleanup")

    // Acquire distributed lock
    lockKey := "lock:audit_cleanup"
    acquired, err := w.acquireLock(ctx, lockKey)
    if err != nil {
        return fmt.Errorf("failed to acquire lock: %w", err)
    }
    if !acquired {
        log.Println("Another instance is already cleaning up audit logs")
        return nil
    }
    defer w.releaseLock(ctx, lockKey)

    // Calculate cutoff time (6 months)
    cutoff := time.Now().Add(-w.config.AuditLogRetention)

    // Delete old audit logs
    deleted, err := w.auditLogRepo.DeleteOlderThan(ctx, cutoff)
    if err != nil {
        return fmt.Errorf("failed to delete old audit logs: %w", err)
    }

    log.Printf("Audit log cleanup completed: %d logs deleted", deleted)
    return nil
}

func (w *AuditCleanupWorker) acquireLock(ctx context.Context, key string) (bool, error) {
    result, err := w.redisClient.SetNX(ctx, key, "locked", 10*time.Minute).Result()
    if err != nil {
        return false, err
    }
    return result, nil
}

func (w *AuditCleanupWorker) releaseLock(ctx context.Context, key string) {
    w.redisClient.Del(ctx, key)
}

// newDailyTicker creates a ticker that fires at the specified hour and minute
func (w *AuditCleanupWorker) newDailyTicker(hour, minute int) *time.Ticker {
    now := time.Now()
    target := time.Date(
        now.Year(), now.Month(), now.Day(),
        hour, minute, 0, 0,
        now.Location(),
    )
    
    if target.Before(now) {
        target = target.Add(24 * time.Hour)
    }
    
    duration := target.Sub(now)
    ticker := time.NewTicker(duration)
    
    return ticker
}

// Stop stops the worker
func (w *AuditCleanupWorker) Stop() {
    close(w.stopCh)
}
```

---

## 5. Worker Runner

### 6.1 Worker Runner Implementation

```go
// internal/workers/runner.go
package workers

import (
    "context"
    "log"
    "sync"

    "github.com/ecommerce/auth-service/internal/domain/port"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

// Worker interface for all background workers
type Worker interface {
    Start(ctx context.Context) error
    Stop()
}

// Runner manages all background workers
type Runner struct {
    workers []Worker
    wg     sync.WaitGroup
}

// NewRunner creates a new worker runner
func NewRunner() *Runner {
    return &Runner{
        workers: make([]Worker, 0),
    }
}

// Register registers a worker to be started
func (r *Runner) Register(worker Worker) {
    r.workers = append(r.workers, worker)
}

// Start starts all registered workers
func (r *Runner) Start(ctx context.Context) {
    log.Printf("Starting %d workers", len(r.workers))

    for _, worker := range r.workers {
        r.wg.Add(1)
        go func(w Worker) {
            defer r.wg.Done()
            if err := w.Start(ctx); err != nil {
                log.Printf("Worker error: %v", err)
            }
        }(worker)
    }
}

// Stop stops all registered workers
func (r *Runner) Stop() {
    log.Println("Stopping all workers")
    for _, worker := range r.workers {
        worker.Stop()
    }
    r.wg.Wait()
    log.Println("All workers stopped")
}
```

### 6.2 Worker Initialization in Main

```go
// cmd/server/main.go (worker section)
package main

import (
    // ... imports
    
    // Workers
    "github.com/ecommerce/auth-service/internal/workers"
    "github.com/ecommerce/auth-service/internal/workers/tokencleanup"
    "github.com/ecommerce/auth-service/internal/workers/autounlock"
    "github.com/ecommerce/auth-service/internal/workers/auditcleanup"
    "github.com/ecommerce/auth-service/internal/workers/keyrotation"
    "github.com/ecommerce/auth-service/internal/workers/keyretirement"
)

func main() {
    // ... existing code ...
    
    // Initialize workers
    workerRunner := workers.NewRunner()
    
    // Token cleanup worker
    tokenCleanupWorker := tokencleanup.NewTokenCleanupWorker(
        refreshTokenRepo,
        redisClient,
        cfg.Cleanup,
    )
    workerRunner.Register(tokenCleanupWorker)
    
    // Auto-unlock worker
    autoUnlockWorker := autounlock.NewAutoUnlockWorker(
        userRepo,
        eventPublisher,
        redisClient,
        cfg.Cleanup,
    )
    workerRunner.Register(autoUnlockWorker)
    
    // Audit cleanup worker
    auditCleanupWorker := auditcleanup.NewAuditCleanupWorker(
        auditLogRepo,
        redisClient,
        cfg.Cleanup,
    )
    workerRunner.Register(auditCleanupWorker)
    
    // Key rotation worker
    keyRotationWorker := keyrotation.NewKeyRotationWorker(
        keyService,
        jwksService,
        redisClient,
        cfg.Keys,
    )
    workerRunner.Register(keyRotationWorker)
    
    // Key retirement worker
    keyRetirementWorker := keyretirement.NewKeyRetirementWorker(
        keyService,
        jwksService,
        auditService,
        redisClient,
        cfg.Keys,
    )
    workerRunner.Register(keyRetirementWorker)
    
    // Create context for workers
    workerCtx, workerCancel := context.WithCancel(context.Background())
    
    // Start workers
    workerRunner.Start(workerCtx)
    
    // ... existing server code ...
    
    // On shutdown
    workerCancel()
    workerRunner.Stop()
}
```

---

## 6. Testing

### 7.1 Token Cleanup Worker Test

```go
// test/unit/workers/token_cleanup_test.go
package workers

import (
    "context"
    "testing"
    "time"

    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
)

type MockRefreshTokenRepository struct {
    mock.Mock
}

func (m *MockRefreshTokenRepository) DeleteExpired(ctx context.Context, olderThan time.Time) (int64, error) {
    args := m.Called(ctx, olderThan)
    return args.Get(0).(int64), args.Error(1)
}

type MockRedisClient struct {
    mock.Mock
}

func (m *MockRedisClient) SetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.BoolCmd {
    args := m.Called(ctx, key, value, expiration)
    cmd := new(redis.BoolCmd)
    cmd.SetErr(args.Error(0))
    return cmd
}

func (m *MockRedisClient) Del(ctx context.Context, keys ...string) *redis.IntCmd {
    args := m.Called(ctx, keys)
    cmd := new(redis.IntCmd)
    cmd.SetErr(args.Error(0))
    return cmd
}

func TestTokenCleanupWorker_cleanup(t *testing.T) {
    ctx := context.Background()

    mockRepo := new(MockRefreshTokenRepository)
    mockRedis := new(MockRedisClient)

    worker := &TokenCleanupWorker{
        refreshTokenRepo: mockRepo,
        redisClient:      mockRedis,
    }

    mockRedis.On("SetNX", ctx, "lock:token_cleanup", "locked", 5*time.Minute).Return(true, nil)
    mockRedis.On("Del", ctx, "lock:token_cleanup").Return(1, nil)
    mockRepo.On("DeleteExpired", ctx, mock.AnythingOfType("time.Time")).Return(int64(100), nil)

    err := worker.cleanup(ctx)

    assert.NoError(t, err)
    mockRepo.AssertExpectations(t)
    mockRedis.AssertExpectations(t)
}
```

---

## 7. Checklist

### Token Cleanup Worker

- [x] Create `internal/workers/token_cleanup/worker.go`
- [x] Implement daily cleanup at 2 AM
- [x] Implement distributed lock
- [x] Implement token deletion
- [ ] Write unit tests

### Auto-Unlock Worker

- [x] Create `internal/workers/auto_unlock/worker.go`
- [x] Implement 5-minute interval check
- [x] Implement 30-minute lock duration check
- [x] Implement event publishing
- [ ] Write unit tests

### Audit Log Cleanup Worker

- [x] Create `internal/workers/audit_cleanup/worker.go`
- [x] Implement daily cleanup at 3 AM
- [x] Implement distributed lock
- [x] Implement log deletion
- [ ] Write unit tests

### Worker Runner

- [x] Create `internal/workers/runner.go`
- [x] Implement worker registration
- [x] Implement graceful startup/shutdown
- [x] Integrate with main.go

### Integration

- [ ] Test all workers end-to-end
- [ ] Verify distributed locking
- [ ] Verify cleanup scheduling
- [ ] Monitor worker performance

---

**Previous:** [module-10-event-publishing.md](module-10-event-publishing.md)  
**Next:** [module-12-testing.md](module-12-testing.md)
