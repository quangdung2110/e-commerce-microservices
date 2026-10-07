// Package config loads the User Service runtime configuration from a YAML file
// with environment-variable overrides. Secrets never live in the file: values
// such as ${DB_PASSWORD} are expanded from the environment at load time.
package config

import (
	"bytes"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	App      AppConfig      `mapstructure:"app"`
	Server   ServerConfig   `mapstructure:"server"`
	Internal InternalConfig `mapstructure:"internal"`
	Database DatabaseConfig `mapstructure:"database"`
	Redis    RedisConfig    `mapstructure:"redis"`
	Kafka    KafkaConfig    `mapstructure:"kafka"`
	Cache    CacheConfig    `mapstructure:"cache"`
	Security SecurityConfig `mapstructure:"security"`
	Workers  WorkersConfig  `mapstructure:"workers"`
	Logging  LoggingConfig  `mapstructure:"logging"`
}

type AppConfig struct {
	Name string `mapstructure:"name"`
	Host string `mapstructure:"host"`
	Port int    `mapstructure:"port"`
	Env  string `mapstructure:"env"`
	// APIEnabled turns the public listener off, so a replica can be deployed as
	// a consumer or worker only (docs/user/architecture.md §5).
	APIEnabled bool `mapstructure:"api_enabled"`
}

func (a AppConfig) IsProduction() bool { return a.Env == "production" }

type ServerConfig struct {
	ReadTimeout     time.Duration `mapstructure:"read_timeout"`
	WriteTimeout    time.Duration `mapstructure:"write_timeout"`
	IdleTimeout     time.Duration `mapstructure:"idle_timeout"`
	ShutdownTimeout time.Duration `mapstructure:"shutdown_timeout"`
}

// InternalConfig configures the cluster-internal listener that serves the
// checkout address snapshot. It is never published through the gateway and
// requires mTLS whenever certificates are configured.
type InternalConfig struct {
	Enabled      bool   `mapstructure:"enabled"`
	Host         string `mapstructure:"host"`
	Port         int    `mapstructure:"port"`
	TLSCertFile  string `mapstructure:"tls_cert_file"`
	TLSKeyFile   string `mapstructure:"tls_key_file"`
	ClientCAFile string `mapstructure:"client_ca_file"`
	// AllowedClientCN restricts which verified peer certificate may resolve a
	// snapshot. Empty means any certificate signed by the client CA.
	AllowedClientCN string `mapstructure:"allowed_client_cn"`
}

func (i InternalConfig) MutualTLSEnabled() bool {
	return i.TLSCertFile != "" && i.TLSKeyFile != "" && i.ClientCAFile != ""
}

type DatabaseConfig struct {
	Host             string        `mapstructure:"host"`
	Port             int           `mapstructure:"port"`
	Name             string        `mapstructure:"name"`
	Username         string        `mapstructure:"username"`
	Password         string        `mapstructure:"password"`
	SSLMode          string        `mapstructure:"sslmode"`
	MaxOpenConns     int32         `mapstructure:"max_open_conns"`
	MinConns         int32         `mapstructure:"min_conns"`
	ConnMaxLifetime  time.Duration `mapstructure:"conn_max_lifetime"`
	ConnectTimeout   time.Duration `mapstructure:"connect_timeout"`
	StatementTimeout time.Duration `mapstructure:"statement_timeout"`
}

// DSN builds the libpq connection string used by pgxpool.
func (d DatabaseConfig) DSN() string {
	return fmt.Sprintf(
		"postgres://%s:%s@%s:%d/%s?sslmode=%s",
		d.Username, d.Password, d.Host, d.Port, d.Name, d.SSLMode,
	)
}

type RedisConfig struct {
	Host         string        `mapstructure:"host"`
	Port         int           `mapstructure:"port"`
	Password     string        `mapstructure:"password"`
	DB           int           `mapstructure:"db"`
	PoolSize     int           `mapstructure:"pool_size"`
	MinIdleConns int           `mapstructure:"min_idle_conns"`
	DialTimeout  time.Duration `mapstructure:"dial_timeout"`
	ReadTimeout  time.Duration `mapstructure:"read_timeout"`
	WriteTimeout time.Duration `mapstructure:"write_timeout"`
	// CachePrefix produces `user:caches:addresses:{user_id}`, the key pinned by
	// docs/user/data.md §6.
	CachePrefix string `mapstructure:"cache_prefix"`
	StatePrefix string `mapstructure:"state_prefix"`
}

