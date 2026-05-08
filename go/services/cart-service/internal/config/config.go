package config

import (
	"fmt"
	"strings"
	"time"

	"github.com/spf13/viper"
)

// Config holds all configuration for cart service
type Config struct {
	App           AppConfig           `mapstructure:"app"`
	Server        ServerConfig        `mapstructure:"server"`
	Redis         RedisConfig         `mapstructure:"redis"`
	RedisCluster  RedisClusterConfig  `mapstructure:"redis_cluster"`
	Kafka         KafkaConfig         `mapstructure:"kafka"`
	Postgres      PostgresConfig      `mapstructure:"postgres"`
	ProductService ProductServiceConfig `mapstructure:"product_service"`
	Cart          CartConfig          `mapstructure:"cart"`
	Workers       WorkersConfig       `mapstructure:"workers"`
	Observability ObservabilityConfig `mapstructure:"observability"`
}

// AppConfig holds application configuration
type AppConfig struct {
	Name string `mapstructure:"name"`
	Env  string `mapstructure:"env"`
}

// ServerConfig holds HTTP server configuration
type ServerConfig struct {
	Host string `mapstructure:"host"`
	Port int    `mapstructure:"port"`
}

// RedisConfig holds Redis configuration
type RedisConfig struct {
	Addresses    []string `mapstructure:"addresses"`
	Password     string   `mapstructure:"password"`
	DB           int      `mapstructure:"db"`
	PoolSize     int      `mapstructure:"pool_size"`
	DialTimeout  string   `mapstructure:"dial_timeout"`
	ReadTimeout  string   `mapstructure:"read_timeout"`
	WriteTimeout string   `mapstructure:"write_timeout"`
}

// RedisClusterConfig holds Redis Cluster configuration
type RedisClusterConfig struct {
	Enabled     bool     `mapstructure:"enabled"`
	StartupNodes []string `mapstructure:"startup_nodes"`
}

// KafkaConfig holds Kafka configuration
type KafkaConfig struct {
	Brokers           []string            `mapstructure:"brokers"`
	Topic            string              `mapstructure:"topic"`
	ConsumerGroup    string              `mapstructure:"consumer_group"`
	OrderTopic       string              `mapstructure:"order_topic"`
	OrderConsumerGroup string            `mapstructure:"order_consumer_group"`
	Producer         KafkaProducerConfig  `mapstructure:"producer"`
	Consumer         KafkaConsumerConfig  `mapstructure:"consumer"`
}

// KafkaProducerConfig holds Kafka producer configuration
type KafkaProducerConfig struct {
	Acks          string `mapstructure:"acks"`
	Retries       int    `mapstructure:"retries"`
	RetryBackoff  string `mapstructure:"retry_backoff"`
}

// KafkaConsumerConfig holds Kafka consumer configuration
type KafkaConsumerConfig struct {
	SessionTimeout     string `mapstructure:"session_timeout"`
	HeartbeatInterval  string `mapstructure:"heartbeat_interval"`
	MaxPollRecords    int    `mapstructure:"max_poll_records"`
}

// PostgresConfig holds PostgreSQL configuration
type PostgresConfig struct {
	DSN            string `mapstructure:"dsn"`
	MaxOpenConns   int    `mapstructure:"max_open_conns"`
	MaxIdleConns   int    `mapstructure:"max_idle_conns"`
	ConnMaxLifetime string `mapstructure:"conn_max_lifetime"`
}

// ProductServiceConfig holds Product Service gRPC configuration
type ProductServiceConfig struct {
	Address        string              `mapstructure:"address"`
	Timeout        string              `mapstructure:"timeout"`
	CircuitBreaker CircuitBreakerConfig `mapstructure:"circuit_breaker"`
}

// CircuitBreakerConfig holds circuit breaker configuration
type CircuitBreakerConfig struct {
	MaxFailures        int `mapstructure:"max_failures"`
	FailureTimeout     string `mapstructure:"failure_timeout"`
	HalfOpenMaxRequests int `mapstructure:"half_open_max_requests"`
}

