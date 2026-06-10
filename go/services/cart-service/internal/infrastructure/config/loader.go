package config

import (
	"fmt"
	"strings"

	"github.com/spf13/viper"
)

// AppConfig holds the complete application configuration.
// It maps directly to application.yaml.
type AppConfig struct {
	App      AppSection      `mapstructure:"app"`
	Redis    RedisSection    `mapstructure:"redis"`
	Database DatabaseSection `mapstructure:"database"`
	Kafka    KafkaSection    `mapstructure:"kafka"`
	GRPC     GRPCSection     `mapstructure:"grpc"`
	Cart     CartSection     `mapstructure:"cart"`
	Worker   WorkerSection   `mapstructure:"worker"`
	Logging  LoggingSection  `mapstructure:"logging"`
	Tracing  TracingSection  `mapstructure:"tracing"`
}

type AppSection struct {
	Name            string `mapstructure:"name"`
	Env             string `mapstructure:"env"`
	Host            string `mapstructure:"host"`
	Port            int    `mapstructure:"port"`
	ShutdownTimeout string `mapstructure:"shutdown_timeout"`
}

type RedisSection struct {
	Addrs        []string `mapstructure:"addrs"`
	Password     string   `mapstructure:"password"`
	DB           int      `mapstructure:"db"`
	PoolSize     int      `mapstructure:"pool_size"`
	MinIdleConns int      `mapstructure:"min_idle_conns"`
	ReadTimeout  string   `mapstructure:"read_timeout"`
	WriteTimeout string   `mapstructure:"write_timeout"`
	NumShards    int      `mapstructure:"num_shards"`
}

type DatabaseSection struct {
	Host            string `mapstructure:"host"`
	Port            int    `mapstructure:"port"`
	User            string `mapstructure:"user"`
	Password       string `mapstructure:"password"`
	Name            string `mapstructure:"name"`
	MaxOpenConns   int    `mapstructure:"max_open_conns"`
	MaxIdleConns   int    `mapstructure:"max_idle_conns"`
	ConnMaxLifetime string `mapstructure:"conn_max_lifetime"`
}

type KafkaSection struct {
	Brokers                    []string `mapstructure:"brokers"`
	TopicCartEvents            string   `mapstructure:"topic_cart_events"`
	TopicOrderEvents           string   `mapstructure:"topic_order_events"`
	ConsumerGroupCartSync      string   `mapstructure:"consumer_group_cart_sync"`
	ConsumerGroupOrder         string   `mapstructure:"consumer_group_order"`
	MinInsyncReplicas          int      `mapstructure:"min_insync_replicas"`
	RequiredAcks               string   `mapstructure:"required_acks"`
	MaxPollRecords             int      `mapstructure:"max_poll_records"`
}

type GRPCSection struct {
	ProductServiceAddr string            `mapstructure:"product_service_addr"`
	DialTimeout        string            `mapstructure:"dial_timeout"`
	MaxCallTimeout     string            `mapstructure:"max_call_timeout"`
	CircuitBreaker    CircuitBreakerCfg `mapstructure:"circuit_breaker"`
}

type CircuitBreakerCfg struct {
	Name        string `mapstructure:"name"`
	MaxRequests int    `mapstructure:"max_requests"`
	Interval    string `mapstructure:"interval"`
	Timeout     string `mapstructure:"timeout"`
}

type CartSection struct {
	TTL               int `mapstructure:"ttl"`
	MaxSKUsPerCart   int `mapstructure:"max_skus_per_cart"`
	MaxQtyPerItem    int `mapstructure:"max_qty_per_item"`
	NegativeCacheTTL int `mapstructure:"negative_cache_ttl"`
	RehydrateTTL     int `mapstructure:"rehydrate_ttl"`
}

type WorkerSection struct {
	OutboxRelay     OutboxRelayCfg     `mapstructure:"outbox_relay"`
	PostgresSync    PostgresSyncCfg    `mapstructure:"postgres_sync"`
	AbandonedMarker AbandonedMarkerCfg `mapstructure:"abandoned_marker"`
	ArchivalCleaner ArchivalCleanerCfg `mapstructure:"archival_cleaner"`
	StreamTrimmer   StreamTrimmerCfg   `mapstructure:"stream_trimmer"`
}