func (r RedisConfig) Addr() string { return fmt.Sprintf("%s:%d", r.Host, r.Port) }

type KafkaConfig struct {
	Brokers []string `mapstructure:"brokers"`
	// AuthTopic carries USER_VERIFIED_SUCCESS and USER_DELETED.
	AuthTopic string `mapstructure:"auth_topic"`
	// AuthDLQTopic parks what the consumer cannot process. Nothing is redriven
	// automatically.
	AuthDLQTopic string `mapstructure:"auth_dlq_topic"`
	// UserTopic carries the two events this service publishes.
	UserTopic         string        `mapstructure:"user_topic"`
	ConsumerGroup     string        `mapstructure:"consumer_group"`
	SessionTimeout    time.Duration `mapstructure:"session_timeout"`
	HeartbeatInterval time.Duration `mapstructure:"heartbeat_interval"`
	// ConsumeMaxAttempts, ConsumeMinBackoff and ConsumeMaxBackoff implement the
	// retry ladder documented for `user-auth-lifecycle-consumer`.
	ConsumeMaxAttempts int           `mapstructure:"consume_max_attempts"`
	ConsumeMinBackoff  time.Duration `mapstructure:"consume_min_backoff"`
	ConsumeMaxBackoff  time.Duration `mapstructure:"consume_max_backoff"`
	// PublishMaxAttempts bounds the direct publish of ADR-004.
	PublishMaxAttempts int           `mapstructure:"publish_max_attempts"`
	PublishBackoff     time.Duration `mapstructure:"publish_backoff"`
	PublishTimeout     time.Duration `mapstructure:"publish_timeout"`
	// Enabled allows a local run without a broker.
	Enabled bool `mapstructure:"enabled"`
	// ConsumerEnabled lets a replica publish without joining the consumer group.
	ConsumerEnabled bool `mapstructure:"consumer_enabled"`
}

type CacheConfig struct {
	// AddressTTL is the normal lifetime of a cached address book.
	AddressTTL time.Duration `mapstructure:"address_ttl"`
	// AddressEmptyTTL is the shorter lifetime of an empty list, which keeps a
	// user without addresses from becoming a cache-penetration path.
	AddressEmptyTTL time.Duration `mapstructure:"address_empty_ttl"`
}

type RateLimitRule struct {
	Limit  int           `mapstructure:"limit"`
	Window time.Duration `mapstructure:"window"`
}

func (r RateLimitRule) Enabled() bool { return r.Limit > 0 && r.Window > 0 }

type RateLimitConfig struct {
	ReadPerIdentity   RateLimitRule `mapstructure:"read_per_identity"`
	WritePerIdentity  RateLimitRule `mapstructure:"write_per_identity"`
	InternalPerClient RateLimitRule `mapstructure:"internal_per_client"`
}

type SecurityConfig struct {
	RateLimit RateLimitConfig `mapstructure:"rate_limit"`
	// TrustedProxies tells gin which hops may set X-Forwarded-For.
	TrustedProxies []string `mapstructure:"trusted_proxies"`
}

type WorkerConfig struct {
	Enabled  bool          `mapstructure:"enabled"`
	Interval time.Duration `mapstructure:"interval"`
	Timeout  time.Duration `mapstructure:"timeout"`
	Batch    int           `mapstructure:"batch"`
}

// ReconciliationConfig adds the scheduling detail the projection sweep needs on
// top of a plain worker: it runs once a day outside peak hours and paces itself
// so a full sweep cannot saturate PostgreSQL or Kafka (ADR-007).
type ReconciliationConfig struct {
	WorkerConfig `mapstructure:",squash"`
	// RunAtHourUTC is the hour of the day the sweep starts.
	RunAtHourUTC int `mapstructure:"run_at_hour_utc"`
	// BatchGap throttles the walk between two batches.
	BatchGap time.Duration `mapstructure:"batch_gap"`
}