// CartConfig holds cart-specific configuration
type CartConfig struct {
	MaxQuantityPerItem  int `mapstructure:"max_quantity_per_item"`
	MaxUniqueSKUs       int `mapstructure:"max_unique_skus"`
	TTL                 int `mapstructure:"ttl"`
	NegativeCacheTTL    int `mapstructure:"negative_cache_ttl"`
	AbandonedThresholdDays int `mapstructure:"abandoned_threshold_days"`
	DeletedRetentionDays int `mapstructure:"deleted_retention_days"`
	AbandonedRetentionDays int `mapstructure:"abandoned_retention_days"`
}

// WorkersConfig holds worker configuration
type WorkersConfig struct {
	OutboxRelay    OutboxRelayConfig    `mapstructure:"outbox_relay"`
	StreamTrimmer  StreamTrimmerConfig `mapstructure:"stream_trimmer"`
	AbandonedMarker AbandonedMarkerConfig `mapstructure:"abandoned_marker"`
	ArchivalCleaner ArchivalCleanerConfig `mapstructure:"archival_cleaner"`
	DLQHandler     DLQHandlerConfig    `mapstructure:"dlq_handler"`
}

// OutboxRelayConfig holds outbox relay worker configuration
type OutboxRelayConfig struct {
	ConsumerCount   int    `mapstructure:"consumer_count"`
	BatchSize       int    `mapstructure:"batch_size"`
	PollTimeout     string `mapstructure:"poll_timeout"`
	PendingTimeoutMs int   `mapstructure:"pending_timeout_ms"`
	MaxRetries      int    `mapstructure:"max_retries"`
}

// StreamTrimmerConfig holds stream trimmer configuration
type StreamTrimmerConfig struct {
	Interval  string `mapstructure:"interval"`
	BatchSize  int    `mapstructure:"batch_size"`
}

// AbandonedMarkerConfig holds abandoned cart marker configuration
type AbandonedMarkerConfig struct {
	Schedule  string `mapstructure:"schedule"`
	BatchSize int    `mapstructure:"batch_size"`
}

// ArchivalCleanerConfig holds archival cleaner configuration
type ArchivalCleanerConfig struct {
	Schedule  string `mapstructure:"schedule"`
	BatchSize int    `mapstructure:"batch_size"`
}

// DLQHandlerConfig holds DLQ handler configuration
type DLQHandlerConfig struct {
	Interval           string `mapstructure:"interval"`
	AutoQuarantineHours int   `mapstructure:"auto_quarantine_hours"`
	MaxDLQSize         int    `mapstructure:"max_dlq_size"`
}

// ObservabilityConfig holds observability configuration
type ObservabilityConfig struct {
	Prometheus PrometheusConfig `mapstructure:"prometheus"`
	Tracing    TracingConfig    `mapstructure:"tracing"`
	Logging    LoggingConfig    `mapstructure:"logging"`
}

// PrometheusConfig holds Prometheus configuration
type PrometheusConfig struct {
	Enabled bool   `mapstructure:"enabled"`
	Port    int    `mapstructure:"port"`
	Path    string `mapstructure:"path"`
}

// TracingConfig holds tracing configuration
type TracingConfig struct {
	Enabled      bool   `mapstructure:"enabled"`
	JaegerEndpoint string `mapstructure:"jaeger_endpoint"`
	SamplingRate float64 `mapstructure:"sampling_rate"`
}

// LoggingConfig holds logging configuration
type LoggingConfig struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
}

// Load loads configuration from file and environment variables
func Load(configPath string) (*Config, error) {
	viper.SetConfigFile(configPath)
	viper.SetConfigType("yaml")

	// Read config file
	if err := viper.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	// Enable environment variable override
	viper.AutomaticEnv()
	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	viper.SetEnvPrefix("CART")

	var config Config
	if err := viper.Unmarshal(&config); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	// Set defaults
	setDefaults(&config)

	return &config, nil
}

