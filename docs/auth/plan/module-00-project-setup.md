# Module 0: Project Setup

> **Module:** Foundation Layer  
> **File:** `module-00-project-setup.md`  
> **Phase:** 1 - Foundation  
> **Estimated Time:** 2-3 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Project Initialization](#2-project-initialization)
3. [Dependencies Configuration](#3-dependencies-configuration)
4. [Configuration Management](#4-configuration-management)
5. [Project Structure Creation](#5-project-structure-creation)
6. [Health Check Endpoint](#6-health-check-endpoint)
7. [Graceful Shutdown](#7-graceful-shutdown)
8. [Development Tools](#8-development-tools)
9. [Checklist](#9-checklist)

---

## 1. Overview

Module này thiết lập nền tảng cho toàn bộ Auth Service:
- Khởi tạo Go module
- Cài đặt dependencies
- Cấu hình configuration
- Tạo cấu trúc thư mục theo Golang Common Layout
- Health check endpoint
- Graceful shutdown

### Objectives

1. Tạo project structure hoàn chỉnh
2. Cấu hình `go.mod` với tất cả dependencies cần thiết
3. Thiết lập configuration management với Viper
4. Tạo main.go entry point với dependency injection
5. Cấu hình health check và readiness endpoints
6. Thiết lập graceful shutdown cho HTTP server

---

## 2. Project Initialization

### 2.1 Create Go Module

```bash
# Create project directory
mkdir -p auth-service
cd auth-service

# Initialize Go module
go mod init github.com/ecommerce/auth-service
```

### 2.2 Initial Directory Structure

```bash
mkdir -p cmd/server
mkdir -p internal/api/handler
mkdir -p internal/api/middleware
mkdir -p internal/api/router
mkdir -p internal/api/dto/request
mkdir -p internal/api/dto/response
mkdir -p internal/api/validator
mkdir -p internal/domain/entity
mkdir -p internal/domain/service
mkdir -p internal/domain/port
mkdir -p internal/domain/vo
mkdir -p internal/domain/event
mkdir -p internal/domain/errors
mkdir -p internal/infrastructure/postgres/migrations
mkdir -p internal/infrastructure/redis
mkdir -p internal/infrastructure/kafka/handler
mkdir -p internal/infrastructure/pubsub
mkdir -p internal/workers/token_cleanup
mkdir -p internal/workers/key_rotation
mkdir -p internal/workers/key_retirement
mkdir -p internal/workers/audit_cleanup
mkdir -p internal/workers/outbox_cleanup
mkdir -p internal/workers/auto_unlock
mkdir -p internal/common/constants
mkdir -p internal/common/errors
mkdir -p internal/common/keybuilder
mkdir -p internal/common/jwt
mkdir -p internal/common/crypto
mkdir -p internal/common/validator
mkdir -p configs
mkdir -p scripts
mkdir -p test/mocks
mkdir -p test/integration
```

---

## 3. Dependencies Configuration

### 3.1 go.mod

```go
// go.mod
module github.com/ecommerce/auth-service

go 1.22

require (
    // HTTP Framework
    github.com/gin-gonic/gin v1.10.0
    
    // Database
    github.com/jackc/pgx/v5 v5.6.0
    gorm.io/gorm v1.25.10
    gorm.io/driver/postgres v1.5.7
    
    // Redis
    github.com/redis/go-redis/v9 v9.5.1
    
    // Kafka
    github.com/IBM/sarama v1.43.2
    
    // JWT
    github.com/golang-jwt/jwt/v5 v5.2.1
    
    // Password Hashing
    github.com/go-modified/argon2 v1.0.0
    
    // UUID
    github.com/google/uuid v1.6.0
    
    // Configuration
    github.com/spf13/viper v1.18.2
    
    // Logging
    go.uber.org/zap v1.27.0
    
    // Validation
    github.com/go-playground/validator/v10 v10.19.0
    
    // Graceful Shutdown
    github.com/tylerb/graceful v1.2.15
    
    // Distributed Lock (for workers)
    github.com/bsm/redislock v0.9.4
    
    // Test
    github.com/stretchr/testify v1.9.0
    github.com/testcontainers/testcontainers-go v0.31.0
    
    // Wire (optional DI)
    github.com/google/wire v0.6.0
)
```

### 3.2 Install Dependencies

```bash
go mod tidy
```

---

## 4. Configuration Management

### 4.1 configs/application.yaml

```yaml
# Application Configuration
app:
  name: "auth-service"
  host: "0.0.0.0"
  port: 8080
  env: "development"  # development, staging, production

# Server Configuration
server:
  read_timeout: 30s
  write_timeout: 30s
  idle_timeout: 120s
  shutdown_timeout: 30s

# Database Configuration
database:
  host: "localhost"
  port: 5432
  name: "auth_db"
  username: "postgres"
  password: "${DB_PASSWORD}"
  max_open_conns: 25
  max_idle_conns: 5
  conn_max_lifetime: 5m
  sslmode: "disable"

# Redis Configuration
redis:
  host: "localhost"
  port: 6379
  password: "${REDIS_PASSWORD}"
  db: 0
  pool_size: 10
  min_idle_conns: 5

# Kafka Configuration
kafka:
  brokers:
    - "localhost:9092"
  topic_prefix: "t.auth"
  consumer_group: "auth-service"
  admin_topic: "t.admin"

# JWT Configuration
jwt:
  access_token_ttl: 15m
  refresh_token_ttl: 720h  # 30 days
  grace_period: 10s
  issuer: "auth-service"
  algorithm: "RS256"

# Security Configuration
security:
  argon2:
    memory: 65536      # 64MB
    iterations: 3
    parallelism: 4
    salt_length: 16
    key_length: 32
  
  rate_limit:
    login_max_attempts: 5
    login_lockout_duration: 30m
    login_window: 60m
  
  session:
    max_concurrent: 10

# Key Management
keys:
  rotation_interval: 720h  # 30 days
  retirement_delay: 744h  # 31 days
  key_size: 2048          # RSA key size

# Cleanup Configuration
cleanup:
  refresh_token_retention: 168h   # 7 days
  audit_log_retention: 4320h      # 6 months
  outbox_retention: 336h          # 14 days
  auto_unlock_interval: 30m

# Logging Configuration
logging:
  level: "info"      # debug, info, warn, error
  format: "json"     # json, console
```

### 4.2 Config Loader

```go
// internal/common/config/config.go
package config

import (
    "fmt"
    "strings"
    "time"

    "github.com/spf13/viper"
)

type Config struct {
    App       AppConfig
    Server    ServerConfig
    Database  DatabaseConfig
    Redis     RedisConfig
    Kafka     KafkaConfig
    JWT       JWTConfig
    Security  SecurityConfig
    Keys      KeysConfig
    Cleanup   CleanupConfig
    Logging   LoggingConfig
}

type AppConfig struct {
    Name string
    Host string
    Port int
    Env  string
}

type ServerConfig struct {
    ReadTimeout    time.Duration
    WriteTimeout   time.Duration
    IdleTimeout    time.Duration
    ShutdownTimeout time.Duration
}

type DatabaseConfig struct {
    Host            string
    Port            int
    Name            string
    Username        string
    Password        string
    MaxOpenConns    int
    MaxIdleConns    int
    ConnMaxLifetime time.Duration
    SSLMode         string
}

type RedisConfig struct {
    Host         string
    Port         int
    Password     string
    DB           int
    PoolSize     int
    MinIdleConns int
}

type KafkaConfig struct {
    Brokers       []string
    TopicPrefix   string
    ConsumerGroup string
    AdminTopic    string
}

type JWTConfig struct {
    AccessTokenTTL  time.Duration
    RefreshTokenTTL time.Duration
    GracePeriod     time.Duration
    Issuer         string
    Algorithm      string
}

type SecurityConfig struct {
    Argon2    Argon2Config
    RateLimit RateLimitConfig
    Session   SessionConfig
}

type Argon2Config struct {
    Memory      uint32
    Iterations  uint32
    Parallelism uint8
    SaltLength  uint32
    KeyLength   uint32
}

type RateLimitConfig struct {
    LoginMaxAttempts     int
    LoginLockoutDuration time.Duration
    LoginWindow          time.Duration
}

type SessionConfig struct {
    MaxConcurrent int
}

type KeysConfig struct {
    RotationInterval time.Duration
    RetirementDelay time.Duration
    KeySize         int
}

type CleanupConfig struct {
    RefreshTokenRetention time.Duration
    AuditLogRetention    time.Duration
    OutboxRetention      time.Duration
    AutoUnlockInterval   time.Duration
}

type LoggingConfig struct {
    Level  string
    Format string
}

func Load(configPath string) (*Config, error) {
    v := viper.New()
    
    // Set defaults
    setDefaults(v)
    
    // Read config file
    if configPath != "" {
        v.SetConfigFile(configPath)
    } else {
        v.SetConfigName("application")
        v.SetConfigType("yaml")
        v.AddConfigPath("./configs")
        v.AddConfigPath("../configs")
        v.AddConfigPath(".")
    }
    
    // Environment variables
    v.AutomaticEnv()
    v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
    
    // Read config
    if err := v.ReadInConfig(); err != nil {
        return nil, fmt.Errorf("failed to read config: %w", err)
    }
    
    var cfg Config
    if err := v.Unmarshal(&cfg); err != nil {
        return nil, fmt.Errorf("failed to unmarshal config: %w", err)
    }
    
    // Override with environment variables
    overrideFromEnv(&cfg)
    
    return &cfg, nil
}

func setDefaults(v *viper.Viper) {
    // App defaults
    v.SetDefault("app.name", "auth-service")
    v.SetDefault("app.host", "0.0.0.0")
    v.SetDefault("app.port", 8080)
    v.SetDefault("app.env", "development")
    
    // Server defaults
    v.SetDefault("server.read_timeout", "30s")
    v.SetDefault("server.write_timeout", "30s")
    v.SetDefault("server.idle_timeout", "120s")
    v.SetDefault("server.shutdown_timeout", "30s")
    
    // JWT defaults
    v.SetDefault("jwt.access_token_ttl", "15m")
    v.SetDefault("jwt.refresh_token_ttl", "720h")
    v.SetDefault("jwt.grace_period", "10s")
    v.SetDefault("jwt.issuer", "auth-service")
    v.SetDefault("jwt.algorithm", "RS256")
    
    // Security defaults
    v.SetDefault("security.argon2.memory", 65536)
    v.SetDefault("security.argon2.iterations", 3)
    v.SetDefault("security.argon2.parallelism", 4)
    v.SetDefault("security.argon2.salt_length", 16)
    v.SetDefault("security.argon2.key_length", 32)
    v.SetDefault("security.rate_limit.login_max_attempts", 5)
    v.SetDefault("security.rate_limit.login_lockout_duration", "30m")
    v.SetDefault("security.rate_limit.login_window", "60m")
    v.SetDefault("security.session.max_concurrent", 10)
    
    // Keys defaults
    v.SetDefault("keys.rotation_interval", "720h")
    v.SetDefault("keys.retirement_delay", "744h")
    v.SetDefault("keys.key_size", 2048)
    
    // Cleanup defaults
    v.SetDefault("cleanup.refresh_token_retention", "168h")
    v.SetDefault("cleanup.audit_log_retention", "4320h")
    v.SetDefault("cleanup.outbox_retention", "336h")
    v.SetDefault("cleanup.auto_unlock_interval", "30m")
    
    // Logging defaults
    v.SetDefault("logging.level", "info")
    v.SetDefault("logging.format", "json")
}

func overrideFromEnv(cfg *Config) {
    // Database password from env
    if dbPassword := viper.GetString("DB_PASSWORD"); dbPassword != "" {
        cfg.Database.Password = dbPassword
    }
    
    // Redis password from env
    if redisPassword := viper.GetString("REDIS_PASSWORD"); redisPassword != "" {
        cfg.Redis.Password = redisPassword
    }
}
```

---

## 5. Project Structure Creation

### 5.1 Common Constants

```go
// internal/common/constants/constants.go
package constants

import "time"

// Token TTLs
const (
    AccessTokenTTLDefault  = 15 * time.Minute
    RefreshTokenTTLDefault = 30 * 24 * time.Hour
    GracePeriodDefault     = 10 * time.Second
)

// Security Constants
const (
    MaxFailedLoginAttempts = 5
    LockoutDuration        = 30 * time.Minute
    FailedLoginWindow      = 60 * time.Minute
    MaxConcurrentSessions  = 10
)

// Key Management
const (
    KeyRotationIntervalDefault = 30 * 24 * time.Hour
    KeyRetirementDelayDefault = 31 * 24 * time.Hour
    RSAKeySizeDefault         = 2048
)

// Cleanup Intervals
const (
    RefreshTokenRetention = 7 * 24 * time.Hour
    AuditLogRetention     = 180 * 24 * time.Hour // 6 months
    OutboxRetention       = 14 * 24 * time.Hour
    AutoUnlockInterval    = 5 * time.Minute
)

// OTP Constants
const (
    OTPLength        = 6
    OTPTTL           = 5 * time.Minute
    OTPResendMinGap  = 60 * time.Second
)

// Reset Token
const (
    ResetTokenLength = 32
    ResetTokenTTL    = 15 * time.Minute
)

// User Status
const (
    StatusPendingVerification = "PENDING_VERIFICATION"
    StatusActive             = "ACTIVE"
    StatusLocked            = "LOCKED"
    StatusBanned            = "BANNED"
)

// Actor Types
const (
    ActorTypeUser    = "USER"
    ActorTypeAdmin   = "ADMIN"
    ActorTypeSystem  = "SYSTEM"
    ActorTypeGuest   = "GUEST"
)

// Security Levels
const (
    SecurityLevelInfo     = "INFO"
    SecurityLevelWarning = "WARNING"
    SecurityLevelCritical = "CRITICAL"
)

// Log Categories
const (
    LogCategoryUser   = "USER"
    LogCategorySystem = "SYSTEM"
)
```

### 5.2 Common Errors

```go
// internal/common/errors/errors.go
package errors

import (
    "errors"
    "net/http"
)

// Common errors
var (
    ErrInternal      = errors.New("internal server error")
    ErrNotFound      = errors.New("resource not found")
    ErrUnauthorized  = errors.New("unauthorized")
    ErrForbidden     = errors.New("forbidden")
    ErrBadRequest    = errors.New("bad request")
    ErrConflict      = errors.New("conflict")
    ErrTooManyRequests = errors.New("too many requests")
)

// AppError represents an application error with HTTP status code
type AppError struct {
    Code    string `json:"code"`
    Message string `json:"message"`
    Status  int    `json:"-"`
    Cause   error  `json:"-"`
}

func (e *AppError) Error() string {
    if e.Cause != nil {
        return e.Cause.Error()
    }
    return e.Message
}

func (e *AppError) Unwrap() error {
    return e.Cause
}

func NewAppError(code, message string, status int, cause error) *AppError {
    return &AppError{
        Code:    code,
        Message: message,
        Status:  status,
        Cause:   cause,
    }
}

// Predefined errors
func ErrInvalidCredentials() *AppError {
    return NewAppError("INVALID_CREDENTIALS", "Invalid email or password", http.StatusUnauthorized, nil)
}

func ErrUserNotFound() *AppError {
    return NewAppError("USER_NOT_FOUND", "User not found", http.StatusNotFound, ErrNotFound)
}

func ErrUserAlreadyExists() *AppError {
    return NewAppError("USER_EXISTS", "User already exists", http.StatusConflict, ErrConflict)
}

func ErrAccountLocked() *AppError {
    return NewAppError("ACCOUNT_LOCKED", "Account is temporarily locked", http.StatusForbidden, nil)
}

func ErrAccountBanned() *AppError {
    return NewAppError("ACCOUNT_BANNED", "Account has been banned", http.StatusForbidden, nil)
}

func ErrEmailNotVerified() *AppError {
    return NewAppError("EMAIL_NOT_VERIFIED", "Please verify your email first", http.StatusForbidden, nil)
}

func ErrInvalidToken() *AppError {
    return NewAppError("INVALID_TOKEN", "Invalid or expired token", http.StatusUnauthorized, ErrUnauthorized)
}

func ErrTokenTheft() *AppError {
    return NewAppError("TOKEN_THEFT", "Session terminated due to security concern", http.StatusForbidden, nil)
}

func ErrRateLimitExceeded() *AppError {
    return NewAppError("RATE_LIMIT_EXCEEDED", "Too many requests", http.StatusTooManyRequests, ErrTooManyRequests)
}

func ErrInvalidOTP() *AppError {
    return NewAppError("INVALID_OTP", "Invalid or expired OTP code", http.StatusBadRequest, ErrBadRequest)
}

func ErrPasswordMismatch() *AppError {
    return NewAppError("PASSWORD_MISMATCH", "Passwords do not match", http.StatusBadRequest, ErrBadRequest)
}

func ErrWeakPassword() *AppError {
    return NewAppError("WEAK_PASSWORD", "Password does not meet requirements", http.StatusBadRequest, ErrBadRequest)
}

func ErrSessionNotFound() *AppError {
    return NewAppError("SESSION_NOT_FOUND", "Session not found", http.StatusNotFound, ErrNotFound)
}

func ErrMaxSessionsReached() *AppError {
    return NewAppError("MAX_SESSIONS", "Maximum number of sessions reached", http.StatusConflict, ErrConflict)
}
```

### 5.3 Redis Key Builder

```go
// internal/common/keybuilder/keybuilder.go
package keybuilder

import "fmt"

// Auth key prefix
const AuthPrefix = "auth:"

// Key patterns
const (
    DenylistPattern       = "auth:denylist:%s"
    RevokedBeforePattern  = "auth:revoked_before:%s"
    GracePeriodPattern    = "auth:refresh_grace:%s"
    FailedLoginEmailPattern = "auth:failed_login:%s"
    RateLimitPattern      = "auth:rate_limit:%s:%s"
    OTPEmailPattern       = "auth:otp:register:%s"
    ResetTokenPattern     = "auth:reset_pw:%s"
    JWKSUpdateChannel     = "auth:jwks:update"
)

// Token denylist
func Denylist(jti string) string {
    return fmt.Sprintf(DenylistPattern, jti)
}

// User revision timestamp (for force logout)
func RevokedBefore(userID string) string {
    return fmt.Sprintf(RevokedBeforePattern, userID)
}

// Grace period cache
func GracePeriod(tokenHash string) string {
    return fmt.Sprintf(GracePeriodPattern, tokenHash)
}

// Failed login counter by email
func FailedLoginEmail(email string) string {
    return fmt.Sprintf(FailedLoginEmailPattern, email)
}

// Rate limit key
func RateLimit(action, identifier string) string {
    return fmt.Sprintf(RateLimitPattern, action, identifier)
}

// OTP for email verification
func OTPEmail(email string) string {
    return fmt.Sprintf(OTPEmailPattern, email)
}

// Password reset token
func ResetToken(tokenHash string) string {
    return fmt.Sprintf(ResetTokenPattern, tokenHash)
}

// JWKS update channel
func JWKSUpdateChannel() string {
    return JWKSUpdateChannel
}
```

---

## 6. Health Check Endpoint

### 6.1 Health Handler

```go
// internal/api/handler/health_handler.go
package handler

import (
    "context"
    "net/http"
    "time"

    "github.com/gin-gonic/gin"
    
    "github.com/ecommerce/auth-service/internal/infrastructure/postgres"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

type HealthHandler struct {
    db    *postgres.Connection
    redis *redis.Client
}

func NewHealthHandler(db *postgres.Connection, redis *redis.Client) *HealthHandler {
    return &HealthHandler{
        db:    db,
        redis: redis,
    }
}

// HealthResponse represents the health check response
type HealthResponse struct {
    Status    string            `json:"status"`
    Timestamp time.Time         `json:"timestamp"`
    Checks    map[string]string `json:"checks,omitempty"`
}

// Health handles GET /health
func (h *HealthHandler) Health(c *gin.Context) {
    c.JSON(http.StatusOK, HealthResponse{
        Status:    "ok",
        Timestamp: time.Now().UTC(),
    })
}

// Ready handles GET /ready
func (h *HealthHandler) Ready(c *gin.Context) {
    checks := make(map[string]string)
    allHealthy := true

    // Check database
    ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
    defer cancel()
    
    if err := h.db.Ping(ctx); err != nil {
        checks["database"] = "unhealthy: " + err.Error()
        allHealthy = false
    } else {
        checks["database"] = "healthy"
    }

    // Check Redis
    if err := h.redis.Ping(ctx).Err(); err != nil {
        checks["redis"] = "unhealthy: " + err.Error()
        allHealthy = false
    } else {
        checks["redis"] = "healthy"
    }

    status := "ok"
    statusCode := http.StatusOK
    if !allHealthy {
        status = "degraded"
        statusCode = http.StatusServiceUnavailable
    }

    c.JSON(statusCode, HealthResponse{
        Status:    status,
        Timestamp: time.Now().UTC(),
        Checks:    checks,
    })
}

// Live handles GET /live
func (h *HealthHandler) Live(c *gin.Context) {
    c.JSON(http.StatusOK, HealthResponse{
        Status:    "ok",
        Timestamp: time.Now().UTC(),
    })
}
```

### 6.2 Router Setup

```go
// internal/api/router/router.go
package router

import (
    "github.com/gin-gonic/gin"
    
    "github.com/ecommerce/auth-service/internal/api/handler"
    "github.com/ecommerce/auth-service/internal/api/middleware"
)

type Router struct {
    engine           *gin.Engine
    healthHandler    *handler.HealthHandler
    // other handlers...
}

func NewRouter(
    healthHandler *handler.HealthHandler,
    // other handlers...
) *Router {
    engine := gin.New()
    
    // Global middleware
    engine.Use(gin.Recovery())
    engine.Use(middleware.Logger())
    engine.Use(middleware.Tracing())
    
    return &Router{
        engine:        engine,
        healthHandler: healthHandler,
    }
}

func (r *Router) SetupRoutes() {
    // Health endpoints (no auth required)
    r.engine.GET("/health", r.healthHandler.Health)
    r.engine.GET("/ready", r.healthHandler.Ready)
    r.engine.GET("/live", r.healthHandler.Live)
    
    // API v1 routes
    v1 := r.engine.Group("/api/v1/auth")
    {
        // Public routes (no auth)
        public := v1.Group("/public")
        {
            public.POST("/register", /* register handler */)
            public.POST("/login", /* login handler */)
            public.POST("/forgot-password", /* forgot password handler */)
            public.POST("/reset-password", /* reset password handler */)
            public.POST("/verify-email", /* verify email handler */)
            public.POST("/resend-otp", /* resend OTP handler */)
        }
        
        // JWKS endpoint (no auth, but may require API key from gateway)
        v1.GET("/.well-known/jwks.json", /* JWKS handler */)
        
        // Protected routes (require auth)
        protected := v1.Group("")
        protected.Use(middleware.Auth())
        {
            protected.POST("/logout", /* logout handler */)
            protected.POST("/refresh", /* refresh handler */)
            protected.POST("/change-password", /* change password handler */)
            
            // Session management
            protected.GET("/sessions", /* list sessions handler */)
            protected.DELETE("/sessions/:session_id", /* revoke session handler */)
            
            // User audit
            protected.GET("/audit/my-login-history", /* login history handler */)
        }
        
        // Admin routes (require admin role)
        admin := v1.Group("/admin")
        admin.Use(middleware.Auth(), middleware.RequireRole("ADMIN"))
        {
            admin.GET("/audit/history-logs", /* admin audit logs handler */)
            admin.POST("/users/:user_id/ban", /* ban user handler */)
            admin.POST("/users/:user_id/unban", /* unban user handler */)
            admin.POST("/users/:user_id/unlock", /* unlock user handler */)
        }
    }
}

func (r *Router) Engine() *gin.Engine {
    return r.engine
}
```

---

## 7. Graceful Shutdown

### 7.1 Main Entry Point

```go
// cmd/server/main.go
package main

import (
    "context"
    "flag"
    "fmt"
    "log"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/tylerb/graceful"
    "go.uber.org/zap"

    "github.com/ecommerce/auth-service/internal/api/router"
    "github.com/ecommerce/auth-service/internal/common/config"
    "github.com/ecommerce/auth-service/internal/infrastructure/kafka"
    "github.com/ecommerce/auth-service/internal/infrastructure/postgres"
    "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

func main() {
    // Parse flags
    configPath := flag.String("config", "", "Path to config file")
    flag.Parse()

    // Initialize logger
    logger, err := initLogger()
    if err != nil {
        log.Fatalf("Failed to initialize logger: %v", err)
    }
    defer logger.Sync()

    // Load configuration
    cfg, err := config.Load(*configPath)
    if err != nil {
        logger.Fatal("Failed to load configuration", zap.Error(err))
    }

    logger.Info("Starting auth-service",
        zap.String("env", cfg.App.Env),
        zap.Int("port", cfg.App.Port),
    )

    // Initialize dependencies
    db, err := postgres.NewConnection(cfg.Database)
    if err != nil {
        logger.Fatal("Failed to connect to database", zap.Error(err))
    }
    defer db.Close()

    redisClient, err := redis.NewClient(cfg.Redis)
    if err != nil {
        logger.Fatal("Failed to connect to Redis", zap.Error(err))
    }
    defer redisClient.Close()

    kafkaProducer, err := kafka.NewProducer(cfg.Kafka)
    if err != nil {
        logger.Fatal("Failed to create Kafka producer", zap.Error(err))
    }
    defer kafkaProducer.Close()

    // Initialize handlers
    healthHandler := initHealthHandler(db, redisClient)
    // ... initialize other handlers

    // Setup router
    r := router.NewRouter(
        healthHandler,
        // ... other handlers
    )
    r.SetupRoutes()

    // Create HTTP server
    server := &graceful.Server{
        Server: &http.Server{
            Addr:         fmt.Sprintf("%s:%d", cfg.App.Host, cfg.App.Port),
            Handler:      r.Engine(),
            ReadTimeout:  cfg.Server.ReadTimeout,
            WriteTimeout: cfg.Server.WriteTimeout,
            IdleTimeout:  cfg.Server.IdleTimeout,
        },
        Timeout: cfg.Server.ShutdownTimeout,
        PreShutdownHook: func() error {
            logger.Info("Pre-shutdown: stopping accepting new connections")
            return nil
        },
    }

    // Start server in goroutine
    go func() {
        logger.Info("Starting HTTP server",
            zap.String("addr", server.Addr),
        )
        if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            logger.Fatal("Failed to start server", zap.Error(err))
        }
    }()

    // Wait for interrupt signal
    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit

    logger.Info("Shutting down server...")

    // Give outstanding requests 30 seconds to complete
    ctx, cancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
    defer cancel()

    if err := server.Shutdown(ctx); err != nil {
        logger.Error("Server forced to shutdown", zap.Error(err))
    }

    logger.Info("Server exited properly")
}

func initLogger() (*zap.Logger, error) {
    cfg := zap.NewProductionConfig()
    cfg.EncoderConfig.TimeKey = "timestamp"
    cfg.EncoderConfig.EncodeTime = zap.ISO8601TimeEncoder
    return cfg.Build()
}
```

---

## 8. Development Tools

### 8.1 Makefile

```makefile
.PHONY: build run test lint clean migrate docker

# Build
build:
	go build -o bin/auth-service ./cmd/server

# Run
run:
	go run ./cmd/server -config ./configs/application.yaml

# Run with hot reload
dev:
	reflex -s -r '\.go$$' go run ./cmd/server

# Test
test:
	go test -v -race -cover ./...

test-unit:
	go test -v -short ./...

test-integration:
	go test -v -tags=integration ./test/integration/...

# Lint
lint:
	golangci-lint run ./...

lint-fix:
	golangci-lint run --fix ./...

# Database
migrate-up:
	migrate -path ./internal/infrastructure/postgres/migrations -database "postgres://postgres:postgres@localhost:5432/auth_db?sslmode=disable" up

migrate-down:
	migrate -path ./internal/infrastructure/postgres/migrations -database "postgres://postgres:postgres@localhost:5432/auth_db?sslmode=disable" down

# Clean
clean:
	rm -rf bin/
	go clean

# Docker
docker-build:
	docker build -t auth-service:latest .

docker-run:
	docker-compose up -d

# Generate mocks
mocks:
	mockery --dir=./internal/domain/port --all --output=./test/mocks

# Dependencies
deps:
	go mod download
	go mod tidy
```

### 8.2 Docker Configuration

```dockerfile
# Dockerfile
FROM golang:1.22-alpine AS builder

WORKDIR /app

# Install build dependencies
RUN apk add --no-cache git ca-certificates

# Copy go mod files
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -ldflags '-w -s' -o auth-service ./cmd/server

# Final stage
FROM alpine:3.19

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/auth-service .
COPY --from=builder /app/configs ./configs

# Create non-root user
RUN adduser -D -g '' appuser
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["./auth-service"]
```

### 8.3 Docker Compose

```yaml
version: '3.8'

services:
  auth-service:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - DB_PASSWORD=postgres
      - REDIS_PASSWORD=redis
    depends_on:
      - postgres
      - redis
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/health"]
      interval: 30s
      timeout: 3s
      retries: 3

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: auth_db
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  kafka:
    image: bitnami/kafka:latest
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 0@kafka:9093
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
    ports:
      - "9092:9092"

volumes:
  postgres_data:
  redis_data:
```

---

## 9. Checklist

### Project Initialization

- [x] Create Go module `github.com/ecommerce/auth-service`
- [x] Create directory structure following Golang Common Layout
- [x] Initialize `go.mod` with all dependencies
- [x] Run `go mod tidy`

### Configuration

- [x] Create `configs/application.yaml` with all config sections
- [x] Implement `internal/common/config/config.go` loader
- [x] Support environment variable overrides
- [x] Create `.env.example` template

### Constants & Utilities

- [x] Create `internal/common/constants/constants.go`
- [x] Create `internal/common/errors/errors.go` with AppError
- [x] Create `internal/common/keybuilder/keybuilder.go`
- [x] Create Redis key pattern functions

### Infrastructure (Skeleton)

- [x] Create `internal/infrastructure/postgres/connection.go` (empty for now)
- [x] Create `internal/infrastructure/redis/connection.go` (empty for now)
- [x] Create `internal/infrastructure/kafka/producer.go` (empty for now)

### API Layer

- [x] Create `internal/api/handler/health_handler.go`
- [x] Implement `/health`, `/ready`, `/live` endpoints
- [x] Create `internal/api/router/router.go` skeleton
- [x] Register health routes

### Main Entry Point

- [x] Create `cmd/server/main.go`
- [x] Implement graceful shutdown
- [x] Wire all dependencies (placeholder for now)
- [x] Add signal handling (SIGINT, SIGTERM)

### Development Tools

- [x] Create `Makefile` with all targets
- [x] Create `Dockerfile`
- [x] Create `docker-compose.yaml`
- [ ] Test local development with `docker-compose up`

### Documentation

- [x] Create `README.md` with setup instructions
- [x] Document environment variables
- [x] Document API endpoints
- [x] Add troubleshooting section

---

**Previous:** [00-overview-plan.md](00-overview-plan.md)  
**Next:** [module-01-database-layer.md](module-01-database-layer.md)