type WorkersConfig struct {
	LockTTL                  time.Duration        `mapstructure:"lock_ttl"`
	RetentionPurge           WorkerConfig         `mapstructure:"retention_purge"`
	ProjectionReconciliation ReconciliationConfig `mapstructure:"projection_reconciliation"`
}

type LoggingConfig struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
}

// Load reads the YAML configuration, expands environment references inside it
// and applies USER_-prefixed overrides, so USER_APP_PORT overrides app.port.
func Load(path string) (*Config, error) {
	v := viper.New()
	setDefaults(v)

	v.SetEnvPrefix("USER")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	if path == "" {
		path = os.Getenv("USER_CONFIG_FILE")
	}
	if path != "" {
		raw, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read config %s: %w", path, err)
		}
		v.SetConfigType("yaml")
		if err := v.ReadConfig(bytes.NewReader([]byte(os.ExpandEnv(string(raw))))); err != nil {
			return nil, fmt.Errorf("parse config %s: %w", path, err)
		}
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("decode config: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

// Validate rejects a configuration that would break an invariant of the design.
func (c *Config) Validate() error {
	var problems []string

	if !c.App.APIEnabled && !c.Internal.Enabled && !c.Kafka.ConsumerEnabled &&
		!c.Workers.RetentionPurge.Enabled && !c.Workers.ProjectionReconciliation.Enabled {
		// A process that serves nothing and consumes nothing is a misconfigured
		// deployment, not a valid "idle" mode.
		problems = append(problems, "at least one of the APIs, the consumer or a worker must be enabled")
	}
	if c.App.APIEnabled && c.App.Port <= 0 {
		problems = append(problems, "app.port must be positive")
	}
	if c.Internal.Enabled && c.Internal.Port == c.App.Port {
		problems = append(problems, "internal.port must differ from app.port")
	}
	if c.Cache.AddressTTL <= 0 {
		problems = append(problems, "cache.address_ttl must be positive")
	}
	if c.Cache.AddressEmptyTTL <= 0 {
		problems = append(problems, "cache.address_empty_ttl must be positive")
	}
	if c.Cache.AddressEmptyTTL > c.Cache.AddressTTL {
		// An empty list is cached defensively, so it must not outlive a real one.
		problems = append(problems, "cache.address_empty_ttl must not exceed cache.address_ttl")
	}
	if c.Kafka.Enabled {
		if len(c.Kafka.Brokers) == 0 {
			problems = append(problems, "kafka.brokers is required when kafka is enabled")
		}
		if c.Kafka.UserTopic == "" {
			problems = append(problems, "kafka.user_topic is required when kafka is enabled")
		}
		if c.Kafka.ConsumerEnabled {
			if c.Kafka.AuthTopic == "" {
				problems = append(problems, "kafka.auth_topic is required when the consumer is enabled")
			}
			if c.Kafka.AuthDLQTopic == "" {
				problems = append(problems, "kafka.auth_dlq_topic is required when the consumer is enabled")
			}
			if c.Kafka.ConsumerGroup == "" {
				problems = append(problems, "kafka.consumer_group is required when the consumer is enabled")
			}
		}
	}
	if c.Kafka.ConsumerEnabled && !c.Kafka.Enabled {
		problems = append(problems, "kafka.consumer_enabled requires kafka.enabled")
	}
	hour := c.Workers.ProjectionReconciliation.RunAtHourUTC
	if hour < 0 || hour > 23 {
		problems = append(problems, "workers.projection_reconciliation.run_at_hour_utc must be between 0 and 23")
	}
	if c.App.IsProduction() && c.Internal.Enabled && !c.Internal.MutualTLSEnabled() {
		// The snapshot endpoint returns a recipient name, phone number and
		// street address to its caller.
		problems = append(problems, "the internal checkout API requires mTLS certificates in production")
	}

	if len(problems) > 0 {
		return fmt.Errorf("invalid configuration: %s", strings.Join(problems, "; "))
	}
	return nil
}

func setDefaults(v *viper.Viper) {
	v.SetDefault("app.name", "user-service")
	v.SetDefault("app.host", "0.0.0.0")
	v.SetDefault("app.port", 8082)
	v.SetDefault("app.env", "development")
	v.SetDefault("app.api_enabled", true)

	v.SetDefault("server.read_timeout", "15s")
	v.SetDefault("server.write_timeout", "15s")
	v.SetDefault("server.idle_timeout", "120s")
	v.SetDefault("server.shutdown_timeout", "20s")

	v.SetDefault("internal.enabled", true)
	v.SetDefault("internal.host", "0.0.0.0")
	v.SetDefault("internal.port", 8083)

	v.SetDefault("database.host", "localhost")
	v.SetDefault("database.port", 5432)
	v.SetDefault("database.name", "user_db")
	v.SetDefault("database.username", "postgres")
	v.SetDefault("database.sslmode", "disable")
	v.SetDefault("database.max_open_conns", 25)
	v.SetDefault("database.min_conns", 5)
	v.SetDefault("database.conn_max_lifetime", "30m")
	v.SetDefault("database.connect_timeout", "5s")
	v.SetDefault("database.statement_timeout", "5s")

	v.SetDefault("redis.host", "localhost")
	v.SetDefault("redis.port", 6379)
	v.SetDefault("redis.db", 0)
	v.SetDefault("redis.pool_size", 20)
	v.SetDefault("redis.min_idle_conns", 5)
	v.SetDefault("redis.dial_timeout", "1s")
	v.SetDefault("redis.read_timeout", "1s")
	v.SetDefault("redis.write_timeout", "1s")
	v.SetDefault("redis.cache_prefix", "user:caches")
	v.SetDefault("redis.state_prefix", "user")

	v.SetDefault("kafka.enabled", true)
	v.SetDefault("kafka.consumer_enabled", true)
	v.SetDefault("kafka.brokers", []string{"localhost:9092"})
	v.SetDefault("kafka.auth_topic", "t.auth.events")
	v.SetDefault("kafka.auth_dlq_topic", "t.auth.events.dlq")
	v.SetDefault("kafka.user_topic", "t.user.events")
	v.SetDefault("kafka.consumer_group", "user-service")
	v.SetDefault("kafka.session_timeout", "30s")
	v.SetDefault("kafka.heartbeat_interval", "3s")
	v.SetDefault("kafka.consume_max_attempts", 10)
	v.SetDefault("kafka.consume_min_backoff", "1s")
	v.SetDefault("kafka.consume_max_backoff", "5m")
	v.SetDefault("kafka.publish_max_attempts", 3)
	v.SetDefault("kafka.publish_backoff", "200ms")
	v.SetDefault("kafka.publish_timeout", "5s")

	v.SetDefault("cache.address_ttl", "12h")
	v.SetDefault("cache.address_empty_ttl", "15m")

	v.SetDefault("security.trusted_proxies", []string{})
	v.SetDefault("security.rate_limit.read_per_identity.limit", 120)
	v.SetDefault("security.rate_limit.read_per_identity.window", "1m")
	v.SetDefault("security.rate_limit.write_per_identity.limit", 30)
	v.SetDefault("security.rate_limit.write_per_identity.window", "1m")
	v.SetDefault("security.rate_limit.internal_per_client.limit", 600)
	v.SetDefault("security.rate_limit.internal_per_client.window", "1m")

	v.SetDefault("workers.lock_ttl", "5m")
	v.SetDefault("workers.retention_purge.enabled", true)
	v.SetDefault("workers.retention_purge.interval", "1h")
	v.SetDefault("workers.retention_purge.timeout", "10m")
	v.SetDefault("workers.retention_purge.batch", 100)
	v.SetDefault("workers.projection_reconciliation.enabled", true)
	v.SetDefault("workers.projection_reconciliation.interval", "24h")
	v.SetDefault("workers.projection_reconciliation.timeout", "2h")
	v.SetDefault("workers.projection_reconciliation.batch", 500)
	v.SetDefault("workers.projection_reconciliation.run_at_hour_utc", 18)
	v.SetDefault("workers.projection_reconciliation.batch_gap", "200ms")

	v.SetDefault("logging.level", "info")
	v.SetDefault("logging.format", "json")
}
