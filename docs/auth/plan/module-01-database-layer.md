# Module 1: Database Layer

> **Module:** Infrastructure Layer  
> **File:** `module-01-database-layer.md`  
> **Phase:** 1 - Foundation  
> **Estimated Time:** 3-4 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Database Schema Design](#2-database-schema-design)
3. [Migration Files](#3-migration-files)
4. [Domain Entities](#4-domain-entities)
5. [Repository Interfaces](#5-repository-interfaces)
6. [Repository Implementations](#6-repository-implementations)
7. [Database Connection](#7-database-connection)
8. [Testing](#8-testing)
9. [Checklist](#9-checklist)

---

## 1. Overview

Module này thiết lập tầng Database cho Auth Service:
- Database schema với 5 bảng chính
- SQL migrations (up/down)
- Domain entities
- Repository interfaces (ports)
- Repository implementations

### Database Tables

| Table | Purpose |
|-------|---------|
| `users` | User credentials and status |
| `refresh_tokens` | Refresh token storage with rotation tracking |
| `jwt_keys` | JWKS keystore for key rotation |
| `audit_logs` | Security audit trail |

---

## 2. Database Schema Design

### 2.1 Users Table

```sql
-- Users table: Stores user credentials and account status
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    roles JSONB NOT NULL DEFAULT '["USER"]',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    locked_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT users_status_check CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'BANNED')
    ),
    CONSTRAINT users_failed_login_check CHECK (
        failed_login_attempts >= 0
    )
);

-- Indexes
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_locked_at ON users(locked_at) WHERE locked_at IS NOT NULL;
CREATE INDEX idx_users_created_at ON users(created_at);

-- Comment
COMMENT ON TABLE users IS 'User credentials and account status - Auth Service bounded context';
COMMENT ON COLUMN users.status IS 'PENDING_VERIFICATION: Chờ xác thực email, ACTIVE: Hoạt động, LOCKED: Tạm khóa, BANNED: Cấm vĩnh viễn';
```

### 2.2 Refresh Tokens Table

```sql
-- Refresh tokens table: Stores opaque refresh tokens with rotation tracking
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    family_id UUID NOT NULL DEFAULT gen_random_uuid(),
    replaced_by UUID,
    device_id VARCHAR(255) NOT NULL,
    device_info VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT refresh_tokens_token_hash_length CHECK (
        length(token_hash) >= 64 AND length(token_hash) <= 128
    )
);

-- Indexes
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_refresh_tokens_revoked_at ON refresh_tokens(revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX idx_refresh_tokens_device_id ON refresh_tokens(device_id);
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens(user_id, revoked_at, expires_at) 
    WHERE revoked_at IS NULL AND expires_at > NOW();

-- Comments
COMMENT ON TABLE refresh_tokens IS 'Refresh token storage with rotation tracking for Grace Period 10s';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of opaque refresh token';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Groups tokens from same login session';
COMMENT ON COLUMN refresh_tokens.replaced_by IS 'Points to new token that replaced this one';
```

### 2.3 JWT Keys Table

```sql
-- JWT keys table: Keystore for JWKS key rotation
CREATE TABLE jwt_keys (
    kid VARCHAR(100) PRIMARY KEY,
    public_key TEXT NOT NULL,
    private_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    
    -- Constraints
    CONSTRAINT jwt_keys_status_check CHECK (
        status IN ('ACTIVE', 'PHASE_OUT', 'RETIRED')
    )
);

-- Indexes
CREATE INDEX idx_jwt_keys_status ON jwt_keys(status);
CREATE INDEX idx_jwt_keys_created_at ON jwt_keys(created_at);
CREATE INDEX idx_jwt_keys_rotated_at ON jwt_keys(rotated_at) WHERE rotated_at IS NOT NULL;

-- Comments
COMMENT ON TABLE jwt_keys IS 'JWKS keystore - stores RSA key pairs for JWT signing';
COMMENT ON COLUMN jwt_keys.status IS 'ACTIVE: Current signing key, PHASE_OUT: Old key during rotation, RETIRED: Deprecated';
COMMENT ON COLUMN jwt_keys.kid IS 'Key ID used in JWT header';
```

### 2.4 Audit Logs Table

```sql
-- Audit logs table: Security audit trail
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id VARCHAR(255) NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    device_info VARCHAR(255),
    security_level VARCHAR(50) NOT NULL DEFAULT 'INFO',
    payload JSONB,
    log_category VARCHAR(50) NOT NULL DEFAULT 'USER',
    
    -- Constraints
    CONSTRAINT audit_logs_actor_type_check CHECK (
        actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'GUEST')
    ),
    CONSTRAINT audit_logs_security_level_check CHECK (
        security_level IN ('INFO', 'WARNING', 'CRITICAL')
    ),
    CONSTRAINT audit_logs_log_category_check CHECK (
        log_category IN ('USER', 'SYSTEM')
    )
);

-- Indexes
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_actor_type ON audit_logs(actor_type);
CREATE INDEX idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs(occurred_at DESC);
CREATE INDEX idx_audit_logs_security_level ON audit_logs(security_level);
CREATE INDEX idx_audit_logs_log_category ON audit_logs(log_category);
CREATE INDEX idx_audit_logs_actor_event ON audit_logs(actor_id, event_type, occurred_at DESC);
-- GIN index for JSONB payload queries
CREATE INDEX idx_audit_logs_payload ON audit_logs USING GIN (payload);

-- Comments
COMMENT ON TABLE audit_logs IS 'Security audit trail - records all sensitive events';
COMMENT ON COLUMN audit_logs.actor_type IS 'USER: Regular user, ADMIN: System admin, SYSTEM: Background jobs, GUEST: Anonymous';
COMMENT ON COLUMN audit_logs.security_level IS 'INFO: Normal, WARNING: Suspicious activity, CRITICAL: Security breach';
COMMENT ON COLUMN audit_logs.log_category IS 'USER: User data (6-month retention), SYSTEM: System events (permanent)';
```

### 2.6 Update Trigger for users.updated_at

```sql
-- Trigger to automatically update updated_at on users table
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON FUNCTION update_updated_at_column() IS 'Automatically updates the updated_at column on UPDATE';
```

---

## 3. Migration Files

### 3.1 Initial Schema Migration

```sql
-- internal/infrastructure/postgres/migrations/001_initial_schema.up.sql

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =====================================================
-- USERS TABLE
-- =====================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    roles JSONB NOT NULL DEFAULT '["USER"]',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    locked_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT users_status_check CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'BANNED')
    ),
    CONSTRAINT users_failed_login_check CHECK (
        failed_login_attempts >= 0
    )
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_locked_at ON users(locked_at) WHERE locked_at IS NOT NULL;
CREATE INDEX idx_users_created_at ON users(created_at);

-- =====================================================
-- REFRESH TOKENS TABLE
-- =====================================================
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    family_id UUID NOT NULL DEFAULT gen_random_uuid(),
    replaced_by UUID,
    device_id VARCHAR(255) NOT NULL,
    device_info VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_refresh_tokens_revoked_at ON refresh_tokens(revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX idx_refresh_tokens_device_id ON refresh_tokens(device_id);
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens(user_id, revoked_at, expires_at) 
    WHERE revoked_at IS NULL AND expires_at > NOW();

-- =====================================================
-- JWT KEYS TABLE
-- =====================================================
CREATE TABLE jwt_keys (
    kid VARCHAR(100) PRIMARY KEY,
    public_key TEXT NOT NULL,
    private_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_jwt_keys_status ON jwt_keys(status);
CREATE INDEX idx_jwt_keys_created_at ON jwt_keys(created_at);
CREATE INDEX idx_jwt_keys_rotated_at ON jwt_keys(rotated_at) WHERE rotated_at IS NOT NULL;

-- =====================================================
-- AUDIT LOGS TABLE
-- =====================================================
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id VARCHAR(255) NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    device_info VARCHAR(255),
    security_level VARCHAR(50) NOT NULL DEFAULT 'INFO',
    payload JSONB,
    log_category VARCHAR(50) NOT NULL DEFAULT 'USER',
    
    CONSTRAINT audit_logs_actor_type_check CHECK (
        actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'GUEST')
    ),
    CONSTRAINT audit_logs_security_level_check CHECK (
        security_level IN ('INFO', 'WARNING', 'CRITICAL')
    ),
    CONSTRAINT audit_logs_log_category_check CHECK (
        log_category IN ('USER', 'SYSTEM')
    )
);

CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_actor_type ON audit_logs(actor_type);
CREATE INDEX idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs(occurred_at DESC);
CREATE INDEX idx_audit_logs_security_level ON audit_logs(security_level);
CREATE INDEX idx_audit_logs_log_category ON audit_logs(log_category);
CREATE INDEX idx_audit_logs_actor_event ON audit_logs(actor_id, event_type, occurred_at DESC);
CREATE INDEX idx_audit_logs_payload ON audit_logs USING GIN (payload);

-- =====================================================
-- TRIGGERS
-- =====================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- =====================================================
-- COMMENTS
-- =====================================================
COMMENT ON TABLE users IS 'User credentials and account status - Auth Service bounded context';
COMMENT ON COLUMN users.status IS 'PENDING_VERIFICATION: Chờ xác thực email, ACTIVE: Hoạt động, LOCKED: Tạm khóa, BANNED: Cấm vĩnh viễn';

COMMENT ON TABLE refresh_tokens IS 'Refresh token storage with rotation tracking for Grace Period 10s';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of opaque refresh token';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Groups tokens from same login session';

COMMENT ON TABLE jwt_keys IS 'JWKS keystore - stores RSA key pairs for JWT signing';

COMMENT ON TABLE audit_logs IS 'Security audit trail - records all sensitive events';
```

### 3.2 Rollback Migration

```sql
-- internal/infrastructure/postgres/migrations/001_initial_schema.down.sql

-- Drop triggers
DROP TRIGGER IF EXISTS update_users_updated_at ON users;
DROP FUNCTION IF EXISTS update_updated_at_column();

-- Drop tables in reverse order (respecting foreign keys)
DROP TABLE IF EXISTS audit_logs;
DROP TABLE IF EXISTS jwt_keys;
DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS users;

-- Drop extension
DROP EXTENSION IF EXISTS "pgcrypto";
```

---

## 4. Domain Entities

### 4.1 User Entity

```go
// internal/domain/entity/user.go
package entity

import (
    "time"

    "github.com/google/uuid"
)

type UserStatus string

const (
    StatusPendingVerification UserStatus = "PENDING_VERIFICATION"
    StatusActive             UserStatus = "ACTIVE"
    StatusLocked            UserStatus = "LOCKED"
    StatusBanned            UserStatus = "BANNED"
)

type User struct {
    ID                   uuid.UUID   `json:"id"`
    Email                string      `json:"email"`
    PasswordHash         string      `json:"-"`
    Roles                []string    `json:"roles"`
    Status               UserStatus  `json:"status"`
    LockedAt             *time.Time  `json:"locked_at,omitempty"`
    FailedLoginAttempts   int         `json:"-"`
    EmailVerifiedAt       *time.Time `json:"email_verified_at,omitempty"`
    CreatedAt            time.Time   `json:"created_at"`
    UpdatedAt            time.Time   `json:"updated_at"`
}

func (u *User) IsActive() bool {
    return u.Status == StatusActive
}

func (u *User) IsLocked() bool {
    return u.Status == StatusLocked
}

func (u *User) IsBanned() bool {
    return u.Status == StatusBanned
}

func (u *User) IsPendingVerification() bool {
    return u.Status == StatusPendingVerification
}

func (u *User) IsEmailVerified() bool {
    return u.EmailVerifiedAt != nil
}

func (u *User) CanLogin() bool {
    return u.IsActive() && u.IsEmailVerified()
}

func (u *User) CanBeUnlocked() bool {
    if u.LockedAt == nil {
        return false
    }
    return time.Since(*u.LockedAt) >= 30*time.Minute
}

func (u *User) SetLocked() {
    u.Status = StatusLocked
    now := time.Now()
    u.LockedAt = &now
    u.FailedLoginAttempts = 0
}

func (u *User) SetActive() {
    u.Status = StatusActive
    u.LockedAt = nil
    u.FailedLoginAttempts = 0
}

func (u *User) SetBanned() {
    u.Status = StatusBanned
}

func (u *User) SetEmailVerified() {
    u.Status = StatusActive
    now := time.Now()
    u.EmailVerifiedAt = &now
}

func (u *User) IncrementFailedAttempts() {
    u.FailedLoginAttempts++
    if u.FailedLoginAttempts >= 5 {
        u.SetLocked()
    }
}

func (u *User) ResetFailedAttempts() {
    u.FailedLoginAttempts = 0
}
```

### 4.2 RefreshToken Entity

```go
// internal/domain/entity/refresh_token.go
package entity

import (
    "time"

    "github.com/google/uuid"
)

type RefreshToken struct {
    ID          uuid.UUID  `json:"id"`
    UserID      uuid.UUID  `json:"user_id"`
    TokenHash   string     `json:"-"`
    FamilyID    uuid.UUID  `json:"family_id"`
    ReplacedBy  *uuid.UUID `json:"replaced_by,omitempty"`
    DeviceID    string     `json:"device_id"`
    DeviceInfo  string     `json:"device_info,omitempty"`
    ExpiresAt   time.Time  `json:"expires_at"`
    RevokedAt   *time.Time `json:"revoked_at,omitempty"`
    CreatedAt   time.Time  `json:"created_at"`
}

func (rt *RefreshToken) IsValid() bool {
    return rt.RevokedAt == nil && time.Now().Before(rt.ExpiresAt)
}

func (rt *RefreshToken) IsExpired() bool {
    return time.Now().After(rt.ExpiresAt)
}

func (rt *RefreshToken) IsRevoked() bool {
    return rt.RevokedAt != nil
}

func (rt *RefreshToken) Revoke() {
    now := time.Now()
    rt.RevokedAt = &now
}

func (rt *RefreshToken) WithinGracePeriod() bool {
    if rt.RevokedAt == nil {
        return false
    }
    gracePeriod := 10 * time.Second
    return time.Since(*rt.RevokedAt) <= gracePeriod
}

func (rt *RefreshToken) GracePeriodExpiresAt() time.Time {
    if rt.RevokedAt == nil {
        return time.Time{}
    }
    return rt.RevokedAt.Add(10 * time.Second)
}
```

### 4.3 JWTKey Entity

```go
// internal/domain/entity/jwt_key.go
package entity

import (
    "time"
)

type KeyStatus string

const (
    KeyStatusActive   KeyStatus = "ACTIVE"
    KeyStatusPhaseOut KeyStatus = "PHASE_OUT"
    KeyStatusRetired  KeyStatus = "RETIRED"
)

type JWTKey struct {
    Kid         string     `json:"kid"`
    PublicKey   string     `json:"public_key"`
    PrivateKey  string     `json:"-"`
    Status      KeyStatus  `json:"status"`
    CreatedAt   time.Time  `json:"created_at"`
    RotatedAt   *time.Time `json:"rotated_at,omitempty"`
    ExpiresAt   *time.Time `json:"expires_at,omitempty"`
}

func (k *JWTKey) IsActive() bool {
    return k.Status == KeyStatusActive
}

func (k *JWTKey) IsPhaseOut() bool {
    return k.Status == KeyStatusPhaseOut
}

func (k *JWTKey) IsRetired() bool {
    return k.Status == KeyStatusRetired
}

func (k *JWTKey) CanSign() bool {
    return k.IsActive()
}

func (k *JWTKey) CanVerify() bool {
    return k.IsActive() || k.IsPhaseOut()
}

func (k *JWTKey) SetPhaseOut() {
    k.Status = KeyStatusPhaseOut
    now := time.Now()
    k.RotatedAt = &now
}

func (k *JWTKey) SetRetired() {
    k.Status = KeyStatusRetired
}
```

### 4.4 AuditLog Entity

```go
// internal/domain/entity/audit_log.go
package entity

import (
    "encoding/json"
    "time"
)

type ActorType string

const (
    ActorUser   ActorType = "USER"
    ActorAdmin  ActorType = "ADMIN"
    ActorSystem ActorType = "SYSTEM"
    ActorGuest  ActorType = "GUEST"
)

type SecurityLevel string

const (
    SecurityLevelInfo     SecurityLevel = "INFO"
    SecurityLevelWarning SecurityLevel = "WARNING"
    SecurityLevelCritical SecurityLevel = "CRITICAL"
)

type LogCategory string

const (
    LogCategoryUser   LogCategory = "USER"
    LogCategorySystem LogCategory = "SYSTEM"
)

type AuditLog struct {
    ID             string          `json:"id"`
    ActorID        string          `json:"actor_id"`
    ActorType      ActorType       `json:"actor_type"`
    EventType      string          `json:"event_type"`
    OccurredAt     time.Time       `json:"occurred_at"`
    IPAddress      string          `json:"ip_address,omitempty"`
    DeviceID       string          `json:"device_id,omitempty"`
    DeviceInfo     string          `json:"device_info,omitempty"`
    SecurityLevel  SecurityLevel   `json:"security_level"`
    Payload        json.RawMessage `json:"payload,omitempty"`
    LogCategory    LogCategory     `json:"log_category"`
}

func NewAuditLog(
    actorID string,
    actorType ActorType,
    eventType string,
    securityLevel SecurityLevel,
    logCategory LogCategory,
) *AuditLog {
    return &AuditLog{
        ID:            generateUUID(),
        ActorID:       actorID,
        ActorType:     actorType,
        EventType:     eventType,
        OccurredAt:    time.Now().UTC(),
        SecurityLevel: securityLevel,
        LogCategory:   logCategory,
    }
}

func (a *AuditLog) WithIP(ip string) *AuditLog {
    a.IPAddress = ip
    return a
}

func (a *AuditLog) WithDevice(deviceID, deviceInfo string) *AuditLog {
    a.DeviceID = deviceID
    a.DeviceInfo = deviceInfo
    return a
}

func (a *AuditLog) WithPayload(payload interface{}) *AuditLog {
    if data, err := json.Marshal(payload); err == nil {
        a.Payload = data
    }
    return a
}
```

---

## 5. Repository Interfaces

### 5.1 User Repository Interface

```go
// internal/domain/port/user_repository.go
package port

import (
    "context"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

type UserRepository interface {
    // Create creates a new user
    Create(ctx context.Context, user *entity.User) error

    // GetByID retrieves a user by ID
    GetByID(ctx context.Context, id uuid.UUID) (*entity.User, error)

    // GetByEmail retrieves a user by email
    GetByEmail(ctx context.Context, email string) (*entity.User, error)

    // Update updates a user
    Update(ctx context.Context, user *entity.User) error

    // UpdateStatus updates only the status field
    UpdateStatus(ctx context.Context, id uuid.UUID, status entity.UserStatus) error

    // LockAccount locks a user account
    LockAccount(ctx context.Context, id uuid.UUID) error

    // UnlockAccount unlocks a user account
    UnlockAccount(ctx context.Context, id uuid.UUID) error

    // BanAccount bans a user account
    BanAccount(ctx context.Context, id uuid.UUID) error

    // GetLockedUsers retrieves users that have been locked for more than the specified duration
    GetLockedUsers(ctx context.Context, lockDuration time.Duration, limit int) ([]*entity.User, error)

    // Delete deletes a user
    Delete(ctx context.Context, id uuid.UUID) error
}
```

### 5.2 Refresh Token Repository Interface

```go
// internal/domain/port/refresh_token_repository.go
package port

import (
    "context"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

type RefreshTokenRepository interface {
    // Create creates a new refresh token
    Create(ctx context.Context, token *entity.RefreshToken) error

    // GetByID retrieves a refresh token by ID
    GetByID(ctx context.Context, id uuid.UUID) (*entity.RefreshToken, error)

    // GetByHash retrieves a refresh token by its hash
    GetByHash(ctx context.Context, hash string) (*entity.RefreshToken, error)

    // GetActiveByUserID retrieves all active refresh tokens for a user
    GetActiveByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.RefreshToken, error)

    // Revoke marks a refresh token as revoked
    Revoke(ctx context.Context, id uuid.UUID) error

    // RevokeByHash marks a refresh token as revoked by its hash
    RevokeByHash(ctx context.Context, hash string) error

    // RevokeAllByUserID revokes all refresh tokens for a user
    RevokeAllByUserID(ctx context.Context, userID uuid.UUID) error

    // DeleteExpired deletes all expired refresh tokens older than the retention period
    DeleteExpired(ctx context.Context, olderThan time.Time) (int64, error)

    // UpdateReplacedBy updates the replaced_by field
    UpdateReplacedBy(ctx context.Context, oldID, newID uuid.UUID) error
}
```

### 5.3 JWT Key Repository Interface

```go
// internal/domain/port/jwt_key_repository.go
package port

import (
    "context"
    "time"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

type JWTKeyRepository interface {
    // Create creates a new JWT key
    Create(ctx context.Context, key *entity.JWTKey) error

    // GetActive retrieves the active JWT key
    GetActive(ctx context.Context) (*entity.JWTKey, error)

    // GetAllForSigning retrieves all keys that can be used for signing (ACTIVE and PHASE_OUT)
    GetAllForSigning(ctx context.Context) ([]*entity.JWTKey, error)

    // GetAllForVerification retrieves all keys that can be used for verification
    GetAllForVerification(ctx context.Context) ([]*entity.JWTKey, error)

    // GetByKid retrieves a key by its ID
    GetByKid(ctx context.Context, kid string) (*entity.JWTKey, error)

    // UpdateStatus updates the status of a key
    UpdateStatus(ctx context.Context, kid string, status entity.KeyStatus) error

    // GetPhaseOutKeys retrieves keys that can be retired
    GetPhaseOutKeys(ctx context.Context, olderThan time.Time) ([]*entity.JWTKey, error)

    // Delete deletes a key
    Delete(ctx context.Context, kid string) error
}
```

### 5.4 Audit Log Repository Interface

```go
// internal/domain/port/audit_log_repository.go
package port

import (
    "context"
    "time"

    "github.com/ecommerce/uuid"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

type AuditLogRepository interface {
    // Create creates a new audit log entry
    Create(ctx context.Context, log *entity.AuditLog) error

    // GetByActorID retrieves audit logs by actor ID
    GetByActorID(ctx context.Context, actorID string, limit, offset int) ([]*entity.AuditLog, error)

    // GetByEventType retrieves audit logs by event type
    GetByEventType(ctx context.Context, eventType string, limit, offset int) ([]*entity.AuditLog, error)

    // GetByTimeRange retrieves audit logs within a time range
    GetByTimeRange(ctx context.Context, from, to time.Time, limit, offset int) ([]*entity.AuditLog, error)

    // GetLoginHistory retrieves login history for a user
    GetLoginHistory(ctx context.Context, userID uuid.UUID, limit int) ([]*entity.AuditLog, error)

    // GetByFilters retrieves audit logs with multiple filters
    GetByFilters(ctx context.Context, filters AuditLogFilters, limit, offset int) ([]*entity.AuditLog, int, error)

    // DeleteOlderThan deletes audit logs older than the specified time
    DeleteOlderThan(ctx context.Context, olderThan time.Time) (int64, error)
}

type AuditLogFilters struct {
    ActorID       string
    ActorType     entity.ActorType
    EventType     string
    SecurityLevel entity.SecurityLevel
    From          time.Time
    To            time.Time
    LogCategory   entity.LogCategory
}
```

---

## 6. Repository Implementations

### 6.1 Database Connection

```go
// internal/infrastructure/postgres/connection.go
package postgres

import (
    "context"
    "fmt"
    "time"

    "github.com/jackc/pgx/v5/pgxpool"

    "github.com/ecommerce/auth-service/internal/common/config"
)

type Connection struct {
    pool *pgxpool.Pool
}

func NewConnection(cfg config.DatabaseConfig) (*Connection, error) {
    dsn := fmt.Sprintf(
        "postgres://%s:%s@%s:%d/%s?sslmode=%s",
        cfg.Username,
        cfg.Password,
        cfg.Host,
        cfg.Port,
        cfg.Name,
        cfg.SSLMode,
    )

    poolConfig, err := pgxpool.ParseConfig(dsn)
    if err != nil {
        return nil, fmt.Errorf("failed to parse config: %w", err)
    }

    poolConfig.MaxConns = int32(cfg.MaxOpenConns)
    poolConfig.MinConns = int32(cfg.MaxIdleConns)
    poolConfig.MaxConnLifetime = cfg.ConnMaxLifetime

    ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
    defer cancel()

    pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
    if err != nil {
        return nil, fmt.Errorf("failed to create pool: %w", err)
    }

    if err := pool.Ping(ctx); err != nil {
        return nil, fmt.Errorf("failed to ping database: %w", err)
    }

    return &Connection{pool: pool}, nil
}

func (c *Connection) Pool() *pgxpool.Pool {
    return c.pool
}

func (c *Connection) Ping(ctx context.Context) error {
    return c.pool.Ping(ctx)
}

func (c *Connection) Close() {
    c.pool.Close()
}

// Tx represents a database transaction
type Tx struct {
    tx *pgxpool.Tx
}

func (c *Connection) BeginTx(ctx context.Context) (*Tx, error) {
    tx, err := c.pool.Begin(ctx)
    if err != nil {
        return nil, err
    }
    return &Tx{tx: tx}, nil
}

func (t *Tx) Commit(ctx context.Context) error {
    return t.tx.Commit(ctx)
}

func (t *Tx) Rollback(ctx context.Context) error {
    return t.tx.Rollback(ctx)
}

func (t *Tx) QueryRow(ctx context.Context, sql string, args ...interface{}) interface {
    QueryRow(ctx context.Context, sql string, args ...interface{}) interface {
        Query(ctx context.Context, sql string, args ...interface{}) (interface {
            Close()
            Next() bool
            Scan(dest ...interface{}) error
        }, error)
        Scan(dest ...interface{}) error
    }
}
```

### 6.2 User Repository Implementation

```go
// internal/infrastructure/postgres/user_repo.go
package postgres

import (
    "context"
    "encoding/json"
    "errors"
    "fmt"
    "time"

    "github.com/google/uuid"
    "github.com/jackc/pgx/v5"
    "github.com/jackc/pgx/v5/pgxpool"

    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.UserRepository = (*UserRepository)(nil)

type UserRepository struct {
    pool *pgxpool.Pool
}

func NewUserRepository(pool *pgxpool.Pool) *UserRepository {
    return &UserRepository{pool: pool}
}

func (r *UserRepository) Create(ctx context.Context, user *entity.User) error {
    rolesJSON, err := json.Marshal(user.Roles)
    if err != nil {
        return fmt.Errorf("failed to marshal roles: %w", err)
    }

    sql := `
        INSERT INTO users (id, email, password_hash, roles, status, locked_at, 
                          failed_login_attempts, email_verified_at, created_at, updated_at)
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
    `

    _, err = r.pool.Exec(ctx, sql,
        user.ID,
        user.Email,
        user.PasswordHash,
        rolesJSON,
        user.Status,
        user.LockedAt,
        user.FailedLoginAttempts,
        user.EmailVerifiedAt,
        user.CreatedAt,
        user.UpdatedAt,
    )
    if err != nil {
        return fmt.Errorf("failed to create user: %w", err)
    }

    return nil
}

func (r *UserRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.User, error) {
    sql := `
        SELECT id, email, password_hash, roles, status, locked_at, 
               failed_login_attempts, email_verified_at, created_at, updated_at
        FROM users
        WHERE id = $1
    `

    user, err := r.scanUser(r.pool.QueryRow(ctx, sql, id))
    if err != nil {
        if errors.Is(err, pgx.ErrNoRows) {
            return nil, port.ErrNotFound
        }
        return nil, fmt.Errorf("failed to get user by id: %w", err)
    }

    return user, nil
}

func (r *UserRepository) GetByEmail(ctx context.Context, email string) (*entity.User, error) {
    sql := `
        SELECT id, email, password_hash, roles, status, locked_at, 
               failed_login_attempts, email_verified_at, created_at, updated_at
        FROM users
        WHERE email = $1
    `

    user, err := r.scanUser(r.pool.QueryRow(ctx, sql, email))
    if err != nil {
        if errors.Is(err, pgx.ErrNoRows) {
            return nil, port.ErrNotFound
        }
        return nil, fmt.Errorf("failed to get user by email: %w", err)
    }

    return user, nil
}

func (r *UserRepository) Update(ctx context.Context, user *entity.User) error {
    rolesJSON, err := json.Marshal(user.Roles)
    if err != nil {
        return fmt.Errorf("failed to marshal roles: %w", err)
    }

    sql := `
        UPDATE users 
        SET email = $2, password_hash = $3, roles = $4, status = $5, 
            locked_at = $6, failed_login_attempts = $7, email_verified_at = $8
        WHERE id = $1
    `

    _, err = r.pool.Exec(ctx, sql,
        user.ID,
        user.Email,
        user.PasswordHash,
        rolesJSON,
        user.Status,
        user.LockedAt,
        user.FailedLoginAttempts,
        user.EmailVerifiedAt,
    )
    if err != nil {
        return fmt.Errorf("failed to update user: %w", err)
    }

    return nil
}

func (r *UserRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status entity.UserStatus) error {
    sql := `UPDATE users SET status = $2 WHERE id = $1`

    result, err := r.pool.Exec(ctx, sql, id, status)
    if err != nil {
        return fmt.Errorf("failed to update status: %w", err)
    }

    if result.RowsAffected() == 0 {
        return port.ErrNotFound
    }

    return nil
}

func (r *UserRepository) LockAccount(ctx context.Context, id uuid.UUID) error {
    sql := `
        UPDATE users 
        SET status = 'LOCKED', locked_at = NOW(), failed_login_attempts = 0
        WHERE id = $1 AND status = 'ACTIVE'
    `

    result, err := r.pool.Exec(ctx, sql, id)
    if err != nil {
        return fmt.Errorf("failed to lock account: %w", err)
    }

    if result.RowsAffected() == 0 {
        return port.ErrNotFound
    }

    return nil
}

func (r *UserRepository) UnlockAccount(ctx context.Context, id uuid.UUID) error {
    sql := `
        UPDATE users 
        SET status = 'ACTIVE', locked_at = NULL, failed_login_attempts = 0
        WHERE id = $1 AND status = 'LOCKED'
    `

    result, err := r.pool.Exec(ctx, sql, id)
    if err != nil {
        return fmt.Errorf("failed to unlock account: %w", err)
    }

    if result.RowsAffected() == 0 {
        return port.ErrNotFound
    }

    return nil
}

func (r *UserRepository) BanAccount(ctx context.Context, id uuid.UUID) error {
    sql := `UPDATE users SET status = 'BANNED' WHERE id = $1`

    result, err := r.pool.Exec(ctx, sql, id)
    if err != nil {
        return fmt.Errorf("failed to ban account: %w", err)
    }

    if result.RowsAffected() == 0 {
        return port.ErrNotFound
    }

    return nil
}

func (r *UserRepository) GetLockedUsers(ctx context.Context, lockDuration time.Duration, limit int) ([]*entity.User, error) {
    sql := `
        SELECT id, email, password_hash, roles, status, locked_at, 
               failed_login_attempts, email_verified_at, created_at, updated_at
        FROM users
        WHERE status = 'LOCKED' AND locked_at <= $1
        ORDER BY locked_at ASC
        LIMIT $2
    `

    threshold := time.Now().Add(-lockDuration)

    rows, err := r.pool.Query(ctx, sql, threshold, limit)
    if err != nil {
        return nil, fmt.Errorf("failed to get locked users: %w", err)
    }
    defer rows.Close()

    var users []*entity.User
    for rows.Next() {
        user, err := r.scanUserRow(rows)
        if err != nil {
            return nil, err
        }
        users = append(users, user)
    }

    return users, nil
}

func (r *UserRepository) Delete(ctx context.Context, id uuid.UUID) error {
    sql := `DELETE FROM users WHERE id = $1`

    result, err := r.pool.Exec(ctx, sql, id)
    if err != nil {
        return fmt.Errorf("failed to delete user: %w", err)
    }

    if result.RowsAffected() == 0 {
        return port.ErrNotFound
    }

    return nil
}

func (r *UserRepository) scanUser(row interface {
    Scan(dest ...interface{}) error
}) (*entity.User, error) {
    var user entity.User
    var rolesJSON []byte

    err := row.Scan(
        &user.ID,
        &user.Email,
        &user.PasswordHash,
        &rolesJSON,
        &user.Status,
        &user.LockedAt,
        &user.FailedLoginAttempts,
        &user.EmailVerifiedAt,
        &user.CreatedAt,
        &user.UpdatedAt,
    )
    if err != nil {
        return nil, err
    }

    if err := json.Unmarshal(rolesJSON, &user.Roles); err != nil {
        return nil, fmt.Errorf("failed to unmarshal roles: %w", err)
    }

    return &user, nil
}

func (r *UserRepository) scanUserRow(rows interface {
    Next() bool
    Scan(dest ...interface{}) error
}) (*entity.User, error) {
    var user entity.User
    var rolesJSON []byte

    err := rows.Scan(
        &user.ID,
        &user.Email,
        &user.PasswordHash,
        &rolesJSON,
        &user.Status,
        &user.LockedAt,
        &user.FailedLoginAttempts,
        &user.EmailVerifiedAt,
        &user.CreatedAt,
        &user.UpdatedAt,
    )
    if err != nil {
        return nil, err
    }

    if err := json.Unmarshal(rolesJSON, &user.Roles); err != nil {
        return nil, fmt.Errorf("failed to unmarshal roles: %w", err)
    }

    return &user, nil
}
```

### 6.3 Refresh Token Repository Implementation

```go
// internal/infrastructure/postgres/refresh_token_repo.go
package postgres

import (
    "context"
    "fmt"
    "time"

    "github.com/google/uuid"
    "github.com/jackc/pgx/v5"
    "github.com/jackc/pgx/v5/pgxpool"

    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.RefreshTokenRepository = (*RefreshTokenRepository)(nil)

type RefreshTokenRepository struct {
    pool *pgxpool.Pool
}

func NewRefreshTokenRepository(pool *pgxpool.Pool) *RefreshTokenRepository {
    return &RefreshTokenRepository{pool: pool}
}

func (r *RefreshTokenRepository) Create(ctx context.Context, token *entity.RefreshToken) error {
    sql := `
        INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, replaced_by, 
                                   device_id, device_info, expires_at, revoked_at, created_at)
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
    `

    _, err := r.pool.Exec(ctx, sql,
        token.ID,
        token.UserID,
        token.TokenHash,
        token.FamilyID,
        token.ReplacedBy,
        token.DeviceID,
        token.DeviceInfo,
        token.ExpiresAt,
        token.RevokedAt,
        token.CreatedAt,
    )
    if err != nil {
        return fmt.Errorf("failed to create refresh token: %w", err)
    }

    return nil
}

func (r *RefreshTokenRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.RefreshToken, error) {
    sql := `
        SELECT id, user_id, token_hash, family_id, replaced_by, device_id, 
               device_info, expires_at, revoked_at, created_at
        FROM refresh_tokens
        WHERE id = $1
    `

    token, err := r.scanRefreshToken(r.pool.QueryRow(ctx, sql, id))
    if err != nil {
        if errors.Is(err, pgx.ErrNoRows) {
            return nil, port.ErrNotFound
        }
        return nil, fmt.Errorf("failed to get refresh token by id: %w", err)
    }

    return token, nil
}

func (r *RefreshTokenRepository) GetByHash(ctx context.Context, hash string) (*entity.RefreshToken, error) {
    sql := `
        SELECT id, user_id, token_hash, family_id, replaced_by, device_id, 
               device_info, expires_at, revoked_at, created_at
        FROM refresh_tokens
        WHERE token_hash = $1
    `

    token, err := r.scanRefreshToken(r.pool.QueryRow(ctx, sql, hash))
    if err != nil {
        if errors.Is(err, pgx.ErrNoRows) {
            return nil, port.ErrNotFound
        }
        return nil, fmt.Errorf("failed to get refresh token by hash: %w", err)
    }

    return token, nil
}

func (r *RefreshTokenRepository) GetActiveByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.RefreshToken, error) {
    sql := `
        SELECT id, user_id, token_hash, family_id, replaced_by, device_id, 
               device_info, expires_at, revoked_at, created_at
        FROM refresh_tokens
        WHERE user_id = $1 AND revoked_at IS NULL AND expires_at > NOW()
        ORDER BY created_at DESC
    `

    rows, err := r.pool.Query(ctx, sql, userID)
    if err != nil {
        return nil, fmt.Errorf("failed to get active tokens: %w", err)
    }
    defer rows.Close()

    var tokens []*entity.RefreshToken
    for rows.Next() {
        token, err := r.scanRefreshTokenRow(rows)
        if err != nil {
            return nil, err
        }
        tokens = append(tokens, token)
    }

    return tokens, nil
}

func (r *RefreshTokenRepository) Revoke(ctx context.Context, id uuid.UUID) error {
    sql := `UPDATE refresh_tokens SET revoked_at = NOW() WHERE id = $1`

    result, err := r.pool.Exec(ctx, sql, id)
    if err != nil {
        return fmt.Errorf("failed to revoke token: %w", err)
    }

    if result.RowsAffected() == 0 {
        return port.ErrNotFound
    }

    return nil
}

func (r *RefreshTokenRepository) RevokeByHash(ctx context.Context, hash string) error {
    sql := `UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = $1`

    _, err := r.pool.Exec(ctx, sql, hash)
    if err != nil {
        return fmt.Errorf("failed to revoke token by hash: %w", err)
    }

    return nil
}

func (r *RefreshTokenRepository) RevokeAllByUserID(ctx context.Context, userID uuid.UUID) error {
    sql := `UPDATE refresh_tokens SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL`

    _, err := r.pool.Exec(ctx, sql, userID)
    if err != nil {
        return fmt.Errorf("failed to revoke all tokens: %w", err)
    }

    return nil
}

func (r *RefreshTokenRepository) DeleteExpired(ctx context.Context, olderThan time.Time) (int64, error) {
    sql := `
        DELETE FROM refresh_tokens 
        WHERE (expires_at < $1 AND expires_at < NOW() - INTERVAL '7 days')
           OR (revoked_at IS NOT NULL AND revoked_at < $1)
    `

    result, err := r.pool.Exec(ctx, sql, olderThan)
    if err != nil {
        return 0, fmt.Errorf("failed to delete expired tokens: %w", err)
    }

    return result.RowsAffected(), nil
}

func (r *RefreshTokenRepository) UpdateReplacedBy(ctx context.Context, oldID, newID uuid.UUID) error {
    sql := `UPDATE refresh_tokens SET replaced_by = $2 WHERE id = $1`

    _, err := r.pool.Exec(ctx, sql, oldID, newID)
    if err != nil {
        return fmt.Errorf("failed to update replaced_by: %w", err)
    }

    return nil
}

func (r *RefreshTokenRepository) scanRefreshToken(row interface {
    Scan(dest ...interface{}) error
}) (*entity.RefreshToken, error) {
    var token entity.RefreshToken
    err := row.Scan(
        &token.ID,
        &token.UserID,
        &token.TokenHash,
        &token.FamilyID,
        &token.ReplacedBy,
        &token.DeviceID,
        &token.DeviceInfo,
        &token.ExpiresAt,
        &token.RevokedAt,
        &token.CreatedAt,
    )
    if err != nil {
        return nil, err
    }
    return &token, nil
}

func (r *RefreshTokenRepository) scanRefreshTokenRow(rows interface {
    Next() bool
    Scan(dest ...interface{}) error
}) (*entity.RefreshToken, error) {
    var token entity.RefreshToken
    err := rows.Scan(
        &token.ID,
        &token.UserID,
        &token.TokenHash,
        &token.FamilyID,
        &token.ReplacedBy,
        &token.DeviceID,
        &token.DeviceInfo,
        &token.ExpiresAt,
        &token.RevokedAt,
        &token.CreatedAt,
    )
    if err != nil {
        return nil, err
    }
    return &token, nil
}
```

### 6.4 Audit Log Repository Implementation

```go
// internal/infrastructure/postgres/audit_log_repo.go
package postgres

import (
    "context"
    "encoding/json"
    "fmt"
    "strings"
    "time"

    "github.com/jackc/pgx/v5/pgxpool"

    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.AuditLogRepository = (*AuditLogRepository)(nil)

type AuditLogRepository struct {
    pool *pgxpool.Pool
}

func NewAuditLogRepository(pool *pgxpool.Pool) *AuditLogRepository {
    return &AuditLogRepository{pool: pool}
}

func (r *AuditLogRepository) Create(ctx context.Context, log *entity.AuditLog) error {
    sql := `
        INSERT INTO audit_logs (id, actor_id, actor_type, event_type, occurred_at, 
                               ip_address, device_id, device_info, security_level, payload, log_category)
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
    `

    _, err := r.pool.Exec(ctx, sql,
        log.ID,
        log.ActorID,
        log.ActorType,
        log.EventType,
        log.OccurredAt,
        log.IPAddress,
        log.DeviceID,
        log.DeviceInfo,
        log.SecurityLevel,
        log.Payload,
        log.LogCategory,
    )
    if err != nil {
        return fmt.Errorf("failed to create audit log: %w", err)
    }

    return nil
}

func (r *AuditLogRepository) GetByActorID(ctx context.Context, actorID string, limit, offset int) ([]*entity.AuditLog, error) {
    sql := `
        SELECT id, actor_id, actor_type, event_type, occurred_at, 
               ip_address, device_id, device_info, security_level, payload, log_category
        FROM audit_logs
        WHERE actor_id = $1
        ORDER BY occurred_at DESC
        LIMIT $2 OFFSET $3
    `

    rows, err := r.pool.Query(ctx, sql, actorID, limit, offset)
    if err != nil {
        return nil, fmt.Errorf("failed to get audit logs by actor: %w", err)
    }
    defer rows.Close()

    return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetByEventType(ctx context.Context, eventType string, limit, offset int) ([]*entity.AuditLog, error) {
    sql := `
        SELECT id, actor_id, actor_type, event_type, occurred_at, 
               ip_address, device_id, device_info, security_level, payload, log_category
        FROM audit_logs
        WHERE event_type = $1
        ORDER BY occurred_at DESC
        LIMIT $2 OFFSET $3
    `

    rows, err := r.pool.Query(ctx, sql, eventType, limit, offset)
    if err != nil {
        return nil, fmt.Errorf("failed to get audit logs by event type: %w", err)
    }
    defer rows.Close()

    return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetByTimeRange(ctx context.Context, from, to time.Time, limit, offset int) ([]*entity.AuditLog, error) {
    sql := `
        SELECT id, actor_id, actor_type, event_type, occurred_at, 
               ip_address, device_id, device_info, security_level, payload, log_category
        FROM audit_logs
        WHERE occurred_at >= $1 AND occurred_at <= $2
        ORDER BY occurred_at DESC
        LIMIT $3 OFFSET $4
    `

    rows, err := r.pool.Query(ctx, sql, from, to, limit, offset)
    if err != nil {
        return nil, fmt.Errorf("failed to get audit logs by time range: %w", err)
    }
    defer rows.Close()

    return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetLoginHistory(ctx context.Context, userID string, limit int) ([]*entity.AuditLog, error) {
    sql := `
        SELECT id, actor_id, actor_type, event_type, occurred_at, 
               ip_address, device_id, device_info, security_level, payload, log_category
        FROM audit_logs
        WHERE actor_id = $1 AND event_type = 'USER_LOGGED_IN'
        ORDER BY occurred_at DESC
        LIMIT $2
    `

    rows, err := r.pool.Query(ctx, sql, userID, limit)
    if err != nil {
        return nil, fmt.Errorf("failed to get login history: %w", err)
    }
    defer rows.Close()

    return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetByFilters(ctx context.Context, filters port.AuditLogFilters, limit, offset int) ([]*entity.AuditLog, int, error) {
    var conditions []string
    var args []interface{}
    argIndex := 1

    if filters.ActorID != "" {
        conditions = append(conditions, fmt.Sprintf("actor_id = $%d", argIndex))
        args = append(args, filters.ActorID)
        argIndex++
    }
    if filters.ActorType != "" {
        conditions = append(conditions, fmt.Sprintf("actor_type = $%d", argIndex))
        args = append(args, filters.ActorType)
        argIndex++
    }
    if filters.EventType != "" {
        conditions = append(conditions, fmt.Sprintf("event_type = $%d", argIndex))
        args = append(args, filters.EventType)
        argIndex++
    }
    if filters.SecurityLevel != "" {
        conditions = append(conditions, fmt.Sprintf("security_level = $%d", argIndex))
        args = append(args, filters.SecurityLevel)
        argIndex++
    }
    if filters.LogCategory != "" {
        conditions = append(conditions, fmt.Sprintf("log_category = $%d", argIndex))
        args = append(args, filters.LogCategory)
        argIndex++
    }
    if !filters.From.IsZero() {
        conditions = append(conditions, fmt.Sprintf("occurred_at >= $%d", argIndex))
        args = append(args, filters.From)
        argIndex++
    }
    if !filters.To.IsZero() {
        conditions = append(conditions, fmt.Sprintf("occurred_at <= $%d", argIndex))
        args = append(args, filters.To)
        argIndex++
    }

    whereClause := ""
    if len(conditions) > 0 {
        whereClause = "WHERE " + strings.Join(conditions, " AND ")
    }

    // Count query
    countSQL := fmt.Sprintf("SELECT COUNT(*) FROM audit_logs %s", whereClause)
    var total int
    err := r.pool.QueryRow(ctx, countSQL, args...).Scan(&total)
    if err != nil {
        return nil, 0, fmt.Errorf("failed to count audit logs: %w", err)
    }

    // Data query
    dataSQL := fmt.Sprintf(`
        SELECT id, actor_id, actor_type, event_type, occurred_at, 
               ip_address, device_id, device_info, security_level, payload, log_category
        FROM audit_logs
        %s
        ORDER BY occurred_at DESC
        LIMIT $%d OFFSET $%d
    `, whereClause, argIndex, argIndex+1)

    args = append(args, limit, offset)

    rows, err := r.pool.Query(ctx, dataSQL, args...)
    if err != nil {
        return nil, 0, fmt.Errorf("failed to get audit logs by filters: %w", err)
    }
    defer rows.Close()

    logs, err := r.scanAuditLogs(rows)
    if err != nil {
        return nil, 0, err
    }

    return logs, total, nil
}

func (r *AuditLogRepository) DeleteOlderThan(ctx context.Context, olderThan time.Time) (int64, error) {
    sql := `DELETE FROM audit_logs WHERE log_category = 'USER' AND occurred_at < $1`

    result, err := r.pool.Exec(ctx, sql, olderThan)
    if err != nil {
        return 0, fmt.Errorf("failed to delete old audit logs: %w", err)
    }

    return result.RowsAffected(), nil
}

func (r *AuditLogRepository) scanAuditLogs(rows interface {
    Next() bool
    Scan(dest ...interface{}) error
}) ([]*entity.AuditLog, error) {
    var logs []*entity.AuditLog

    for {
        var log entity.AuditLog
        var payload []byte

        // Type assertion for rows
        row, ok := rows.(interface {
            Next() bool
            Scan(dest ...interface{}) error
        })
        if !ok {
            break
        }

        if !row.Next() {
            break
        }

        err := row.Scan(
            &log.ID,
            &log.ActorID,
            &log.ActorType,
            &log.EventType,
            &log.OccurredAt,
            &log.IPAddress,
            &log.DeviceID,
            &log.DeviceInfo,
            &log.SecurityLevel,
            &payload,
            &log.LogCategory,
        )
        if err != nil {
            return nil, fmt.Errorf("failed to scan audit log: %w", err)
        }

        if payload != nil {
            log.Payload = json.RawMessage(payload)
        }

        logs = append(logs, &log)
    }

    return logs, nil
}
```

---

## 7. Database Connection Setup

```go
// internal/infrastructure/postgres/connection.go (updated)
package postgres

import (
    "context"
    "fmt"
    "time"

    "github.com/jackc/pgx/v5"
    "github.com/jackc/pgx/v5/pgxpool"

    "github.com/ecommerce/auth-service/internal/common/config"
)

var ErrNotFound = fmt.Errorf("record not found")

type Connection struct {
    pool *pgxpool.Pool
}

func NewConnection(cfg config.DatabaseConfig) (*Connection, error) {
    dsn := fmt.Sprintf(
        "postgres://%s:%s@%s:%d/%s?sslmode=%s",
        cfg.Username,
        cfg.Password,
        cfg.Host,
        cfg.Port,
        cfg.Name,
        cfg.SSLMode,
    )

    poolConfig, err := pgxpool.ParseConfig(dsn)
    if err != nil {
        return nil, fmt.Errorf("failed to parse config: %w", err)
    }

    poolConfig.MaxConns = int32(cfg.MaxOpenConns)
    poolConfig.MinConns = int32(cfg.MaxIdleConns)
    poolConfig.MaxConnLifetime = cfg.ConnMaxLifetime

    ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
    defer cancel()

    pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
    if err != nil {
        return nil, fmt.Errorf("failed to create pool: %w", err)
    }

    if err := pool.Ping(ctx); err != nil {
        return nil, fmt.Errorf("failed to ping database: %w", err)
    }

    return &Connection{pool: pool}, nil
}

func (c *Connection) Pool() *pgxpool.Pool {
    return c.pool
}

func (c *Connection) Ping(ctx context.Context) error {
    return c.pool.Ping(ctx)
}

func (c *Connection) Close() {
    c.pool.Close()
}

// BeginTx starts a new transaction
func (c *Connection) BeginTx(ctx context.Context) (Transaction, error) {
    tx, err := c.pool.Begin(ctx)
    if err != nil {
        return nil, fmt.Errorf("failed to begin transaction: %w", err)
    }
    return &transaction{tx: tx}, nil
}

// Transaction interface for database transactions
type Transaction interface {
    Commit(ctx context.Context) error
    Rollback(ctx context.Context) error
    QueryRow(ctx context.Context, sql string, args ...interface{}) pgx.Row
    Query(ctx context.Context, sql string, args ...interface{}) (pgx.Rows, error)
    Exec(ctx context.Context, sql string, args ...interface{}) (pgxconn, error)
}

type transaction struct {
    tx pgx.Tx
}

func (t *transaction) Commit(ctx context.Context) error {
    return t.tx.Commit(ctx)
}

func (t *transaction) Rollback(ctx context.Context) error {
    return t.tx.Rollback(ctx)
}

func (t *transaction) QueryRow(ctx context.Context, sql string, args ...interface{}) pgx.Row {
    return t.tx.QueryRow(ctx, sql, args...)
}

func (t *transaction) Query(ctx context.Context, sql string, args ...interface{}) (pgx.Rows, error) {
    return t.tx.Query(ctx, sql, args...)
}

func (t *transaction) Exec(ctx context.Context, sql string, args ...interface{}) (pgxconn, error) {
    result, err := t.tx.Exec(ctx, sql, args...)
    return &pgxResult{result: result}, err
}

type pgxconn interface {
    RowsAffected() int64
}

type pgxResult struct {
    result pgxconn
}

func (r *pgxResult) RowsAffected() int64 {
    return r.result.RowsAffected()
}
```

---

## 8. Testing

### 8.1 User Repository Test

```go
// test/repository/user_repo_test.go
package repository

import (
    "context"
    "testing"
    "time"

    "github.com/google/uuid"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/require"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

func TestUserRepository_Create(t *testing.T) {
    ctx := context.Background()
    repo, db := setupTestDB(t)
    defer cleanupTestDB(db)

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "test@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusPendingVerification,
        CreatedAt:    time.Now(),
        UpdatedAt:    time.Now(),
    }

    err := repo.Create(ctx, user)
    require.NoError(t, err)

    // Verify created user
    created, err := repo.GetByID(ctx, user.ID)
    require.NoError(t, err)
    assert.Equal(t, user.Email, created.Email)
    assert.Equal(t, user.Status, created.Status)
}

func TestUserRepository_GetByEmail(t *testing.T) {
    ctx := context.Background()
    repo, db := setupTestDB(t)
    defer cleanupTestDB(db)

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "getbyemail@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusActive,
        CreatedAt:    time.Now(),
        UpdatedAt:    time.Now(),
    }

    err := repo.Create(ctx, user)
    require.NoError(t, err)

    found, err := repo.GetByEmail(ctx, user.Email)
    require.NoError(t, err)
    assert.Equal(t, user.ID, found.ID)
}

func TestUserRepository_LockAccount(t *testing.T) {
    ctx := context.Background()
    repo, db := setupTestDB(t)
    defer cleanupTestDB(db)

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "lock@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusActive,
        CreatedAt:    time.Now(),
        UpdatedAt:    time.Now(),
    }

    err := repo.Create(ctx, user)
    require.NoError(t, err)

    err = repo.LockAccount(ctx, user.ID)
    require.NoError(t, err)

    locked, err := repo.GetByID(ctx, user.ID)
    require.NoError(t, err)
    assert.Equal(t, entity.StatusLocked, locked.Status)
    assert.NotNil(t, locked.LockedAt)
}
```

### 8.2 Test Helper

```go
// test/helper/postgres.go
package helper

import (
    "context"
    "fmt"
    "os"
    "testing"

    "github.com/jackc/pgx/v5/pgxpool"
    "github.com/testcontainers/testcontainers-go"
    "github.com/testcontainers/testcontainers-go/modules/postgres"
    "github.com/stretchr/testify/require"
)

func SetupPostgresContainer(t *testing.T) (*pgxpool.Pool, func()) {
    ctx := context.Background()

    container, err := postgres.Run(ctx,
        "postgres:16-alpine",
        postgres.WithDatabase("auth_test"),
        postgres.WithUsername("postgres"),
        postgres.WithPassword("postgres"),
    )
    require.NoError(t, err)

    connStr, err := container.ConnectionString(ctx)
    require.NoError(t, err)

    poolConfig, err := pgxpool.ParseConfig(connStr)
    require.NoError(t, err)

    pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
    require.NoError(t, err)

    cleanup := func() {
        pool.Close()
        container.Terminate(ctx)
    }

    return pool, cleanup
}

func RunMigrations(t *testing.T, pool *pgxpool.Pool) {
    // Read and execute migration file
    migration, err := os.ReadFile("./internal/infrastructure/postgres/migrations/001_initial_schema.up.sql")
    require.NoError(t, err)

    _, err = pool.Exec(context.Background(), string(migration))
    require.NoError(t, err)
}
```

---

## 9. Checklist

### Database Schema

- [x] Create `users` table with all columns and constraints
- [x] Create `refresh_tokens` table with all columns
- [x] Create `jwt_keys` table for JWKS
- [x] Create `audit_logs` table with GIN index
- [x] Create `update_updated_at_column()` trigger function
- [x] Create trigger for `users` table
- [x] Create all required indexes

### Migration Files

- [x] Create `001_initial_schema.up.sql`
- [x] Create `001_initial_schema.down.sql`
- [ ] Test migration up
- [ ] Test migration down
- [ ] Test migration idempotency

### Domain Entities

- [x] Create `internal/domain/entity/user.go`
- [x] Create `internal/domain/entity/refresh_token.go`
- [x] Create `internal/domain/entity/jwt_key.go`
- [x] Create `internal/domain/entity/audit_log.go`
- [x] Add entity methods (IsActive, IsLocked, CanLogin, etc.)
- [x] Add business logic methods (SetLocked, SetActive, etc.)

### Repository Interfaces

- [x] Create `internal/domain/port/user_repository.go`
- [x] Create `internal/domain/port/refresh_token_repository.go`
- [x] Create `internal/domain/port/jwt_key_repository.go`
- [x] Create `internal/domain/port/audit_log_repository.go`
- [x] Add comprehensive method signatures

### Repository Implementations

- [x] Create `internal/infrastructure/postgres/connection.go`
- [x] Create `internal/infrastructure/postgres/user_repo.go`
- [x] Create `internal/infrastructure/postgres/refresh_token_repo.go`
- [x] Create `internal/infrastructure/postgres/audit_log_repo.go`
- [x] Create `internal/infrastructure/postgres/jwt_key_repo.go`
- [x] Implement all interface methods
- [x] Add proper error handling
- [x] Add SQL query optimization

### Testing

- [x] Write unit tests for entities
- [x] Write unit tests for repositories
- [x] Set up testcontainers for integration tests
- [x] Create test helpers
- [x] Test CRUD operations
- [x] Test edge cases (not found, duplicate, etc.)

### Documentation

- [x] Document schema design decisions
- [x] Add SQL comments
- [x] Document index strategy
- [ ] Add repository usage examples

---

**Previous:** [module-00-project-setup.md](module-00-project-setup.md)  
**Next:** [module-02-redis-cache-layer.md](module-02-redis-cache-layer.md)