// LoadDefault loads configuration with default values
func LoadDefault() (*Config, error) {
	var config Config
	setDefaults(&config)

	// Try to read from environment/config file
	viper.SetConfigName("application")
	viper.SetConfigType("yaml")
	viper.AddConfigPath(".")
	viper.AddConfigPath("./config")
	viper.AddConfigPath("../config")

	if err := viper.ReadInConfig(); err != nil {
		// If no config file, just use defaults
		return &config, nil
	}

	if err := viper.Unmarshal(&config); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	// Re-apply defaults for any missing values
	setDefaults(&config)

	return &config, nil
}

func setDefaults(config *Config) {
	// App defaults
	if config.App.Name == "" {
		config.App.Name = "cart-service"
	}
	if config.App.Env == "" {
		config.App.Env = "development"
	}

	// Server defaults
	if config.Server.Port == 0 {
		config.Server.Port = 8080
	}
	if config.Server.Host == "" {
		config.Server.Host = "0.0.0.0"
	}

	// Redis defaults
	if len(config.Redis.Addresses) == 0 {
		config.Redis.Addresses = []string{"localhost:6379"}
	}
	if config.Redis.PoolSize == 0 {
		config.Redis.PoolSize = 100
	}
	if config.Redis.DialTimeout == "" {
		config.Redis.DialTimeout = "5s"
	}
	if config.Redis.ReadTimeout == "" {
		config.Redis.ReadTimeout = "3s"
	}
	if config.Redis.WriteTimeout == "" {
		config.Redis.WriteTimeout = "3s"
	}

	// Redis Cluster defaults
	config.RedisCluster.Enabled = false

	// Kafka defaults
	if len(config.Kafka.Brokers) == 0 {
		config.Kafka.Brokers = []string{"localhost:9092"}
	}
	if config.Kafka.Topic == "" {
		config.Kafka.Topic = "t.cart.events"
	}
	if config.Kafka.ConsumerGroup == "" {
		config.Kafka.ConsumerGroup = "cart-db-sync"
	}
	if config.Kafka.OrderTopic == "" {
		config.Kafka.OrderTopic = "t.order.events"
	}
	if config.Kafka.OrderConsumerGroup == "" {
		config.Kafka.OrderConsumerGroup = "cart-order-consumer"
	}

	// Kafka Producer defaults
	if config.Kafka.Producer.Acks == "" {
		config.Kafka.Producer.Acks = "all"
	}
	if config.Kafka.Producer.Retries == 0 {
		config.Kafka.Producer.Retries = 3
	}
	if config.Kafka.Producer.RetryBackoff == "" {
		config.Kafka.Producer.RetryBackoff = "100ms"
	}

	// Kafka Consumer defaults
	if config.Kafka.Consumer.SessionTimeout == "" {
		config.Kafka.Consumer.SessionTimeout = "30s"
	}
	if config.Kafka.Consumer.HeartbeatInterval == "" {
		config.Kafka.Consumer.HeartbeatInterval = "3s"
	}
	if config.Kafka.Consumer.MaxPollRecords == 0 {
		config.Kafka.Consumer.MaxPollRecords = 50
	}

	// Postgres defaults
	if config.Postgres.MaxOpenConns == 0 {
		config.Postgres.MaxOpenConns = 25
	}
	if config.Postgres.MaxIdleConns == 0 {
		config.Postgres.MaxIdleConns = 5
	}
	if config.Postgres.ConnMaxLifetime == "" {
		config.Postgres.ConnMaxLifetime = "5m"
	}

	// Product Service defaults
	if config.ProductService.Address == "" {
		config.ProductService.Address = "product-service:50051"
	}
	if config.ProductService.Timeout == "" {
		config.ProductService.Timeout = "200ms"
	}

	// Circuit Breaker defaults
	if config.ProductService.CircuitBreaker.MaxFailures == 0 {
		config.ProductService.CircuitBreaker.MaxFailures = 5
	}
	if config.ProductService.CircuitBreaker.FailureTimeout == "" {
		config.ProductService.CircuitBreaker.FailureTimeout = "30s"
	}
	if config.ProductService.CircuitBreaker.HalfOpenMaxRequests == 0 {
		config.ProductService.CircuitBreaker.HalfOpenMaxRequests = 3
	}

	// Cart defaults
	if config.Cart.MaxQuantityPerItem == 0 {
		config.Cart.MaxQuantityPerItem = 100
	}
	if config.Cart.MaxUniqueSKUs == 0 {
		config.Cart.MaxUniqueSKUs = 50
	}
	if config.Cart.TTL == 0 {
		config.Cart.TTL = 604800 // 7 days
	}
	if config.Cart.NegativeCacheTTL == 0 {
		config.Cart.NegativeCacheTTL = 60
	}
	if config.Cart.AbandonedThresholdDays == 0 {
		config.Cart.AbandonedThresholdDays = 30
	}
	if config.Cart.DeletedRetentionDays == 0 {
		config.Cart.DeletedRetentionDays = 90
	}
	if config.Cart.AbandonedRetentionDays == 0 {
		config.Cart.AbandonedRetentionDays = 180
	}

	// Workers defaults
	// Outbox Relay
	if config.Workers.OutboxRelay.ConsumerCount == 0 {
		config.Workers.OutboxRelay.ConsumerCount = 10
	}
	if config.Workers.OutboxRelay.BatchSize == 0 {
		config.Workers.OutboxRelay.BatchSize = 50
	}
	if config.Workers.OutboxRelay.PollTimeout == "" {
		config.Workers.OutboxRelay.PollTimeout = "1s"
	}
	if config.Workers.OutboxRelay.PendingTimeoutMs == 0 {
		config.Workers.OutboxRelay.PendingTimeoutMs = 60000
	}
	if config.Workers.OutboxRelay.MaxRetries == 0 {
		config.Workers.OutboxRelay.MaxRetries = 5
	}

	// Stream Trimmer
	if config.Workers.StreamTrimmer.Interval == "" {
		config.Workers.StreamTrimmer.Interval = "5m"
	}
	if config.Workers.StreamTrimmer.BatchSize == 0 {
		config.Workers.StreamTrimmer.BatchSize = 100
	}

	// Abandoned Marker
	if config.Workers.AbandonedMarker.Schedule == "" {
		config.Workers.AbandonedMarker.Schedule = "0 2 * * *" // 2:00 AM daily
	}
	if config.Workers.AbandonedMarker.BatchSize == 0 {
		config.Workers.AbandonedMarker.BatchSize = 1000
	}

	// Archival Cleaner
	if config.Workers.ArchivalCleaner.Schedule == "" {
		config.Workers.ArchivalCleaner.Schedule = "0 3 * * 0" // 3:00 AM Sunday
	}
	if config.Workers.ArchivalCleaner.BatchSize == 0 {
		config.Workers.ArchivalCleaner.BatchSize = 500
	}

	// DLQ Handler
	if config.Workers.DLQHandler.Interval == "" {
		config.Workers.DLQHandler.Interval = "5m"
	}
	if config.Workers.DLQHandler.AutoQuarantineHours == 0 {
		config.Workers.DLQHandler.AutoQuarantineHours = 24
	}
	if config.Workers.DLQHandler.MaxDLQSize == 0 {
		config.Workers.DLQHandler.MaxDLQSize = 10000
	}

	// Observability defaults
	// Prometheus
	if config.Observability.Prometheus.Port == 0 {
		config.Observability.Prometheus.Port = 9090
	}
	if config.Observability.Prometheus.Path == "" {
		config.Observability.Prometheus.Path = "/metrics"
	}

	// Tracing
	if config.Observability.Tracing.SamplingRate == 0 {
		config.Observability.Tracing.SamplingRate = 0.1
	}

	// Logging
	if config.Observability.Logging.Level == "" {
		config.Observability.Logging.Level = "info"
	}
	if config.Observability.Logging.Format == "" {
		config.Observability.Logging.Format = "json"
	}
}

// ParseDuration parses a duration string
func ParseDuration(s string) time.Duration {
	d, err := time.ParseDuration(s)
	if err != nil {
		return 0
	}
	return d
}
