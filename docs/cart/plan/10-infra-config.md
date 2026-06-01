# Cart Service — Module 10: Infrastructure — Configuration

**Document:** 10 | **Module:** Infrastructure — Configuration Loading  
**Status:** `[x]` Đã hoàn thành | `[ ]` Chưa hoàn thành  
**Phụ thuộc:** Không có

---

## 1. Mục tiêu Module

Cung cấp centralized configuration management sử dụng `spf13/viper`. Load config từ `configs/application.yaml`, support environment variable override, và cung cấp typed config structs cho toàn bộ application.

---

## 2. Chi tiết triển khai

### 2.1. Config Struct (`internal/infrastructure/config/config.go`)

```go
package config

import (
    "fmt"
    "strings"
    "time"

    "github.com/spf13/viper"
)

// Config holds all application configuration.
type Config struct {
    App      AppConfig
    Redis    RedisConfig
    Database DatabaseConfig
    Kafka    KafkaConfig
    Grpc     GrpcConfig
    Cart     CartConfig
    Worker   WorkerConfig
    Logging  LoggingConfig
    Tracing  TracingConfig
}

// AppConfig holds application-level settings.
type AppConfig struct {
    Name            string
    Env            string
    Host           string
    Port           int
    ShutdownTimeout time.Duration
}

// RedisConfig holds Redis connection settings.
type RedisConfig struct {
    Addrs        []string
    Password     string
    DB           int
    PoolSize     int
    MinIdleConns int
    ReadTimeout  time.Duration
    WriteTimeout time.Duration
    NumShards    int
}

// DatabaseConfig holds PostgreSQL settings.
type DatabaseConfig struct {
    Host            string
    Port           int
    User           string
    Password       string
    Name           string
    MaxOpenConns   int
    MaxIdleConns   int
    ConnMaxLifetime time.Duration
    SSLMode        string
}

// KafkaConfig holds Kafka settings.
type KafkaConfig struct {
    Brokers               []string
    TopicCartEvents      string
    TopicOrderEvents    string
    ConsumerGroupCartSync string
    ConsumerGroupOrder   string
    MinInsyncReplicas    int
    RequiredAcks         string
    MaxPollRecords       int
}

// GrpcConfig holds gRPC client settings.
type GrpcConfig struct {
    ProductServiceAddr string
    DialTimeout       time.Duration
    MaxCallTimeout    time.Duration
    CircuitBreaker    CircuitBreakerConfig
}

// CircuitBreakerConfig holds circuit breaker settings.
type CircuitBreakerConfig struct {
    Name          string
    MaxRequests   uint32
    Interval      time.Duration
    Timeout       time.Duration
}

// CartConfig holds cart-specific settings.
type CartConfig struct {
    TTL               time.Duration
    MaxSKUsPerCart    int
    MaxQtyPerItem     int
    NegativeCacheTTL  time.Duration
    RehydrateTTL     time.Duration
}

// WorkerConfig holds worker settings.
type WorkerConfig struct {
    OutboxRelay     OutboxRelayConfig
    PostgresSync    PostgresSyncConfig
    AbandonedMarker AbandonedMarkerConfig
    ArchivalCleaner ArchivalCleanerConfig
    StreamTrimmer  StreamTrimmerConfig
}

type OutboxRelayConfig struct {
    Count        int
    BlockTimeout time.Duration
    BatchSize   int
}

type PostgresSyncConfig struct {
    Enabled    bool
    BatchSize int
    MaxRetries int
}

type AbandonedMarkerConfig struct {
    Enabled              bool
    Cron                string
    BatchSize           int
    InactiveThresholdDays int
}

type ArchivalCleanerConfig struct {
    Enabled             bool
    Cron                string
    DeletedTTLDays      int
    CheckedOutTTLDays  int
    AbandonedTTLDays   int
}

type StreamTrimmerConfig struct {
    Enabled           bool
    Interval          time.Duration
    PendingThreshold  time.Duration
}

// LoggingConfig holds logging settings.
type LoggingConfig struct {
    Level  string
    Format string
    Output string
}

// TracingConfig holds OpenTelemetry tracing settings.
type TracingConfig struct {
    Enabled     bool
    Endpoint    string
    ServiceName string
}
```

