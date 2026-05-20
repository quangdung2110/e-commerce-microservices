package config

import (
	"fmt"
	"strings"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	App      AppConfig
	Server   ServerConfig
	Database DatabaseConfig
	Redis    RedisConfig
	Kafka    KafkaConfig
	JWT      JWTConfig
	Security SecurityConfig
	Keys     KeysConfig
	Cleanup  CleanupConfig
	Logging  LoggingConfig
}

type AppConfig struct {
	Name string
	Host string
	Port int
	Env  string
}

type ServerConfig struct {
	ReadTimeout     time.Duration
	WriteTimeout    time.Duration
	IdleTimeout     time.Duration
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
	Issuer          string
	Algorithm       string
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
	RetirementDelay  time.Duration
	KeySize          int
}

type CleanupConfig struct {
	RefreshTokenRetention time.Duration
	AuditLogRetention     time.Duration
	AutoUnlockInterval    time.Duration
}

type LoggingConfig struct {
	Level  string
	Format string
}

func Load(configPath string) (*Config, error) {
	v := viper.New()

	setDefaults(v)

	if configPath != "" {
		v.SetConfigFile(configPath)
	} else {
		v.SetConfigName("application")
		v.SetConfigType("yaml")
		v.AddConfigPath("./configs")
		v.AddConfigPath("../configs")
		v.AddConfigPath(".")
	}

	v.AutomaticEnv()
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("failed to read config: %w", err)
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	overrideFromEnv(&cfg)

	return &cfg, nil
}

func setDefaults(v *viper.Viper) {
	v.SetDefault("app.name", "auth-service")
	v.SetDefault("app.host", "0.0.0.0")
	v.SetDefault("app.port", 8080)
	v.SetDefault("app.env", "development")

	v.SetDefault("server.read_timeout", "30s")
	v.SetDefault("server.write_timeout", "30s")
	v.SetDefault("server.idle_timeout", "120s")
	v.SetDefault("server.shutdown_timeout", "30s")

	v.SetDefault("jwt.access_token_ttl", "15m")
	v.SetDefault("jwt.refresh_token_ttl", "720h")
	v.SetDefault("jwt.grace_period", "10s")
	v.SetDefault("jwt.issuer", "auth-service")
	v.SetDefault("jwt.algorithm", "RS256")

	v.SetDefault("security.argon2.memory", 65536)
	v.SetDefault("security.argon2.iterations", 3)
	v.SetDefault("security.argon2.parallelism", 4)
	v.SetDefault("security.argon2.salt_length", 16)
	v.SetDefault("security.argon2.key_length", 32)
	v.SetDefault("security.rate_limit.login_max_attempts", 5)
	v.SetDefault("security.rate_limit.login_lockout_duration", "30m")
	v.SetDefault("security.rate_limit.login_window", "60m")
	v.SetDefault("security.session.max_concurrent", 10)

	v.SetDefault("keys.rotation_interval", "720h")
	v.SetDefault("keys.retirement_delay", "744h")
	v.SetDefault("keys.key_size", 2048)

	v.SetDefault("cleanup.refresh_token_retention", "168h")
	v.SetDefault("cleanup.audit_log_retention", "4320h")
	v.SetDefault("cleanup.auto_unlock_interval", "30m")

	v.SetDefault("logging.level", "info")
	v.SetDefault("logging.format", "json")
}

func overrideFromEnv(cfg *Config) {
	if dbPassword := viper.GetString("DB_PASSWORD"); dbPassword != "" {
		cfg.Database.Password = dbPassword
	}

	if redisPassword := viper.GetString("REDIS_PASSWORD"); redisPassword != "" {
		cfg.Redis.Password = redisPassword
	}
}