type OutboxRelayCfg struct {
	Count        int    `mapstructure:"count"`
	BlockTimeout string `mapstructure:"block_timeout"`
	BatchSize    int    `mapstructure:"batch_size"`
}

type PostgresSyncCfg struct {
	Enabled    bool `mapstructure:"enabled"`
	BatchSize  int  `mapstructure:"batch_size"`
	MaxRetries int  `mapstructure:"max_retries"`
}

type AbandonedMarkerCfg struct {
	Enabled              bool `mapstructure:"enabled"`
	Cron                 string `mapstructure:"cron"`
	BatchSize            int    `mapstructure:"batch_size"`
	InactiveThresholdDays int    `mapstructure:"inactive_threshold_days"`
}

type ArchivalCleanerCfg struct {
	Enabled           bool   `mapstructure:"enabled"`
	Cron              string `mapstructure:"cron"`
	DeletedTTLDays    int    `mapstructure:"deleted_ttl_days"`
	CheckedOutTTLDays int    `mapstructure:"checked_out_ttl_days"`
	AbandonedTTLDays  int    `mapstructure:"abandoned_ttl_days"`
}

type StreamTrimmerCfg struct {
	Enabled           bool   `mapstructure:"enabled"`
	Interval          string `mapstructure:"interval"`
	PendingThreshold  string `mapstructure:"pending_threshold"`
}

type LoggingSection struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
	Output string `mapstructure:"output"`
}

type TracingSection struct {
	Enabled     bool   `mapstructure:"enabled"`
	Endpoint    string `mapstructure:"endpoint"`
	ServiceName string `mapstructure:"service_name"`
}

// Load reads the configuration from the specified config file and environment variables.
// File path defaults to "configs/application.yaml" relative to the working directory.
func Load(configPath string) (*AppConfig, error) {
	v := viper.New()

	// Set config file
	v.SetConfigFile(configPath)

	// Environment variable support
	v.SetEnvPrefix("CART")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	// Read config file
	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("failed to read config file %s: %w", configPath, err)
	}

	var cfg AppConfig
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	// Apply defaults
	applyDefaults(&cfg)

	// Validate
	if err := validate(&cfg); err != nil {
		return nil, fmt.Errorf("config validation failed: %w", err)
	}

	return &cfg, nil
}

// MustLoad loads the configuration or panics.
func MustLoad(configPath string) *AppConfig {
	cfg, err := Load(configPath)
	if err != nil {
		panic("failed to load config: " + err.Error())
	}
	return cfg
}

func applyDefaults(cfg *AppConfig) {
	if cfg.Redis.PoolSize == 0 {
		cfg.Redis.PoolSize = 100
	}
	if cfg.Redis.MinIdleConns == 0 {
		cfg.Redis.MinIdleConns = 20
	}
	if cfg.Redis.NumShards == 0 {
		cfg.Redis.NumShards = 4
	}
	if cfg.Redis.ReadTimeout == "" {
		cfg.Redis.ReadTimeout = "3s"
	}
	if cfg.Redis.WriteTimeout == "" {
		cfg.Redis.WriteTimeout = "3s"
	}
	if cfg.Cart.TTL == 0 {
		cfg.Cart.TTL = 604800
	}
	if cfg.Cart.MaxSKUsPerCart == 0 {
		cfg.Cart.MaxSKUsPerCart = 50
	}
	if cfg.Cart.MaxQtyPerItem == 0 {
		cfg.Cart.MaxQtyPerItem = 100
	}
	if cfg.Cart.NegativeCacheTTL == 0 {
		cfg.Cart.NegativeCacheTTL = 60
	}
	if cfg.Cart.RehydrateTTL == 0 {
		cfg.Cart.RehydrateTTL = 604800
	}
}

func validate(cfg *AppConfig) error {
	if len(cfg.Redis.Addrs) == 0 {
		return fmt.Errorf("redis.addrs is required")
	}
	if cfg.Redis.NumShards <= 0 {
		return fmt.Errorf("redis.num_shards must be > 0")
	}
	if cfg.Cart.MaxSKUsPerCart <= 0 {
		return fmt.Errorf("cart.max_skus_per_cart must be > 0")
	}
	if cfg.Cart.MaxQtyPerItem <= 0 {
		return fmt.Errorf("cart.max_qty_per_item must be > 0")
	}
	return nil
}
