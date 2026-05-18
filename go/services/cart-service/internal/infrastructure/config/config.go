package config

import (
	"strings"
	"time"

	"github.com/spf13/viper"
)

// Config represents the application configuration
type Config struct {
	App            AppConfig
	Server         ServerConfig
	Redis          RedisConfig
	Postgres       PostgresConfig
	Kafka          KafkaConfig
	Workers        WorkersConfig
	Cart           CartConfig
	ProductService ProductServiceConfig
}

// AppConfig holds application-level settings
type AppConfig struct {
	Env        string
	ServiceName string
}

// ServerConfig holds HTTP server settings
type ServerConfig struct {
	Host         string
	Port         int
	ReadTimeout  string
	WriteTimeout string
	IdleTimeout  string
}

// RedisConfig holds Redis connection settings
type RedisConfig struct {
	Addresses    []string
	Password     string
	DB           int
	PoolSize     int
	DialTimeout  string
	ReadTimeout  string
	WriteTimeout string
}

// PostgresConfig holds PostgreSQL connection settings
type PostgresConfig struct {
	DSN            string
	MaxOpenConns   int
	MaxIdleConns   int
	ConnMaxLifetime string
}

// KafkaConfig holds Kafka settings
type KafkaConfig struct {
	Brokers           []string
	Topic             string
	ConsumerGroup     string
	Consumer          KafkaConsumerConfig
	Producer          KafkaProducerConfig
	OrderTopic        string
	OrderConsumerGroup string
}

// KafkaProducerConfig holds Kafka producer settings
type KafkaProducerConfig struct {
	Acks    int
	Retries int
}

// KafkaConsumerConfig holds Kafka consumer settings
type KafkaConsumerConfig struct {
	GroupID           string
	AutoOffset        string
	AutoCommit        bool
	SessionTimeout    string
	HeartbeatInterval string
	MaxPollRecords    int
}

// WorkersConfig holds worker-specific settings
type WorkersConfig struct {
	OutboxRelay      OutboxRelayConfig
	AbandonedMarker  AbandonedMarkerConfig
	StreamTrimmer    StreamTrimmerConfig
	ArchivalCleaner  ArchivalCleanerConfig
	DLQHandler       DLQHandlerConfig
}

// OutboxRelayConfig holds outbox relay worker settings
type OutboxRelayConfig struct {
	Enabled         bool
	Topic           string
	PollTimeout     string
	BatchSize       int
	MaxRetries      int
	RetryBackoff    string
	RedisStream     string
	KafkaBrokers    []string
	KafkaTopic      string
	PendingTimeoutMs int
	ConsumerCount   int
}

// AbandonedMarkerConfig holds abandoned cart marker settings
type AbandonedMarkerConfig struct {
	Enabled               bool
	Schedule              string
	Interval              string
	InactivityPeriod      string
	BatchSize             int
	AbandonedThresholdDays int
}

// StreamTrimmerConfig holds stream trimmer settings
type StreamTrimmerConfig struct {
	Enabled   bool
	Interval  string
	BatchSize int
	MaxLen    int64
}

// ArchivalCleanerConfig holds archival cleaner settings
type ArchivalCleanerConfig struct {
	Enabled              bool
	Schedule             string
	Interval             string
	BatchSize            int
	KeepDays             int
	DeletedRetentionDays   int
	AbandonedRetentionDays int
}

// DLQHandlerConfig holds dead letter queue handler settings
type DLQHandlerConfig struct {
	Enabled              bool
	Interval            string
	MaxRetries          int
	RetryBackoff        string
	MaxDLQSize          int
	AutoQuarantineHours int
}

// CartConfig holds cart-specific settings
type CartConfig struct {
	MaxItems              int
	MaxQuantity           int
	MaxQuantityPerItem    int
	MaxUniqueSKUs         int
	TTL                   string
	NegativeCacheTTL      int
	AbandonedThresholdDays  int
	DeletedRetentionDays    int
	AbandonedRetentionDays  int
}

// ProductServiceConfig holds product service settings
type ProductServiceConfig struct {
	Address         string
	Timeout         string
	CircuitBreaker  CircuitBreakerConfig
}

// CircuitBreakerConfig holds circuit breaker settings
type CircuitBreakerConfig struct {
	MaxFailures         int
	FailureTimeout      string
	HalfOpenMaxRequests int
}

// ParseDuration parses a duration string to time.Duration
func ParseDuration(s string) time.Duration {
	d, err := time.ParseDuration(s)
	if err != nil {
		return 30 * time.Second // default
	}
	return d
}

// Load loads configuration from file and environment
func Load(path string) (*Config, error) {
	viper.SetConfigFile(path)
	viper.AutomaticEnv()
	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	if err := viper.ReadInConfig(); err != nil {
		return nil, err
	}

	var cfg Config
	if err := viper.Unmarshal(&cfg); err != nil {
		return nil, err
	}

	return &cfg, nil
}

// LoadDefault loads configuration from default locations
func LoadDefault() (*Config, error) {
	viper.SetConfigName("application")
	viper.SetConfigType("yaml")
	viper.AddConfigPath("./config")
	viper.AddConfigPath("../config")
	viper.AddConfigPath("../../config")
	viper.AutomaticEnv()
	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	if err := viper.ReadInConfig(); err != nil {
		return nil, err
	}

	var cfg Config
	if err := viper.Unmarshal(&cfg); err != nil {
		return nil, err
	}

	return &cfg, nil
}
