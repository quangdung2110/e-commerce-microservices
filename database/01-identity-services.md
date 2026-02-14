# Nhóm 1: Identity Services (Auth, User, Seller)

> **Phiên bản**: 1.0.0  
> **Cập nhật**: 2026-02-01  
> **Trạng thái**: Draft - Chờ Review

---

## Mục lục
1. [Tổng quan Kiến trúc](#1-tổng-quan-kiến-trúc)
2. [Auth Service](#2-auth-service)
3. [User Service](#3-user-service)
4. [Seller Service](#4-seller-service)
5. [Tích hợp Liên Service](#5-tích-hợp-liên-service)
6. [Kafka Topics & Events](#6-kafka-topics--events)
7. [Redis Caching Strategy](#7-redis-caching-strategy)
8. [Resilience & Performance](#8-resilience--performance)

---

## 1. Tổng quan Kiến trúc

### 1.1 Service Boundaries
┌─────────────────────────────────────────────────────────────────────────────┐
│ IDENTITY SERVICES GROUP │
├─────────────────────────────────────────────────────────────────────────────┤
│ │
│ ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐ │
│ │ AUTH SERVICE │ │ USER SERVICE │ │ SELLER SERVICE │ │
│ │ (Port 8083) │ │ (Port 8084) │ │ (Port 8085) │ │
│ ├──────────────────┤ ├──────────────────┤ ├──────────────────┤ │
│ │ • Authentication │ │ • User Profiles │ │ • Shop Management│ │
│ │ • Token Mgmt │◄──►│ • Addresses │◄──►│ • Verification │ │
│ │ • Session Mgmt │ │ • Preferences │ │ • Staff Mgmt │ │
│ │ • OAuth │ │ • Restrictions │ │ • AI Settings │ │
│ └────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘ │
│ │ │ │ │
│ └───────────────────────┼───────────────────────┘ │
│ │ │
│ ┌──────────────▼──────────────┐ │
│ │ PostgreSQL │ │
│ │ (identity_db) │ │
│ └─────────────────────────────┘ │
│ │ │
│ ┌──────────────▼──────────────┐ │
│ │ Redis │ │
│ │ (Sessions, OTP, Cache) │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘


### 1.2 Database Engine Selection

| Service | Database | Lý do |
|---------|----------|-------|
| Auth Service | **PostgreSQL** + **Redis** | PostgreSQL cho audit trail (login_attempts), Redis cho session/token management (high read/write, TTL support) |
| User Service | **PostgreSQL** | ACID compliance cho user data, complex queries với hierarchy (parent_id), 3NF normalization |
| Seller Service | **PostgreSQL** | Relational data với nhiều FK relationships, complex joins cho reporting, PII encryption support |

### 1.3 Shared Database Strategy

Ba services này **SHARE một database** (`identity_db`) vì:
1. **Strong Consistency**: User-Seller relationship cần ACID transactions
2. **Referential Integrity**: FK constraints giữa users ↔ sellers ↔ verification
3. **Single Source of Truth**: Tránh data duplication và sync issues
4. **Simplified Deployment**: Một connection pool, dễ backup/restore

> ⚠️ **Trade-off**: Tight coupling giữa services. Nếu cần scale riêng, migrate sang separate databases với CDC (Change Data Capture).

---

## 2. Auth Service

### 2.1 Tổng quan

**Vai trò**: Auth Service là "người gác cổng" của toàn bộ hệ thống. Nó chịu trách nhiệm:
- Xác thực danh tính (Authentication)
- Quản lý phiên làm việc (Session Management)
- Cấp phát và thu hồi token (Token Lifecycle)
- Tích hợp đăng nhập bên thứ ba (OAuth2)
- Phát hiện và ngăn chặn tấn công brute-force

**Database**: PostgreSQL (audit tables) + Redis (active sessions, tokens)

---

### 2.2 Database Schema

#### 2.2.1 Bảng `auth_sessions`

**Vai trò**: Lưu trữ thông tin về các phiên đăng nhập đang hoạt động. Cho phép user đăng xuất từ xa, xem lịch sử thiết bị đã đăng nhập.

```sql
CREATE TABLE auth_sessions (
    session_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL
    
    -- Device Fingerprint
    device_id       VARCHAR(255),                    -- Unique device identifier
    device_name     VARCHAR(255),                    -- "iPhone 15 Pro", "Chrome on Windows"
    device_type     VARCHAR(50),                     -- MOBILE, DESKTOP, TABLET, UNKNOWN
    ip_address      INET NOT NULL,                   -- IPv4/IPv6 address
    user_agent      TEXT,                            -- Full user agent string
    
    -- Geolocation (optional, from IP)p
    geo_country     VARCHAR(2),                      -- ISO country code (VN, US)
    geo_city        VARCHAR(100),
    
    -- Session State
    is_active       BOOLEAN DEFAULT TRUE,            -- FALSE when logged out
    
    -- Security
    refresh_token_hash  VARCHAR(255) NOT NULL,       -- Hashed refresh token
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,  -- Session expiry
    revoked_at      TIMESTAMP WITH TIME ZONE,           -- When manually revoked
    
    -- Indexes
    CONSTRAINT idx_sessions_user_active UNIQUE (user_id, device_id, is_active)
);

-- Indexes for common queries
CREATE INDEX idx_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX idx_sessions_expires ON auth_sessions(expires_at) WHERE is_active = TRUE;
CREATE INDEX idx_sessions_last_activity ON auth_sessions(last_activity);
```

Phân tích từng trường:

Trường	            Vai trò
session_id	        PK dạng UUID, không dùng auto-increment để tránh enumeration attack
user_id	            UUID xác định user sở hữu session
device_id	        Định danh thiết bị (có thể hash từ device fingerprint), cho phép "Trust this device"
device_name	        Hiển thị cho user trong "Manage sessions", ví dụ: "iPhone 15 Pro - Safari"
device_type	        Phân loại thiết bị để áp dụng policy khác nhau (mobile có TTL ngắn hơn desktop)
ip_address	        Kiểu INET để PostgreSQL tự validate IPv4/IPv6, dùng cho geo-blocking
user_agent	        Lưu nguyên để debug, detect suspicious changes (cùng session nhưng đổi browser)
geo_country/city	Phát hiện login từ location bất thường (VN → US trong 1 giờ = impossible travel)
is_active	        Soft-delete flag, cho phép giữ history nhưng invalidate session
refresh_token_hash	Hash của refresh token (không lưu plaintext), dùng để rotate token
expires_at	        Hard expiry, thường 30 ngày cho "Remember me", 24h cho normal login
revoked_at	        Timestamp khi user manual logout hoặc admin revoke, NULL nếu chưa revoke

#### 2.2.2 Bảng auth_login_attempts
**Vai trò**: Ghi lại mọi lần thử đăng nhập (thành công và thất bại) để:

+ Phát hiện brute-force attack
+ Audit trail cho compliance
+ Kích hoạt CAPTCHA hoặc lock account sau N lần fail

```sql
CREATE TABLE auth_login_attempts (
    attempt_id      BIGSERIAL PRIMARY KEY,
    
    -- Identifier (có thể chưa biết user_id nếu login fail)
    user_id         UUID 
    login_identifier VARCHAR(255) NOT NULL,          -- Email/Phone
    
    -- Attempt Details
    attempt_type    VARCHAR(20) NOT NULL,            
    is_successful   BOOLEAN NOT NULL,
    failure_reason  VARCHAR(100),                    -- WRONG_PASSWORD, ACCOUNT_LOCKED, OTP_EXPIRED
    
    -- Context
    ip_address      INET NOT NULL,
    user_agent      TEXT,
    device_id       VARCHAR(255),
    
    -- Security Signals
    is_suspicious   BOOLEAN DEFAULT FALSE,           -- Flagged by anomaly detection
    
    -- Timestamp
    attempted_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Partitioning by month for performance (login_attempts grows fast)
-- Recommend: Convert to partitioned table in production

-- Indexes
CREATE INDEX idx_login_attempts_ip ON auth_login_attempts(ip_address, attempted_at DESC);
CREATE INDEX idx_login_attempts_identifier ON auth_login_attempts(login_identifier, attempted_at DESC);
CREATE INDEX idx_login_attempts_user ON auth_login_attempts(user_id, attempted_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX idx_login_attempts_suspicious ON auth_login_attempts(attempted_at DESC) WHERE is_suspicious = TRUE;
```

Phân tích từng trường:

Trường	            Vai trò
attempt_id	        BIGSERIAL vì table này grow rất nhanh, UUID sẽ bloat index
user_id	            Nullable vì login có thể fail trước khi xác định được user (wrong email or wrong password)
login_identifier	Lưu nguyên giá trị user nhập vào, dùng để detect credential stuffing
attempt_type	    Phân biệt các phương thức auth để báo cáo (OTP fail nhiều = SMS system issue)
is_successful	    Boolean đơn giản để query nhanh: WHERE is_successful = FALSE
failure_reason	    Enum-like string cho debugging, không expose cho user (security)
is_suspicious	    Flag bởi hệ thống Analytics, dùng để alert security team
attempted_at	    Indexed để query recent attempts, partition candidate

Partitioning Strategy:
```sql
-- Partition by month
CREATE TABLE auth_login_attempts (
    ...
) PARTITION BY RANGE (attempted_at);

CREATE TABLE auth_login_attempts_2026_01 PARTITION OF auth_login_attempts
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
    
-- Cron job tạo partition cho tháng tiếp theo
-- Retention policy: Giữ 12 tháng, archive hoặc drop partition cũ
```

#### 2.2.3 Bảng auth_oauth_accounts (bảng này là một tùy chọn nâng cao, theo thuộc vào yêu cầu dự án, hiện tại chưa quá cần thiết)
**Vai trò**: Liên kết tài khoản user với các provider OAuth2 bên ngoài (Google, Facebook, Apple, etc). Cho phép một user có nhiều OAuth providers.

```sql
CREATE TABLE auth_oauth_accounts (
    oauth_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    
    -- OAuth Provider
    provider        VARCHAR(20) NOT NULL,            -- GOOGLE, FACEBOOK, APPLE, ZALO
    provider_user_id VARCHAR(255) NOT NULL,          -- ID từ provider (không đổi)
    
    -- Provider Data (encrypted)
    provider_email  VARCHAR(255),                    -- Email từ provider (có thể khác email đăng ký)
    provider_name   VARCHAR(255),                    -- Display name từ provider
    provider_avatar_url TEXT,                        -- Avatar URL từ provider
    
    -- Tokens (encrypted at rest)
    access_token_encrypted  TEXT,                    -- Encrypted, dùng để call provider API
    refresh_token_encrypted TEXT,                    -- Encrypted, để refresh access_token
    token_expires_at        TIMESTAMP WITH TIME ZONE,
    
    -- Metadata
    scopes          TEXT[],                          -- Permissions granted (email, profile, etc)
    raw_profile     JSONB,                           -- Full profile response từ provider
    
    -- Timestamps
    linked_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_used_at    TIMESTAMP WITH TIME ZONE,
    unlinked_at     TIMESTAMP WITH TIME ZONE,        -- Soft delete
    
    -- Constraints
    CONSTRAINT uq_oauth_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_user_provider UNIQUE (user_id, provider)  -- 1 user chỉ có 1 account per provider
);

CREATE INDEX idx_oauth_user ON auth_oauth_accounts(user_id) WHERE unlinked_at IS NULL;
CREATE INDEX idx_oauth_provider_lookup ON auth_oauth_accounts(provider, provider_user_id) WHERE unlinked_at IS NULL;
```
Phân tích từng trường:

Trường	                Vai trò
provider	            Enum-like: GOOGLE, FACEBOOK, APPLE, ZALO. Dùng string thay vì FK để dễ mở rộng
provider_user_id	    ID bất biến từ provider, dùng để lookup khi OAuth callback
provider_email	        Có thể khác với email chính của user (user dùng email cá nhân đăng ký, nhưng link Google workspace)
access_token_encrypted	AES-256 encrypted, dùng khi cần gọi API provider (fetch latest profile, post to social)
refresh_token_encrypted	Để refresh access_token khi hết hạn mà không cần user re-auth
scopes	                Array các permission đã được grant, hiển thị cho user xem app access gì
raw_profile	            JSONB lưu nguyên response từ provider, dùng để debug hoặc sync thêm data sau
unlinked_at	            Soft delete khi user unlink account, giữ history để audit

#### 2.2.4 Bảng auth_password_reset_tokens
**Vai trò**: Quản lý các token reset password. Tách riêng khỏi OTP vì có logic khác (one-time use, longer TTL).

```sql
CREATE TABLE auth_password_reset_tokens (
    token_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID 
    
    -- Token
    token_hash      VARCHAR(255) NOT NULL,           -- SHA-256 hash của token gửi qua email
    
    -- State
    is_used         BOOLEAN DEFAULT FALSE,
    used_at         TIMESTAMP WITH TIME ZONE,
    
    -- Context
    requested_ip    INET NOT NULL,
    requested_user_agent TEXT,
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,  -- Thường 1 hour
    
    -- Constraint: Chỉ 1 token active per user
    CONSTRAINT uq_active_reset_token UNIQUE (user_id) 
        DEFERRABLE INITIALLY DEFERRED  -- Cho phép insert new trước khi invalidate old
);

CREATE INDEX idx_reset_token_hash ON auth_password_reset_tokens(token_hash) WHERE is_used = FALSE;
CREATE INDEX idx_reset_token_expires ON auth_password_reset_tokens(expires_at) WHERE is_used = FALSE;
```

Phân tích từng trường:
Trường	                Vai trò
token_id	            UUID để tránh đoán token, không dùng auto-increment
token_hash	            Lưu hash của token (không lưu plaintext) để verify khi user click link
is_used	                Đánh dấu token đã sử dụng để ngăn tái sử dụng
requested_ip	        Ghi lại IP khi yêu cầu reset, dùng để detect suspicious activity
requested_user_agent	Lưu user agent khi yêu cầu, dùng để debug
expires_at	            TTL ngắn (1 hour) để giảm rủi ro token bị lộ

### 2.3 Redis Schema (Auth Service)
Auth Service sử dụng Redis làm primary storage cho các data có TTL ngắn và high-frequency access.

┌─────────────────────────────────────────────────────────────────────────────┐
│                          REDIS KEYS - AUTH SERVICE                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ACCESS TOKENS (Blacklist only - tokens are stateless JWT)                  │
│  ────────────────────────────────────────────────────────────────────────   │
│  Key:     "token:blacklist:{jti}"                                           │
│  Value:   "1"                                                                │
│  TTL:     Remaining time until token's original expiry                       │
│  Purpose: Invalidate tokens before natural expiry (logout, password change)  │
│                                                                              │
│  REFRESH TOKENS                                                              │
│  ────────────────────────────────────────────────────────────────────────   │
│  Key:     "refresh:{user_id}:{device_id}"                                   │
│  Value:   JSON {                                                            │
│             "token_hash": "sha256...",                                      │
│             "session_id": "uuid",                                           │
│             "ip": "192.168.1.1",                                            │
│             "created_at": "2026-02-01T10:00:00Z",                           │
│             "rotated_from": "previous_token_hash"  // For grace period      │
│           }                                                                 │
│  TTL:     90 days (configurable)                                            │
│  Purpose: Validate refresh token, enable rotation detection                  │
│                                                                              │
│  USER SESSIONS (Quick lookup)                                                │
│  ────────────────────────────────────────────────────────────────────────   │
│  Key:     "user:sessions:{user_id}"                                         │
│  Value:   SET of session_ids                                                │
│  TTL:     None (managed by session lifecycle)                                │
│  Purpose: List all active sessions for "Logout all devices" feature          │
│                                                                              │
│  OTP - EMAIL                                                                │
│  ────────────────────────────────────────────────────────────────────────   │
│  Key:     "otp:email:{purpose}:{identifier_hash}"                           │
│           purpose = REGISTER | LOGIN | RESET_PASSWORD | VERIFY_EMAIL        │
│           identifier_hash = SHA256(email.toLowerCase())                      │
│  Value:   JSON {                                                            │
│             "code_hash": "bcrypt...",  // Hashed 6-digit code               │
│             "attempts": 0,             // Failed verification attempts       │
│             "created_at": "...",                                            │
│             "ip": "...",                                                    │
│             "user_id": "..." // Optional, for existing users                │
│           }                                                                 │
│  TTL:     15 minutes                                                        │
│                                                                              │
│  OTP - PHONE                                                                │
│  ────────────────────────────────────────────────────────────────────────   │
│  Key:     "otp:phone:{purpose}:{phone_hash}"                                │
│  Value:   Same as email OTP                                                 │
│  TTL:     5 minutes (shorter than email, SMS costs money)                   │
│                                                                              │
│  RATE LIMITING                                                              │
│  ────────────────────────────────────────────────────────────────────────   │
│  Key:     "ratelimit:login:{ip}"                                            │
│  Value:   Counter (INCR)                                                    │
│  TTL:     15 minutes                                                        │
│  Limit:   10 attempts / 15 min → Block 1 hour                               │
│                                                                              │
│  Key:     "ratelimit:otp:email:{email}"                                        │
│  Value:   Counter                                                           │
│  TTL:     24 hours                                                          │
│  Limit:   5 requests / day → Block 24 hours                                 │
│                                                                              │
│  Key:     "ratelimit:otp:phone:{phone}"                                │
│  Value:   Counter                                                           │
│  TTL:     24 hours                                                          │
│  Limit:   3 requests / day → Block 24 hours (SMS expensive)                 │
│                                                                              │
│  BLOCKED                                                                    │
│  ────────────────────────────────────────────────────────────────────────   │
│  Key:     "blocked:ip:{ip}"                                                 │
│  Value:   JSON { "reason": "...", "blocked_at": "...", "expires_at": "..." }│
│  TTL:     Varies (1 hour to permanent)                                      │
│                                                                              │
│  Key:     "blocked:user:{user_id}"                                          │
│  Value:   JSON { "reason": "...", "blocked_at": "..." }                     │
│  TTL:     None (until manually removed or user.active = true)               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

### 2.4 Luồng hoạt động Auth Service

#### 2.4.1 Đăng ký tài khoản mới (Registration Flow)

┌─────────────────────────────────────────────────────────────────────────────┐
│                           REGISTRATION FLOW                                  │
└─────────────────────────────────────────────────────────────────────────────┘

User                    Auth Service                 User Service           Redis
 │                           │                            │                   │
 │  1. POST /register        │                            │                   │
 │  {email, phone}           │                            │                   │
 │  ─────────────────────►   │                            │                   │
 │                           │  2. Check rate limit       │                   │
 │                           │  ───────────────────────────────────────────►  │
 │                           │                            │    ratelimit:otp: │
 │                           │  ◄───────────────────────────────────────────  │
 │                           │                            │                   │
 │                           │  3. Check existing user    │                   │
 │                           │  ─────────────────────────►│                   │
 │                           │                            │                   │
 │                           │  ◄─────────────────────────│ Not found         │
 │                           │                            │                   │
 │                           │  4. Generate OTP (6 digits)│                   │
 │                           │  5. Hash OTP with bcrypt   │                   │
 │                           │  6. Store in Redis         │                   │
 │                           │  ───────────────────────────────────────────►  │
 │                           │                   otp:email:REGISTER:{hash}    │
 │                           │                            │    TTL: 15 min    │
 │                           │                            │                   │
 │                           │  7. Send OTP via Email/SMS │                   │
 │                           │  ─────────────────────────────────────────►    │
 │                           │                       Notification Service      │
 │                           │                            │                   │
 │  ◄─────────────────────── │  8. Return request_id     │                   │
 │  {request_id, expires_in} │                            │                   │
 │                           │                            │                   │
 │  9. POST /register/verify │                            │                   │
 │  {request_id, otp, pass}  │                            │                   │
 │  ─────────────────────►   │                            │                   │
 │                           │  10. Get OTP from Redis    │                   │
 │                           │  ───────────────────────────────────────────►  │
 │                           │  ◄───────────────────────────────────────────  │
 │                           │                            │                   │
 │                           │  11. Verify bcrypt(otp)    │                   │
 │                           │                            │                   │
 │                           │  12. Create User           │                   │
 │                           │  ─────────────────────────►│                   │
 │                           │                            │ INSERT users      │
 │                           │                            │ INSERT preferences│
 │                           │  ◄─────────────────────────│ Return user_id    │
 │                           │                            │                   │
 │                           │  13. Generate tokens       │                   │
 │                           │  14. Create session        │                   │
 │                           │  ───────────────────────────────────────────►  │
 │                           │               refresh:{user_id}:{device_id}    │
 │                           │                            │                   │
 │                           │  15. Publish event         │                   │
 │                           │  ─────────────────────────────────────────►    │
 │                           │                          Kafka: user-events    │
 │                           │                          UserRegisteredEvent   │
 │                           │                            │                   │
 │  ◄─────────────────────── │  16. Return tokens        │                   │
 │  {access_token,           │                            │                   │
 │   refresh_token,          │                            │                   │
 │   user}                   │                            │                   │
 └───────────────────────────┴────────────────────────────┴───────────────────┘

 #### 2.4.2 Đăng nhập (Login Flow)
 ┌─────────────────────────────────────────────────────────────────────────────┐
│                              LOGIN FLOW                                      │
└─────────────────────────────────────────────────────────────────────────────┘

                         ┌──────────────────────────────────────┐
                         │  1. POST /login                      │
                         │  {identifier, password, device_info} │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  2. Rate Limit Check                 │
                         │  Redis: GET ratelimit:login:{ip}     │
                         │  If > 10 attempts → 429 Too Many     │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  3. IP Block Check                   │
                         │  Redis: EXISTS blocked:ip:{ip}       │
                         │  If exists → 403 Forbidden           │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  4. Find User                        │
                         │  Query: email OR phone OR username   │
                         │  If not found → Log attempt → 401    │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  5. Check user.active                │
                         │  If FALSE → 401 "Account disabled"   │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  6. Check user.restriction_status    │
                         │  Include in response for UI warning  │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  7. If MANAGED account:              │
                         │  Check parent.active (Owner account) │
                         │  If parent.active = FALSE → 401      │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  8. Verify Password                  │
                         │  bcrypt.verify(password, hash)       │
                         │  If fail → Log attempt → INCR rate   │
                         │            → 401 "Wrong credentials" │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  9. Check 2FA (if enabled)           │
                         │  If user.two_factor_enabled:         │
                         │    Return {requires_2fa: true, ...}  │
                         │    Wait for /login/2fa endpoint      │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  10. Generate Tokens                 │
                         │  Access Token (JWT, 15 min):         │
                         │    {user_id, role, account_type,     │
                         │     parent_id, restriction_status,   │
                         │     jti, iat, exp}                   │
                         │  Refresh Token (opaque, 90 days)     │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  11. Create/Update Session           │
                         │  PostgreSQL: INSERT auth_sessions    │
                         │  Redis: SET refresh:{uid}:{device}   │
                         │  Redis: SADD user:sessions:{uid}     │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  12. Log Successful Attempt          │
                         │  PostgreSQL: INSERT login_attempts   │
                         │  Reset rate limit counter            │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  13. Publish Event                   │
                         │  Kafka: UserLoggedInEvent            │
                         │  (for Analytics, anomaly detection)  │
                         └──────────────────┬───────────────────┘
                                            ▼
                         ┌──────────────────────────────────────┐
                         │  14. Return Response                 │
                         │  {access_token, refresh_token,       │
                         │   user_profile, restriction_warning} │
                         └──────────────────────────────────────┘

#### 2.4.3 Token Refresh Flow
┌─────────────────────────────────────────────────────────────────────────────┐
User                    Auth Service                                   Redis
 │                           │                                           │
 │  1. POST /token/refresh   │                                           │
 │  {refresh_token}          │                                           │
 │  ─────────────────────►   │                                           │
 │                           │  2. Parse refresh token                   │
 │                           │  3. GET refresh:{user_id}:{device_id}     │
 │                           │  ───────────────────────────────────────► │
 │                           │  ◄─────────────────────────────────────── │
 │                           │                                           │
 │                           │  4. Compare token_hash                    │
 │                           │     If mismatch:                          │
 │                           │       Check rotated_from (grace period)   │
 │                           │       If no match → Possible token theft  │
 │                           │       → Revoke ALL sessions for user      │
 │                           │       → Return 401 + force re-login       │
 │                           │                                           │
 │                           │  5. Check user.active                     │
 │                           │  (Query User Service or cache)            │
 │                           │                                           │
 │                           │  6. Generate NEW token pair               │
 │                           │     New access_token (JWT)                │
 │                           │     New refresh_token (rotate)            │
 │                           │                                           │
 │                           │  7. Update Redis                          │
 │                           │     Old token_hash → rotated_from         │
 │                           │     New token_hash → current              │
 │                           │  ───────────────────────────────────────► │
 │                           │                                           │
 │  ◄─────────────────────── │  8. Return new tokens                    │
 │  {access_token,           │                                           │
 │   refresh_token}          │                                           │
 │                           │                                           │


Rotation Detection Logic:
Nếu refresh token không khớp với current token_hash:
1. Kiểm tra rotated_from (token cũ vừa được rotate)
   → Nếu khớp: Grace period (client chưa nhận token mới), cho phép
2. Nếu không khớp cả hai:
   → Token theft detected!
   → Revoke tất cả sessions của user
   → Publish SecurityAlertEvent
   → Force user đăng nhập lại trên tất cả devices

#### 2.4.4 Logout Flow
┌─────────────────────────────────────────────────────────────────────────────┐
│                             LOGOUT FLOWS                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  A. LOGOUT CURRENT DEVICE                                                   │
│  ─────────────────────────────────────────────────────────────────────────  │
│  1. POST /logout (with access_token in header)                              │
│  2. Extract jti from access_token                                           │
│  3. Add to blacklist: SET token:blacklist:{jti} "1" EX {remaining_ttl}     │
│  4. Delete refresh token: DEL refresh:{user_id}:{device_id}                │
│  5. Update session: SET is_active = FALSE, revoked_at = NOW()              │
│  6. Remove from user sessions: SREM user:sessions:{user_id} {session_id}   │
│                                                                              │
│  B. LOGOUT ALL DEVICES                                                      │
│  ─────────────────────────────────────────────────────────────────────────  │
│  1. POST /logout/all (with access_token)                                    │
│  2. Get all sessions: SMEMBERS user:sessions:{user_id}                      │
│  3. For each session:                                                       │
│     - Blacklist its access_token (if we track jti per session)             │
│     - DEL refresh:{user_id}:{device_id}                                    │
│  4. Update all sessions in PostgreSQL: SET is_active = FALSE               │
│  5. DEL user:sessions:{user_id}                                            │
│  6. Publish UserLoggedOutAllEvent (for Analytics)                          │
│                                                                              │
│  C. ADMIN FORCE LOGOUT (User bị ban)                                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│  1. User Service sets user.active = FALSE                                   │
│  2. Publish UserDeactivatedEvent → Kafka                                    │
│  3. Auth Service listens:                                                   │
│     - SET blocked:user:{user_id} {...}                                      │
│     - Execute "Logout All Devices" flow                                     │
│  4. Next request from user → Middleware checks blocked:user                 │
│     → 401 Unauthorized                                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

### 2.5 JWT Token Structure
```json
// Access Token Payload
{
  "sub": "user_id (UUID)",
  "jti": "unique token id (UUID)",
  "type": "access",
  
  // User Context (embedded to avoid DB lookup on every request)
  "role": "CUSTOMER | SELLER_OWNER | SELLER_STAFF | PLATFORM_ADMIN | PLATFORM_SUPPORTER",
  "account_type": "PERSONAL | MANAGED",
  "parent_id": "owner's user_id (for MANAGED accounts)",
  "restriction_status": "NONE | WARNING | LIMITED | SUSPENDED",
  
  // Seller Context (if applicable)
  "seller_ids": ["seller_id_1", "seller_id_2"],  // SELLER_OWNER can have multiple shops
  "active_seller_id": "current shop context",
  
  // Standard Claims
  "iat": 1706770800,  // Issued at
  "exp": 1706771700,  // Expires (15 min later)
  "iss": "ecommerce-platform"
}
```
Security Considerations:

+ Access Token TTL: 15 minutes (short-lived)
+ Không lưu sensitive data (password, PII)
+ restriction_status embedded để services có thể check mà không gọi User Service
+ seller_ids embedded để tránh lookup Seller Service trên mỗi request

### 2.6 Kafka Events (Auth Service)
```yaml
Topic: auth-events
Partitions: 5
Replication Factor: 2

Events Produced:
  - UserRegisteredEvent
      aggregateId: user_id
      payload:
        email: string
        phone: string (masked)
        account_type: PERSONAL | MANAGED
        registration_ip: string
        device_info: object

  - UserLoggedInEvent
      aggregateId: user_id
      payload:
        session_id: string
        device_type: string
        ip_address: string
        geo_location: object
        is_new_device: boolean

  - UserLoggedOutEvent
      aggregateId: user_id
      payload:
        session_id: string
        logout_type: MANUAL | FORCED | EXPIRED

  - PasswordChangedEvent
      aggregateId: user_id
      payload:
        changed_at: timestamp
        ip_address: string
        triggered_by: SELF | ADMIN | RESET_FLOW

  - SuspiciousActivityDetectedEvent
      aggregateId: user_id
      payload:
        activity_type: IMPOSSIBLE_TRAVEL | CREDENTIAL_STUFFING | BRUTE_FORCE
        details: object
        risk_score: number

Events Consumed:
  - UserDeactivatedEvent (from User Service)
      Action: Revoke all sessions, block user

  - UserReactivatedEvent (from User Service)
      Action: Remove from blocked list

  - UserRestrictionChangedEvent (from User Service)
      Action: Update cached restriction status
```

## 3. User Service
### 3.1 Enhanced Schema 
#### 3.1.1 Bảng users
``` sql
CREATE TYPE account_type AS ENUM ('PERSONAL', 'MANAGED');
CREATE TYPE user_role AS ENUM (
    'PLATFORM_ADMIN', 
    'PLATFORM_SUPPORTER', 
    'CUSTOMER', 
    'SELLER_OWNER', 
    'SELLER_STAFF'
);
CREATE TYPE restriction_status AS ENUM ('NONE', 'WARNING', 'LIMITED', 'SUSPENDED');

CREATE TABLE users (
    -- Primary Key
    user_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Hierarchy (Self-reference)
    parent_id       UUID REFERENCES users(user_id) ON DELETE SET NULL,
    
    -- Account Classification
    account_type    account_type NOT NULL DEFAULT 'PERSONAL',
    role            user_role NOT NULL DEFAULT 'CUSTOMER',
    
    -- Identity - Conditional based on account_type
    username        VARCHAR(50),                     -- Required for MANAGED
    email           VARCHAR(255),                    -- Required for PERSONAL
    phone_number    VARCHAR(20),                     -- Required for PERSONAL
    full_name       VARCHAR(255) NOT NULL,
    birth_day       DATE,
    
    -- Authentication
    password_hash   VARCHAR(255),                    -- bcrypt hash
    
    -- Verification Status
    email_verified  BOOLEAN DEFAULT FALSE,
    phone_verified  BOOLEAN DEFAULT FALSE,
    
    -- Profile (Flexible JSON)
    profile         JSONB DEFAULT '{}',
    /*  Structure:
        {
            "avatar_url": "https://s3...",
            "gender": "male" | "female" | "other" | "prefer_not_to_say",
            "bio": "...",
            "social_links": { "facebook": "...", "instagram": "..." }
        }
    */
    
    -- Account State
    active          BOOLEAN DEFAULT TRUE,
    deactivated_at  TIMESTAMP WITH TIME ZONE,        -- ✅ NEW: When deactivated
    deactivated_by  UUID REFERENCES users(user_id),  -- ✅ NEW: Who deactivated (admin or self)
    deletion_scheduled_at TIMESTAMP WITH TIME ZONE,  -- ✅ NEW: When to permanently delete (30 days after deactivate)
    
    -- Restriction (Behavioral)
    restriction_status     restriction_status DEFAULT 'NONE',
    restriction_reason     TEXT,                     -- ✅ NEW: Human-readable reason
    restriction_expires_at TIMESTAMP WITH TIME ZONE, -- ✅ NEW: Auto-lift for LIMITED
    
    -- Optimistic Locking
    version         INTEGER DEFAULT 1,               -- ✅ NEW: For concurrent update safety
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT chk_personal_has_contact CHECK (
        account_type = 'MANAGED' OR (email IS NOT NULL OR phone_number IS NOT NULL)
    ),
    CONSTRAINT chk_managed_has_username CHECK (
        account_type = 'PERSONAL' OR username IS NOT NULL
    ),
    CONSTRAINT chk_no_self_parent CHECK (parent_id != user_id),
    CONSTRAINT chk_managed_has_parent CHECK (
        account_type = 'PERSONAL' OR parent_id IS NOT NULL
    )
);

-- Partial Unique Indexes (handle NULL properly)
CREATE UNIQUE INDEX idx_users_email ON users(LOWER(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX idx_users_phone ON users(phone_number) WHERE phone_number IS NOT NULL;
CREATE UNIQUE INDEX idx_users_username ON users(LOWER(username)) WHERE username IS NOT NULL;

-- Query Indexes
CREATE INDEX idx_users_parent ON users(parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_active ON users(active) WHERE active = TRUE;
CREATE INDEX idx_users_restriction ON users(restriction_status) WHERE restriction_status != 'NONE';
CREATE INDEX idx_users_deletion_scheduled ON users(deletion_scheduled_at) 
    WHERE deletion_scheduled_at IS NOT NULL AND active = FALSE;

-- Trigger: Update updated_at
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at();
```

#### 3.1.2 Bảng user_restriction_history (NEW)
**Vai trò**: Audit trail cho mọi thay đổi restriction status. Quan trọng cho:

+ Dispute resolution (user khiếu nại bị ban oan)
+ Pattern detection (user bị restrict nhiều lần)
+ Compliance reporting

```sql
CREATE TABLE user_restriction_history (
    history_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    
    -- Change Details
    previous_status restriction_status NOT NULL,
    new_status      restriction_status NOT NULL,
    
    -- Reason & Evidence
    reason          TEXT NOT NULL,                   -- Human-readable reason
    evidence_data   JSONB,                           -- Links to evidence (order_ids, chat_ids, etc)
    /*  Structure:
        {
            "order_ids": ["...", "..."],      // Orders that triggered this
            "chat_reports": ["...", "..."],    // Chat incident reports
            "analytics_data": { ... }          // Data from Analytics Service
        }
    */
    
    -- Who made the change
    changed_by      UUID REFERENCES users(user_id),  -- NULL if system/automated
    changed_by_type VARCHAR(20) NOT NULL,            -- SYSTEM, ADMIN, ANALYTICS
    
    -- Duration (for LIMITED)
    expires_at      TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_restriction_history_user ON user_restriction_history(user_id, created_at DESC);
CREATE INDEX idx_restriction_history_status ON user_restriction_history(new_status, created_at DESC);
```

#### 3.1.3 Bảng user_address 
```sql
CREATE TABLE user_addresses (
    address_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    
    -- Receiver Info (có thể khác user)
    receiver_name   VARCHAR(255) NOT NULL,
    phone_number    VARCHAR(20) NOT NULL,
    
    -- Location - Structured
    province_id     INTEGER NOT NULL,                -- FK to geo_provinces (separate service)
    district_id     INTEGER NOT NULL,                -- FK to geo_districts
    ward_id         INTEGER NOT NULL,                -- FK to geo_wards
    street_address  TEXT NOT NULL,                   -- Số nhà, tên đường
    
    -- Location - Coordinates (for precise delivery)
    latitude        DECIMAL(10, 8),
    longitude       DECIMAL(11, 8),
    
    -- Classification
    address_type    VARCHAR(20) DEFAULT 'HOME',      -- ✅ NEW: HOME, OFFICE, OTHER
    label           VARCHAR(100),                    -- ✅ NEW: Custom label "Nhà ngoại", "Công ty"
    
    -- Preferences
    is_default      BOOLEAN DEFAULT FALSE,
    
    -- Soft Delete
    is_active       BOOLEAN DEFAULT TRUE,            -- ✅ NEW: Soft delete instead of hard delete
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Ensure only one default address per user
CREATE UNIQUE INDEX idx_user_default_address ON user_addresses(user_id) 
    WHERE is_default = TRUE AND is_active = TRUE;

CREATE INDEX idx_user_addresses_user ON user_addresses(user_id) WHERE is_active = TRUE;

-- Trigger: When setting new default, unset old default
CREATE OR REPLACE FUNCTION manage_default_address()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_default = TRUE THEN
        UPDATE user_addresses 
        SET is_default = FALSE, updated_at = NOW()
        WHERE user_id = NEW.user_id 
          AND address_id != NEW.address_id 
          AND is_default = TRUE;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_manage_default_address
    BEFORE INSERT OR UPDATE ON user_addresses
    FOR EACH ROW
    WHEN (NEW.is_default = TRUE)
    EXECUTE FUNCTION manage_default_address();
``` 

#### 3.1.4 Bảng preferences 

```sql
CREATE TABLE preferences (
    preference_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    
    -- UI Preferences
    theme           VARCHAR(20) DEFAULT 'light',     -- light, dark, system
    language        VARCHAR(10) DEFAULT 'vi',        -- vi, en, etc
    currency        VARCHAR(3) DEFAULT 'VND',        -- VND, USD, etc
    
    -- Notification Preferences (✅ NEW)
    notification_settings JSONB DEFAULT '{
        "email": {"marketing": true, "order_updates": true, "promotions": true},
        "push": {"marketing": false, "order_updates": true, "chat": true},
        "sms": {"order_updates": true, "otp": true}
    }',
    
    -- Privacy Preferences (✅ NEW)
    privacy_settings JSONB DEFAULT '{
        "show_online_status": true,
        "allow_recommendations": true,
        "share_purchase_history_for_ai": true
    }',
    
    -- Timestamps
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

### 3.2 Luồng hoạt động
#### 3.2.1 State Machine: User Lifecycle
┌─────────────────────────────────────────────────────────────────────────────┐
│                        USER LIFECYCLE STATE MACHINE                          │
└─────────────────────────────────────────────────────────────────────────────┘

                            ┌──────────────┐
                            │  REGISTERED  │ (email/phone_verified = FALSE)
                            └──────┬───────┘
                                   │ Verify OTP
                                   ▼
    ┌────────────────────────────────────────────────────────────────┐
    │                         ACTIVE                                  │
    │  (active = TRUE, restriction_status = NONE)                    │
    │                                                                │
    │  Can: Login, Purchase, Chat, Create Shop, etc.                 │
    └─────────────────────────────┬──────────────────────────────────┘
                                  │
           ┌──────────────────────┼──────────────────────┐
           │                      │                      │
           ▼                      ▼                      ▼
    ┌─────────────┐       ┌─────────────┐       ┌─────────────────┐
    │   WARNING   │       │   LIMITED   │       │    SUSPENDED    │
    │             │       │             │       │                 │
    │ Can: All    │       │ Can: Login, │       │ Can: Login,     │
    │ + Warning   │       │ Browse      │       │ Chat w/ Support │
    │ banner      │       │             │       │                 │
    │             │       │ Cannot:     │       │ Cannot: All     │
    │             │       │ Order, Chat │       │ other actions   │
    │             │       │ with Shops  │       │                 │
    │             │       │             │       │                 │
    │ Duration:   │       │ Duration:   │       │ Duration:       │
    │ Until fixed │       │ 7 days      │       │ Indefinite      │
    └──────┬──────┘       └──────┬──────┘       └────────┬────────┘
           │                     │                       │
           │  Behavior improved  │ 7 days passed         │ Appeal approved
           └──────────┬──────────┘                       │
                      │                                  │
                      ▼                                  │
    ┌────────────────────────────────────────────────────┘
    │                         ACTIVE
    └─────────────────────────────────────────────────────
                                  │
                                  │ User requests deletion
                                  │ OR Admin bans
                                  ▼
                         ┌─────────────┐
                         │ DEACTIVATED │
                         │             │
                         │ active=FALSE│
                         │ 30 day hold │
                         └──────┬──────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
              ▼                 │                 ▼
    ┌─────────────────┐         │       ┌─────────────────┐
    │   REACTIVATED   │         │       │    DELETED      │
    │ (User requests) │         │       │  (After 30d)    │
    │                 │         │       │                 │
    │ → Back to       │         │       │ Data anonymized │
    │   ACTIVE        │         │       │ Event published │
    └─────────────────┘         │       └─────────────────┘
                                │
                                │ If user is SELLER_OWNER
                                │ or MANAGED parent
                                ▼
                    ┌───────────────────────┐
                    │   DOMINO EFFECT       │
                    │                       │
                    │ All child accounts    │
                    │ (MANAGED) are also    │
                    │ deactivated           │
                    │                       │
                    │ All shops are set     │
                    │ to SUSPENDED          │
                    └───────────────────────┘

#### 3.3.2 Role Transition Flow
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ROLE TRANSITION RULES                                │
└─────────────────────────────────────────────────────────────────────────────┘

CUSTOMER ────────────────────► SELLER_OWNER
    │                              │
    │  1. Submit shop application  │
    │  2. Seller Service creates   │
    │     seller record            │
    │  3. User Service updates     │
    │     role = SELLER_OWNER      │
    │                              │
    │  ⚠️ SELLER_OWNER vẫn giữ     │
    │    quyền CUSTOMER            │
    │    (có thể mua hàng)         │
    │                              │
    └──────────────────────────────┘

SELLER_OWNER ─────────────────► Creates SELLER_STAFF
    │                              │
    │  1. Owner vào Dashboard      │
    │  2. Tạo account mới:         │
    │     account_type = MANAGED   │
    │     parent_id = owner's id   │
    │     role = SELLER_STAFF      │
    │  3. Assign to shop via       │
    │     seller_staff table       │
    │                              │
    └──────────────────────────────┘

PLATFORM_ADMIN ───────────────► Creates PLATFORM_SUPPORTER
    │                              │
    │  Similar flow to above       │
    │  parent_id = admin's id      │
    │                              │
    └──────────────────────────────┘

    ⚠️ FORBIDDEN TRANSITIONS:
    ✗ CUSTOMER → PLATFORM_ADMIN (manual DB only)
    ✗ SELLER_STAFF → SELLER_OWNER (must create new PERSONAL account)
    ✗ Any role → lower role (no demotion)

### 3.4 Kafka Events (User Service)
```yaml
Topic: user-events
Partitions: 5
Replication Factor: 2

Events Produced:
  - UserCreatedEvent
      aggregateId: user_id
      payload:
        email: string (masked)
        phone: string (masked)
        account_type: string
        role: string
        parent_id: string (nullable)

  - UserUpdatedEvent
      aggregateId: user_id
      payload:
        changed_fields: string[]
        # NOT including sensitive data

  - UserDeactivatedEvent
      aggregateId: user_id
      payload:
        reason: SELF_REQUEST | ADMIN_ACTION | FRAUD_DETECTED
        deactivated_by: user_id
        deletion_scheduled_at: timestamp
        # Triggers: Auth revokes sessions, Cart clears, etc.

  - UserReactivatedEvent
      aggregateId: user_id
      payload:
        reactivated_by: user_id

  - UserPermanentlyDeletedEvent
      aggregateId: user_id
      payload:
        # Triggers: All services anonymize/delete user data

  - UserRestrictionChangedEvent
      aggregateId: user_id
      payload:
        previous_status: string
        new_status: string
        reason: string
        expires_at: timestamp (nullable)
        # Triggers: Auth updates JWT claims on next refresh

  - UserRoleChangedEvent
      aggregateId: user_id
      payload:
        previous_role: string
        new_role: string
        # Triggers: Permission refresh across services

  - AddressCreatedEvent / AddressUpdatedEvent / AddressDeletedEvent
      aggregateId: user_id
      payload:
        address_id: string
        is_default: boolean

Events Consumed:
  - (from Analytics) UserBehaviorAnalyzedEvent
      Action: Update restriction_status if violations detected

  - (from Seller) ShopCreatedEvent
      Action: Update role to SELLER_OWNER if first shop
```

## 4. Seller Service 
### 4.1 Enhanced Schema
#### 4.1.1 Bảng sellers
```sql
CREATE TYPE business_type AS ENUM ('INDIVIDUAL', 'CORPORATE');
CREATE TYPE shop_status AS ENUM ('PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE');

CREATE TABLE sellers (
    seller_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Owner (Direct link to user, not through verification)
    owner_user_id   UUID NOT NULL REFERENCES users(user_id),  -- ✅ CHANGED: Direct FK
    
    -- Shop Identity
    shop_name       VARCHAR(255) NOT NULL,
    shop_slug       VARCHAR(255) NOT NULL UNIQUE,            -- URL-friendly name
    description     TEXT,
    logo_url        TEXT,                                     -- ✅ NEW
    banner_url      TEXT,                                     -- ✅ NEW
    
    -- Contact (Shop's public contact, not owner's personal)
    contact_email   VARCHAR(255),
    contact_phone   VARCHAR(20),
    
    -- Classification
    business_type   business_type NOT NULL,
    
    -- Status
    status          shop_status DEFAULT 'PENDING',
    status_reason   TEXT,                                     -- ✅ NEW: Why suspended/inactive
    
    -- Verification Reference (Computed from owner's verification)
    -- Không lưu FK, query từ seller_verification WHERE user_id = owner_user_id
    
    -- Analytics (Denormalized for performance)
    rating_average  DECIMAL(2,1) DEFAULT 0.0,                 -- ✅ NEW
    rating_count    INTEGER DEFAULT 0,                        -- ✅ NEW
    product_count   INTEGER DEFAULT 0,                        -- ✅ NEW
    follower_count  INTEGER DEFAULT 0,                        -- ✅ NEW
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),   -- ✅ ADDED
    
    -- Optimistic Locking
    version         INTEGER DEFAULT 1
);

-- Indexes
CREATE INDEX idx_sellers_owner ON sellers(owner_user_id);
CREATE INDEX idx_sellers_status ON sellers(status);
CREATE INDEX idx_sellers_slug ON sellers(shop_slug);

-- Full-text search for shop name
CREATE INDEX idx_sellers_name_search ON sellers USING gin(to_tsvector('simple', shop_name));

-- Trigger: Update updated_at
CREATE TRIGGER trg_sellers_updated_at
    BEFORE UPDATE ON sellers
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at();
```

#### 4.1.2 Bảng seller_verification
```sql
CREATE TYPE verification_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED');

CREATE TABLE seller_verification (
    verification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Link to User (Owner)
    user_id         UUID NOT NULL UNIQUE REFERENCES users(user_id),  -- One verification per user
    
    -- Legal Identity (Encrypted PII)
    id_card_number_encrypted BYTEA NOT NULL,                  -- AES-256 encrypted
    id_card_number_hash      VARCHAR(64) NOT NULL UNIQUE,     -- SHA-256 for uniqueness check
    
    -- Tax Information (Encrypted PII)
    tax_id_encrypted         BYTEA,                           -- Nullable for INDIVIDUAL below threshold
    tax_id_hash              VARCHAR(64) UNIQUE,              -- SHA-256 for uniqueness check
    
    -- Documents
    document_urls   JSONB NOT NULL,
    /*  Structure:
        {
            "id_card_front": "https://s3.../front.jpg",
            "id_card_back": "https://s3.../back.jpg",
            "selfie_with_id": "https://s3.../selfie.jpg",
            "business_license": "https://s3.../license.pdf",  // CORPORATE only
            "bank_statement": "https://s3.../statement.pdf"   // Optional
        }
    */
    
    -- Verification State
    status          verification_status DEFAULT 'PENDING',
    rejection_reason TEXT,                                    -- Required if REJECTED
    rejection_count  INTEGER DEFAULT 0,                       -- ✅ NEW: Track resubmissions
    
    -- Verifier Info
    verified_by     UUID REFERENCES users(user_id),           -- Platform staff who verified
    verified_at     TIMESTAMP WITH TIME ZONE,
    
    -- Expiry (KYC needs periodic re-verification)
    expires_at      TIMESTAMP WITH TIME ZONE,                 -- ✅ NEW: e.g., 2 years from approval
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_verification_user ON seller_verification(user_id);
CREATE INDEX idx_verification_status ON seller_verification(status);
CREATE INDEX idx_verification_expires ON seller_verification(expires_at) 
    WHERE status = 'APPROVED';
```

#### 4.1.3 Bảng seller_operating_hours (Redesigned)
```sql
CREATE TABLE seller_operating_hours (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id       UUID NOT NULL REFERENCES sellers(seller_id) ON DELETE CASCADE,
    
    -- Day of Week (ISO: 1=Monday, 7=Sunday)
    day_of_week     SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    
    -- Time Range (có thể có nhiều slots trong ngày, e.g., 8-12 và 14-18)
    open_time       TIME NOT NULL,
    close_time      TIME NOT NULL,
    
    -- State
    is_closed       BOOLEAN DEFAULT FALSE,                    -- TRUE = nghỉ ngày này
    
    -- Constraint
    CONSTRAINT chk_valid_hours CHECK (open_time < close_time OR is_closed = TRUE),
    CONSTRAINT uq_seller_day_slot UNIQUE (seller_id, day_of_week, open_time)
);

CREATE INDEX idx_operating_hours_seller ON seller_operating_hours(seller_id);

/*
  Example Data:
  | seller_id | day_of_week | open_time | close_time | is_closed |
  |-----------|-------------|-----------|------------|-----------|
  | uuid-1    | 1 (Mon)     | 08:00     | 12:00      | FALSE     |
  | uuid-1    | 1 (Mon)     | 14:00     | 18:00      | FALSE     |  <- 2 slots on Monday
  | uuid-1    | 2 (Tue)     | 08:00     | 18:00      | FALSE     |
  | uuid-1    | 6 (Sat)     | NULL      | NULL       | TRUE      |  <- Closed Saturday
  | uuid-1    | 7 (Sun)     | NULL      | NULL       | TRUE      |  <- Closed Sunday
*/
```

#### 4.1.4 Bảng seller_addresses (Simplified - Legal only)
```sql
CREATE TABLE seller_addresses (
    address_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id       UUID NOT NULL REFERENCES sellers(seller_id) ON DELETE CASCADE,
    
    -- Legal Address (for tax/compliance purposes)
    contact_name    VARCHAR(255) NOT NULL,
    phone_number    VARCHAR(20) NOT NULL,
    
    -- Location
    province_id     INTEGER NOT NULL,
    district_id     INTEGER NOT NULL,
    ward_id         INTEGER NOT NULL,
    street_address  TEXT NOT NULL,
    
    -- One legal address per seller
    CONSTRAINT uq_seller_legal_address UNIQUE (seller_id)
);
```


#### 4.1.5 Bảng seller_bank_accounts (Unchanged, add timestamp)
```sql
CREATE TABLE seller_bank_accounts (
    account_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id       UUID NOT NULL REFERENCES sellers(seller_id) ON DELETE CASCADE,
    
    -- Bank Info
    bank_code       VARCHAR(20) NOT NULL,                     -- BIDV, VCB, TCB, etc.
    
    -- Account (Encrypted)
    account_number_encrypted BYTEA NOT NULL,
    account_number_hash      VARCHAR(64) NOT NULL,            -- For uniqueness across platform
    account_holder_name      VARCHAR(255) NOT NULL,
    
    -- Verification
    is_verified     BOOLEAN DEFAULT FALSE,
    verified_at     TIMESTAMP WITH TIME ZONE,
    
    -- State
    is_primary      BOOLEAN DEFAULT FALSE,                    -- Primary account for payouts
    is_active       BOOLEAN DEFAULT TRUE,
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- One primary account per seller
    CONSTRAINT uq_seller_primary_bank UNIQUE (seller_id) 
        -- PostgreSQL doesn't support conditional unique, use partial index instead
);

CREATE UNIQUE INDEX idx_bank_primary ON seller_bank_accounts(seller_id) 
    WHERE is_primary = TRUE AND is_active = TRUE;

-- Uniqueness of account number across platform (prevent same account for multiple sellers)
CREATE UNIQUE INDEX idx_bank_account_unique ON seller_bank_accounts(account_number_hash) 
    WHERE is_active = TRUE;
```

#### 4.1.6 Bảng seller_business_policy (Enhanced)
```sql
CREATE TYPE policy_type AS ENUM ('SHIPPING', 'RETURN', 'FAQ', 'WARRANTY', 'PROMOTION');

CREATE TABLE seller_business_policies (
    policy_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id       UUID NOT NULL REFERENCES sellers(seller_id) ON DELETE CASCADE,
    
    -- Policy Type
    policy_type     policy_type NOT NULL,
    
    -- Content
    content_text    TEXT NOT NULL,                            -- ✅ NEW: Human-readable content
    content_vector  VECTOR(1536),                             -- ✅ For AI (OpenAI embedding dimension)
    
    -- Metadata
    summary         VARCHAR(500),                             -- ✅ NEW: Short summary for quick display
    
    -- State
    is_active       BOOLEAN DEFAULT TRUE,
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- One active policy per type per seller
    CONSTRAINT uq_seller_policy_type UNIQUE (seller_id, policy_type) 
);

-- Vector similarity search index (requires pgvector extension)
CREATE INDEX idx_policy_vector ON seller_business_policies 
    USING ivfflat (content_vector vector_cosine_ops) WITH (lists = 100);
```


#### 4.1.7 Bảng seller_staff (Fixed)
```sql
CREATE TYPE staff_role AS ENUM ('OWNER', 'MANAGER', 'SUPPORTER');

CREATE TABLE seller_staff (
    -- Composite PK: One user can have different roles in different shops
    seller_id       UUID NOT NULL REFERENCES sellers(seller_id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    
    PRIMARY KEY (seller_id, user_id),
    
    -- Role in this shop
    role            staff_role NOT NULL,
    
    -- Permissions (Fine-grained, beyond role)
    permissions     JSONB DEFAULT '{}',
    /*  Structure:
        {
            "can_manage_products": true,
            "can_view_analytics": true,
            "can_manage_orders": true,
            "can_manage_promotions": false,
            "can_manage_staff": false,
            "can_chat": true,
            "can_manage_bank": false
        }
    */
    
    -- State
    is_active       BOOLEAN DEFAULT TRUE,
    
    -- Timestamps
    joined_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_staff_seller ON seller_staff(seller_id) WHERE is_active = TRUE;
CREATE INDEX idx_staff_user ON seller_staff(user_id) WHERE is_active = TRUE;

-- Ensure at least one OWNER per shop (checked by application logic)
```

#### 4.1.8 Bảng seller_support_settings & seller_ai_customization (Unchanged, OK)


### 4.2 Seller State Machine
┌─────────────────────────────────────────────────────────────────────────────┐
│                       SELLER/SHOP LIFECYCLE                                  │
└─────────────────────────────────────────────────────────────────────────────┘

                              ┌─────────────────┐
                              │     PENDING     │
                              │                 │
                              │ Waiting for     │
                              │ verification    │
                              │ approval        │
                              └────────┬────────┘
                                       │
                   ┌───────────────────┼───────────────────┐
                   │                   │                   │
                   ▼                   ▼                   │
          ┌─────────────┐     ┌─────────────┐             │
          │  REJECTED   │     │   ACTIVE    │             │
          │             │     │             │             │
          │ Verification│     │ Normal ops  │             │
          │ failed      │     │ - Sell      │             │
          │             │     │ - Chat      │             │
          │ Can reapply │     │ - Withdraw  │             │
          └─────────────┘     └──────┬──────┘             │
                                     │                    │
                   ┌─────────────────┼─────────────────┐  │
                   │                 │                 │  │
                   ▼                 ▼                 ▼  │
          ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
          │  INACTIVE   │   │  SUSPENDED  │   │   DELETED   │
          │             │   │             │   │             │
          │ Self-pause  │   │ Violation   │   │ Owner       │
          │ (vacation)  │   │ detected    │   │ requested   │
          │             │   │             │   │             │
          │ Products    │   │ Products    │   │ Data kept   │
          │ hidden      │   │ hidden      │   │ for 30 days │
          │             │   │             │   │             │
          │ Can resume  │   │ Appeal →    │   │ Then purged │
          │ anytime     │   │ ACTIVE      │   │             │
          └─────────────┘   └─────────────┘   └─────────────┘


VERIFICATION STATE MACHINE (separate)
─────────────────────────────────────

PENDING ──► APPROVED ──► (linked to shop, shop becomes ACTIVE)
   │            │
   │            └──► EXPIRED (after 2 years, need re-verify)
   │
   └──► REJECTED ──► PENDING (resubmit documents)



### 4.3 Kafka Events (Seller Service)
```yaml
Topic: seller-events
Partitions: 5
Replication Factor: 2

Events Produced:
  - ShopCreatedEvent
      aggregateId: seller_id
      payload:
        owner_user_id: string
        shop_name: string
        business_type: string
        status: PENDING

  - ShopStatusChangedEvent
      aggregateId: seller_id
      payload:
        previous_status: string
        new_status: string
        reason: string
        changed_by: user_id

  - SellerVerificationStatusChangedEvent
      aggregateId: verification_id
      payload:
        user_id: string
        previous_status: string
        new_status: string
        # If APPROVED → triggers ShopStatusChangedEvent to ACTIVE

  - StaffAddedEvent / StaffRemovedEvent / StaffRoleChangedEvent
      aggregateId: seller_id
      payload:
        user_id: string
        role: string

  - WarehouseCreatedEvent / WarehouseUpdatedEvent
      aggregateId: seller_id
      payload:
        warehouse_id: string
        warehouse_type: string
        # Consumed by Shipping Service for delivery optimization

Events Consumed:
  - (from User) UserDeactivatedEvent
      Action: If user is shop owner, set all their shops to SUSPENDED

  - (from Analytics) ShopViolationDetectedEvent
      Action: Set shop status to SUSPENDED

  - (from Order) OrderCompletedEvent
      Action: Update rating_average, rating_count (denormalized)

  - (from Product) ProductCreatedEvent / ProductDeletedEvent
      Action: Update product_count (denormalized)
```

## 5. Tích hợp Liên Service

### 5.1 Service Communication Matrix

┌─────────────────────────────────────────────────────────────────────────────┐
│                    SERVICE COMMUNICATION MATRIX                              │
├──────────────┬──────────────┬──────────────┬────────────────────────────────┤
│    FROM      │      TO      │    METHOD    │         PURPOSE                │
├──────────────┼──────────────┼──────────────┼────────────────────────────────┤
│ Auth         │ User         │ REST (sync)  │ Validate credentials, get user │
│ Auth         │ Redis        │ Direct       │ Token management               │
│ Auth         │ Kafka        │ Publish      │ Login events for Analytics     │
├──────────────┼──────────────┼──────────────┼────────────────────────────────┤
│ User         │ Auth         │ Kafka        │ Notify deactivation (revoke)   │
│ User         │ Seller       │ REST (sync)  │ Check if user is shop owner    │
│ User         │ Kafka        │ Publish      │ User events (GDPR cleanup)     │
├──────────────┼──────────────┼──────────────┼────────────────────────────────┤
│ Seller       │ User         │ REST (sync)  │ Validate owner_user_id         │
│ Seller       │ Kafka        │ Publish      │ Shop events                    │
│ Seller       │ Product      │ Kafka        │ Shop status change (hide prods)│
├──────────────┼──────────────┼──────────────┼────────────────────────────────┤
│ Order        │ User         │ REST (sync)  │ Get shipping address           │
│ Order        │ Seller       │ REST (sync)  │ Validate shop is ACTIVE        │
│ Analytics    │ User         │ Kafka        │ Restriction recommendations    │
│ Analytics    │ Seller       │ Kafka        │ Violation detection            │
└──────────────┴──────────────┴──────────────┴────────────────────────────────┘

### 5.2 Cross-Service Data Flow Example

┌─────────────────────────────────────────────────────────────────────────────┐
│           RESTRICTION ENFORCEMENT FLOW (Cross-Service)                       │
└─────────────────────────────────────────────────────────────────────────────┘

Analytics Service          User Service           Auth Service          Order Service
      │                         │                       │                     │
      │  1. Detect violation    │                       │                     │
      │  (5+ cancellations)     │                       │                     │
      │                         │                       │                     │
      │  2. Publish event       │                       │                     │
      │ ─────────────────────►  │                       │                     │
      │  UserBehaviorAnalyzed   │                       │                     │
      │  {recommend: LIMITED}   │                       │                     │
      │                         │                       │                     │
      │                         │  3. Update user       │                     │
      │                         │  restriction_status   │                     │
      │                         │  = LIMITED            │                     │
      │                         │  expires_at = +7 days │                     │
      │                         │                       │                     │
      │                         │  4. Insert history    │                     │
      │                         │                       │                     │
      │                         │  5. Publish event     │                     │
      │                         │ ──────────────────────────────────────────► │
      │                         │  UserRestrictionChanged                     │
      │                         │  {status: LIMITED}                          │
      │                         │                       │                     │
      │                         │                       │  6. Auth caches     │
      │                         │                       │  restriction status │
      │                         │                       │                     │
─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
                         Later: User tries to order
─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
      │                         │                       │                     │
      │                         │                       │  7. User logs in    │
      │                         │                       │  JWT includes:      │
      │                         │                       │  restriction: LIMITED
      │                         │                       │                     │
      │                         │                       │                     │  8. POST /orders
      │                         │                       │                     │  with JWT
      │                         │                       │                     │
      │                         │                       │                     │  9. Order Service
      │                         │                       │                     │  extracts JWT
      │                         │                       │                     │  sees LIMITED
      │                         │                       │                     │
      │                         │                       │                     │  10. Return 403
      │                         │                       │                     │  "Bạn bị hạn chế
      │                         │                       │                     │   đặt hàng"
      │                         │                       │                     │

## 6. Kafka Topics Summary
```yaml
# Identity Services Kafka Topics

auth-events:
  partitions: 5
  replication: 2
  retention: 7 days
  events:
    - UserRegisteredEvent
    - UserLoggedInEvent
    - UserLoggedOutEvent
    - PasswordChangedEvent
    - SuspiciousActivityDetectedEvent

user-events:
  partitions: 5
  replication: 2
  retention: 30 days
  events:
    - UserCreatedEvent
    - UserUpdatedEvent
    - UserDeactivatedEvent
    - UserReactivatedEvent
    - UserPermanentlyDeletedEvent
    - UserRestrictionChangedEvent
    - UserRoleChangedEvent
    - AddressCreatedEvent
    - AddressUpdatedEvent

seller-events:
  partitions: 5
  replication: 2
  retention: 30 days
  events:
    - ShopCreatedEvent
    - ShopStatusChangedEvent
    - SellerVerificationStatusChangedEvent
    - StaffAddedEvent
    - StaffRemovedEvent
    - StaffRoleChangedEvent
    - WarehouseCreatedEvent
    - WarehouseUpdatedEvent
    - BankAccountAddedEvent
```

## 7. Redis Caching Strategy
┌─────────────────────────────────────────────────────────────────────────────┐
│                     REDIS KEY PATTERNS - IDENTITY SERVICES                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  AUTH SERVICE                                                               │
│  ─────────────────────────────────────────────────────────────────────────  │
│  token:blacklist:{jti}              │ TTL: Token's remaining lifetime        │
│  refresh:{user_id}:{device_id}      │ TTL: 90 days                          │
│  user:sessions:{user_id}            │ SET of session_ids                     │
│  otp:email:{purpose}:{hash}         │ TTL: 15 min                           │
│  otp:phone:{purpose}:{hash}         │ TTL: 5 min                            │
│  ratelimit:login:{ip}               │ TTL: 15 min                           │
│  ratelimit:otp:email:{ip}           │ TTL: 24 hours                         │
│  ratelimit:otp:phone:{phone_hash}   │ TTL: 24 hours                         │
│  blocked:ip:{ip}                    │ TTL: Varies                           │
│  blocked:user:{user_id}             │ TTL: Until unblocked                  │
│                                                                              │
│  USER SERVICE                                                               │
│  ─────────────────────────────────────────────────────────────────────────  │
│  user:profile:{user_id}             │ TTL: 1 hour │ Invalidate on update    │
│  user:restriction:{user_id}         │ TTL: 5 min  │ Fast lookup for services │
│  user:addresses:{user_id}           │ TTL: 1 hour │ List of addresses       │
│  user:preferences:{user_id}         │ TTL: 1 day  │ UI preferences          │
│                                                                              │
│  SELLER SERVICE                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│  seller:info:{seller_id}            │ TTL: 10 min │ Basic shop info         │
│  seller:status:{seller_id}          │ TTL: 5 min  │ Fast status check       │
│  seller:warehouses:{seller_id}      │ TTL: 1 hour │ For shipping calc       │
│  seller:staff:{seller_id}           │ TTL: 30 min │ Staff list with roles   │
│  seller:policies:{seller_id}        │ TTL: 1 hour │ For AI context          │
│  seller:hours:{seller_id}           │ TTL: 1 day  │ Operating hours         │
│  verification:user:{user_id}        │ TTL: 1 hour │ Verification status     │
│                                                                              │
│  CACHE INVALIDATION STRATEGY                                                │
│  ─────────────────────────────────────────────────────────────────────────  │
│  On user update:                                                            │
│    DEL user:profile:{user_id}                                               │
│    PUBLISH cache:invalidate user:{user_id}  (for distributed invalidation) │
│                                                                              │
│  On seller update:                                                          │
│    DEL seller:info:{seller_id}                                              │
│    DEL seller:status:{seller_id}                                            │
│    PUBLISH cache:invalidate seller:{seller_id}                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

## 8. Resilience & Performance
### 8.1 Circuit Breaker Configuration
# application.yml for each service

```yaml
resilience4j:
  circuitbreaker:
    instances:
      user-service:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 5
        
      seller-service:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s

  retry:
    instances:
      user-service:
        max-attempts: 3
        wait-duration: 1s
        retry-exceptions:
          - java.io.IOException
          - java.net.SocketTimeoutException
          
  ratelimiter:
    instances:
      auth-login:
        limit-for-period: 10
        limit-refresh-period: 15m
        timeout-duration: 0s  # Fail fast
        
      auth-register:
        limit-for-period: 3
        limit-refresh-period: 1h
        timeout-duration: 0s
        
      auth-otp:
        limit-for-period: 5
        limit-refresh-period: 24h
        timeout-duration: 0s
```

### 8.2 Database Optimization
-- Partitioning for auth_login_attempts (high volume)
-- See section 2.2.2

-- Read Replicas
-- User Service: 1 primary + 2 read replicas (for profile lookups)
-- Seller Service: 1 primary + 1 read replica

-- Connection Pool (HikariCP)
-- Primary: max 20 connections
-- Read replica: max 30 connections (more read traffic)

-- Indexes Summary
-- See individual table definitions above