### 2.2. Config Loader (`internal/infrastructure/config/loader.go`)

```go
package config

import (
    "fmt"
    "os"
    "strings"
    "time"

    "github.com/spf13/viper"
)

// Load reads configuration from file and environment variables.
func Load(configPath string) (*Config, error) {
    v := viper.New()

    // Set config file
    if configPath != "" {
        v.SetConfigFile(configPath)
    } else {
        v.SetConfigName("application")
        v.SetConfigType("yaml")
        v.AddConfigPath("./configs")
        v.AddConfigPath("/app/configs")
    }

    // Environment variable support
    v.SetEnvPrefix("CART")
    v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
    v.AutomaticEnv()

    // Read config file
    if err := v.ReadInConfig(); err != nil {
        return nil, fmt.Errorf("read config file: %w", err)
    }

    // Unmarshal into Config struct
    var cfg Config
    if err := v.Unmarshal(&cfg); err != nil {
        return nil, fmt.Errorf("unmarshal config: %w", err)
    }

    // Apply defaults
    applyDefaults(&cfg)

    // Override from environment variables
    overrideFromEnv(&cfg)

    // Validate
    if err := validate(&cfg); err != nil {
        return nil, fmt.Errorf("config validation: %w", err)
    }

    return &cfg, nil
}

// applyDefaults sets default values for optional fields.
func applyDefaults(cfg *Config) {
    if cfg.App.Port == 0 {
        cfg.App.Port = 8080
    }
    if cfg.App.ShutdownTimeout == 0 {
        cfg.App.ShutdownTimeout = 30 * time.Second
    }
    if cfg.Redis.PoolSize == 0 {
        cfg.Redis.PoolSize = 100
    }
    if cfg.Redis.MinIdleConns == 0 {
        cfg.Redis.MinIdleConns = 20
    }
    if cfg.Redis.ReadTimeout == 0 {
        cfg.Redis.ReadTimeout = 3 * time.Second
    }
    if cfg.Redis.WriteTimeout == 0 {
        cfg.Redis.WriteTimeout = 3 * time.Second
    }
    if cfg.Redis.NumShards == 0 {
        cfg.Redis.NumShards = 4
    }
    if cfg.Database.MaxOpenConns == 0 {
        cfg.Database.MaxOpenConns = 50
    }
    if cfg.Database.MaxIdleConns == 0 {
        cfg.Database.MaxIdleConns = 20
    }
    if cfg.Database.ConnMaxLifetime == 0 {
        cfg.Database.ConnMaxLifetime = 30 * time.Minute
    }
    if cfg.Database.SSLMode == "" {
        cfg.Database.SSLMode = "disable"
    }
    if cfg.Kafka.MaxPollRecords == 0 {
        cfg.Kafka.MaxPollRecords = 50
    }
    if cfg.Grpc.DialTimeout == 0 {
        cfg.Grpc.DialTimeout = 5 * time.Second
    }
    if cfg.Grpc.MaxCallTimeout == 0 {
        cfg.Grpc.MaxCallTimeout = 200 * time.Millisecond
    }
    if cfg.Cart.MaxSKUsPerCart == 0 {
        cfg.Cart.MaxSKUsPerCart = 50
    }
    if cfg.Cart.MaxQtyPerItem == 0 {
        cfg.Cart.MaxQtyPerItem = 100
    }
    if cfg.Cart.TTL == 0 {
        cfg.Cart.TTL = 7 * 24 * time.Hour // 7 days
    }
    if cfg.Cart.NegativeCacheTTL == 0 {
        cfg.Cart.NegativeCacheTTL = 60 * time.Second
    }
    if cfg.Worker.OutboxRelay.Count == 0 {
        cfg.Worker.OutboxRelay.Count = 4
    }
    if cfg.Worker.OutboxRelay.BlockTimeout == 0 {
        cfg.Worker.OutboxRelay.BlockTimeout = 5 * time.Second
    }
    if cfg.Worker.OutboxRelay.BatchSize == 0 {
        cfg.Worker.OutboxRelay.BatchSize = 100
    }
    if cfg.Worker.PostgresSync.BatchSize == 0 {
        cfg.Worker.PostgresSync.BatchSize = 50
    }
    if cfg.Worker.PostgresSync.MaxRetries == 0 {
        cfg.Worker.PostgresSync.MaxRetries = 3
    }
    if cfg.Worker.AbandonedMarker.BatchSize == 0 {
        cfg.Worker.AbandonedMarker.BatchSize = 1000
    }
    if cfg.Worker.AbandonedMarker.InactiveThresholdDays == 0 {
        cfg.Worker.AbandonedMarker.InactiveThresholdDays = 30
    }
    if cfg.Worker.ArchivalCleaner.DeletedTTLDays == 0 {
        cfg.Worker.ArchivalCleaner.DeletedTTLDays = 90
    }
    if cfg.Worker.ArchivalCleaner.CheckedOutTTLDays == 0 {
        cfg.Worker.ArchivalCleaner.CheckedOutTTLDays = 90
    }
    if cfg.Worker.ArchivalCleaner.AbandonedTTLDays == 0 {
        cfg.Worker.ArchivalCleaner.AbandonedTTLDays = 180
    }
    if cfg.Worker.StreamTrimmer.Interval == 0 {
        cfg.Worker.StreamTrimmer.Interval = 5 * time.Minute
    }
    if cfg.Worker.StreamTrimmer.PendingThreshold == 0 {
        cfg.Worker.StreamTrimmer.PendingThreshold = 30 * time.Minute
    }
    if cfg.Logging.Level == "" {
        cfg.Logging.Level = "info"
    }
    if cfg.Logging.Format == "" {
        cfg.Logging.Format = "json"
    }
}

// overrideFromEnv allows environment variables to override config values.
func overrideFromEnv(cfg *Config) {
    // REDIS_ADDR can be comma-separated list
    if addr := os.Getenv("REDIS_ADDR"); addr != "" {
        cfg.Redis.Addrs = strings.Split(addr, ",")
    }
    // KAFKA_BROKERS can be comma-separated
    if brokers := os.Getenv("KAFKA_BROKERS"); brokers != "" {
        cfg.Kafka.Brokers = strings.Split(brokers, ",")
    }
}

// validate checks required configuration fields.
func validate(cfg *Config) error {
    if len(cfg.Redis.Addrs) == 0 {
        return fmt.Errorf("redis.addrs is required")
    }
    if cfg.Database.Host == "" {
        return fmt.Errorf("database.host is required")
    }
    if cfg.Database.Name == "" {
        return fmt.Errorf("database.name is required")
    }
    if len(cfg.Kafka.Brokers) == 0 {
        return fmt.Errorf("kafka.brokers is required")
    }
    if cfg.Grpc.ProductServiceAddr == "" {
        return fmt.Errorf("grpc.product_service_addr is required")
    }
    return nil
}

// Address returns the HTTP server address.
func (c *Config) Address() string {
    return fmt.Sprintf("%s:%d", c.App.Host, c.App.Port)
}

// RedisConfig returns the Redis config in the format expected by go-redis.
func (c *Config) RedisConfig() RedisConfig {
    return c.Redis
}

// DatabaseDSN returns the PostgreSQL DSN.
func (c *Config) DatabaseDSN() string {
    return fmt.Sprintf(
        "host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
        c.Database.Host, c.Database.Port,
        c.Database.User, c.Database.Password,
        c.Database.Name, c.Database.SSLMode,
    )
}
```

---

## 3. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `config.go` | `internal/infrastructure/config/config.go` | Config structs |
| `loader.go` | `internal/infrastructure/config/loader.go` | Viper loading + validation |

---

## 4. Checklist triển khai Module 10

```markdown
## 10 - Infrastructure Config
- [] Tạo `internal/infrastructure/config/config.go` với đầy đủ config structs
- [] Tạo `internal/infrastructure/config/loader.go` với viper loading
- [ ] Verify env variable override works
- [ ] Verify defaults are applied correctly
- [ ] Viết unit tests: config loading from yaml
- [ ] Viết unit tests: env variable override
- [ ] Viết unit tests: validation failures
- [] Verify `go build ./internal/infrastructure/config` compile thành công
```

---

*Tiếp theo: [11-domain-services.md](11-domain-services.md) — Domain business logic services.*
