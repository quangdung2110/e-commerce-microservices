// Package config loads and validates the service configuration.
package config

import (
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/spf13/viper"

	"cart-service/internal/domain"
)

// Config is the complete application configuration.
type Config struct {
	App     App     `mapstructure:"app"`
	Redis   Redis   `mapstructure:"redis"`
	Kafka   Kafka   `mapstructure:"kafka"`
	Product Product `mapstructure:"product"`
	Media   Media   `mapstructure:"media"`
	Cart    Cart    `mapstructure:"cart"`
	Worker  Worker  `mapstructure:"worker"`
	Logging Logging `mapstructure:"logging"`
}

// App holds process-level settings.
type App struct {
	Name             string        `mapstructure:"name"`
	Env              string        `mapstructure:"env"`
	Host             string        `mapstructure:"host"`
	Port             int           `mapstructure:"port"`
	ShutdownTimeout  time.Duration `mapstructure:"shutdown_timeout"`
	ReadinessTimeout time.Duration `mapstructure:"readiness_timeout"`
}

// Redis holds the connection settings for the cart source of truth.
//
// Production Redis must run with AOF, a replica, TLS and
// maxmemory-policy noeviction. Cart has no recovery path from data loss.
type Redis struct {
	Addrs        []string      `mapstructure:"addrs"`
	Password     string        `mapstructure:"password"`
	DB           int           `mapstructure:"db"`
	PoolSize     int           `mapstructure:"pool_size"`
	MinIdleConns int           `mapstructure:"min_idle_conns"`
	DialTimeout  time.Duration `mapstructure:"dial_timeout"`
	ReadTimeout  time.Duration `mapstructure:"read_timeout"`
	WriteTimeout time.Duration `mapstructure:"write_timeout"`
	TLSEnabled   bool          `mapstructure:"tls_enabled"`
}

// Kafka holds the event transport settings.
type Kafka struct {
	Brokers []string `mapstructure:"brokers"`
	// TopicCartEvents carries CART_ABANDONED, the only event Cart publishes.
	TopicCartEvents string `mapstructure:"topic_cart_events"`
	// TopicCartEventsDLQ receives outbox entries that can never be published.
	TopicCartEventsDLQ string `mapstructure:"topic_cart_events_dlq"`
	// TopicOrderEvents carries the PAID and COMPLETED facts; Cart consumes PAID.
	TopicOrderEvents   string `mapstructure:"topic_order_events"`
	ConsumerGroupOrder string `mapstructure:"consumer_group_order"`
	// TopicAuthEvents carries the identity facts; Cart consumes USER_DELETED to
	// purge the cart of a deleted account.
	TopicAuthEvents   string        `mapstructure:"topic_auth_events"`
	ConsumerGroupAuth string        `mapstructure:"consumer_group_auth"`
	InitialOffset     string        `mapstructure:"initial_offset"`
	RequiredAcks      string        `mapstructure:"required_acks"`
	ProduceTimeout    time.Duration `mapstructure:"produce_timeout"`
	SessionTimeout    time.Duration `mapstructure:"session_timeout"`
	TLSEnabled        bool          `mapstructure:"tls_enabled"`
	// SASL/SCRAM-SHA-512 principal of the service (mesh ADR-003). An empty
	// username keeps PLAINTEXT, for tests and a standalone broker.
	SASLUsername string `mapstructure:"sasl_username"`
	SASLPassword string `mapstructure:"sasl_password"`
	// EventDataSchema is the dataschema URI stamped on every CloudEvent.
	EventDataSchema string `mapstructure:"event_data_schema"`
}

// Product holds the Product Service gRPC settings.
type Product struct {
	Address     string        `mapstructure:"address"`
	DialTimeout time.Duration `mapstructure:"dial_timeout"`
	// GetSkuTimeout and GetBatchSkusTimeout are hard deadlines. Both are
	// mandatory: the cart page must never wait on Product.
	GetSkuTimeout       time.Duration `mapstructure:"get_sku_timeout"`
	GetBatchSkusTimeout time.Duration `mapstructure:"get_batch_skus_timeout"`
	CacheSize           int           `mapstructure:"cache_size"`
	CacheTTL            time.Duration `mapstructure:"cache_ttl"`
	Breaker             Breaker       `mapstructure:"circuit_breaker"`
}

// Media holds the public media settings used to turn Product image storage
// keys into URLs. Both URLs must equal the Product Detail BFF
// (PDP_MEDIA_CDN_BASE_URL, PDP_MEDIA_PLACEHOLDER_URL) and Search
// (CDN_BASE_URL, THUMBNAIL_PLACEHOLDER_URL), so a cart line shows the same file
// as the product card and the detail page.
type Media struct {
	// CDNBaseURL is absolute and has no trailing slash.
	CDNBaseURL string `mapstructure:"cdn_base_url"`
	// PlaceholderURL is returned for a SKU without an image.
	PlaceholderURL string `mapstructure:"placeholder_url"`
	// RequireHTTPS is only turned off locally, where MinIO is served through
	// the gateway over plain http.
	RequireHTTPS bool `mapstructure:"require_https"`
}

// Breaker tunes the Product circuit breaker.
type Breaker struct {
	FailureThreshold int           `mapstructure:"failure_threshold"`
	HalfOpenMaxCalls int           `mapstructure:"half_open_max_calls"`
	OpenTimeout      time.Duration `mapstructure:"open_timeout"`
}

// Cart holds the lifecycle windows the Lua scripts enforce.
type Cart struct {
	// BucketCount is read from config only so a mismatch with the compiled
	// data contract fails loudly at startup instead of silently misrouting.
	BucketCount int `mapstructure:"bucket_count"`
	// AbandonedAfter is the inactivity window before abandonment.
	AbandonedAfter time.Duration `mapstructure:"abandoned_after"`
	// ActiveTTL must exceed AbandonedAfter so the marker has a buffer.
	ActiveTTL time.Duration `mapstructure:"active_ttl"`
	// AbandonedTTL is the retention window after the transition.
	AbandonedTTL time.Duration `mapstructure:"abandoned_ttl"`
	// ProductValidationTTL bounds snapshot reuse on a positive delta.
	ProductValidationTTL time.Duration `mapstructure:"product_validation_ttl"`
	// IdempotencyTTL is how long an HTTP write stays replayable.
	IdempotencyTTL time.Duration `mapstructure:"idempotency_ttl"`
	// InternalCallers are the service identities allowed on /internal/v1 (checkout-service).
	InternalCallers []string  `mapstructure:"internal_callers"`
	RateLimit       RateLimit `mapstructure:"rate_limit"`
}

// RateLimit tunes the local per-user limiter.
type RateLimit struct {
	RequestsPerSecond float64       `mapstructure:"requests_per_second"`
	Burst             int           `mapstructure:"burst"`
	IdleTimeout       time.Duration `mapstructure:"idle_timeout"`
}

// Worker enables and tunes the three background loops and the consumer.
type Worker struct {
	AbandonedMarker MarkerWorker  `mapstructure:"abandoned_marker"`
	OutboxRelay     RelayWorker   `mapstructure:"outbox_relay"`
	StreamTrimmer   TrimmerWorker `mapstructure:"stream_trimmer"`
	OrderConsumer   ToggleWorker  `mapstructure:"order_consumer"`
	// UserDeletedConsumer purges the cart of a deleted account (USER_DELETED).
	UserDeletedConsumer ToggleWorker `mapstructure:"user_deleted_consumer"`
}

// ToggleWorker is a worker with no tuning beyond an on/off switch.
type ToggleWorker struct {
	Enabled bool `mapstructure:"enabled"`
}

// MarkerWorker tunes the abandoned marker loop.
type MarkerWorker struct {
	Enabled          bool          `mapstructure:"enabled"`
	Interval         time.Duration `mapstructure:"interval"`
	BatchSize        int           `mapstructure:"batch_size"`
	OperationTimeout time.Duration `mapstructure:"operation_timeout"`
}

// RelayWorker tunes the outbox relay loop.
type RelayWorker struct {
	Enabled            bool          `mapstructure:"enabled"`
	IdleInterval       time.Duration `mapstructure:"idle_interval"`
	BatchSize          int           `mapstructure:"batch_size"`
	ClaimMinIdle       time.Duration `mapstructure:"claim_min_idle"`
	MaxPublishAttempts int           `mapstructure:"max_publish_attempts"`
	PublishBackoff     time.Duration `mapstructure:"publish_backoff"`
	OperationTimeout   time.Duration `mapstructure:"operation_timeout"`
}

// TrimmerWorker tunes the stream trimmer loop.
type TrimmerWorker struct {
	Enabled          bool          `mapstructure:"enabled"`
	Interval         time.Duration `mapstructure:"interval"`
	OperationTimeout time.Duration `mapstructure:"operation_timeout"`
}

// Logging configures the structured logger.
type Logging struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
	Output string `mapstructure:"output"`
}

// Load reads the YAML file and overlays CART_-prefixed environment variables.
func Load(path string) (*Config, error) {
	v := viper.New()
	v.SetConfigFile(path)
	v.SetEnvPrefix("CART")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()
	// https is the safe default; a bool zero value would silently allow http.
	v.SetDefault("media.require_https", true)
	v.SetDefault("media.cdn_base_url", "")
	v.SetDefault("media.placeholder_url", "")
	v.SetDefault("kafka.sasl_username", "")
	v.SetDefault("kafka.sasl_password", "")

	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("read config file %s: %w", path, err)
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("unmarshal config: %w", err)
	}

	applyDefaults(&cfg)
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

// Validate rejects a configuration that would violate a design invariant.
func (c *Config) Validate() error {
	if len(c.Redis.Addrs) == 0 {
		return fmt.Errorf("redis.addrs is required: redis is the cart source of truth")
	}
	if len(c.Kafka.Brokers) == 0 {
		return fmt.Errorf("kafka.brokers is required")
	}
	if c.Kafka.TopicCartEvents == "" {
		return fmt.Errorf("kafka.topic_cart_events is required")
	}
	if c.Kafka.SASLUsername != "" && c.Kafka.SASLPassword == "" {
		return fmt.Errorf("kafka.sasl_password is required with kafka.sasl_username")
	}
	if c.Product.Address == "" {
		return fmt.Errorf("product.address is required")
	}
	if c.Worker.UserDeletedConsumer.Enabled {
		if c.Kafka.TopicAuthEvents == "" || c.Kafka.ConsumerGroupAuth == "" {
			return fmt.Errorf("kafka.topic_auth_events and kafka.consumer_group_auth are required when worker.user_deleted_consumer is enabled")
		}
	}

	// Without these the cart would hand storage keys or broken URLs to the
	// browser, so refuse to start instead.
	if err := validateMediaURL("media.cdn_base_url", c.Media.CDNBaseURL, c.Media.RequireHTTPS); err != nil {
		return err
	}
	if strings.HasSuffix(c.Media.CDNBaseURL, "/") {
		return fmt.Errorf("media.cdn_base_url must not end with a slash")
	}
	if err := validateMediaURL("media.placeholder_url", c.Media.PlaceholderURL, c.Media.RequireHTTPS); err != nil {
		return err
	}

	// The bucket count is a data contract, not a tunable. Silently accepting a
	// different value would route carts to keys the running namespace does not
	// own, which no rollback could repair.
	if c.Cart.BucketCount != domain.BucketCount {
		return fmt.Errorf(
			"cart.bucket_count is %d but namespace %s is compiled for %d; changing the bucket count requires a new namespace",
			c.Cart.BucketCount, domain.Namespace, domain.BucketCount,
		)
	}

	// The active TTL has to outlive the abandonment deadline, or carts expire
	// before the marker can ever see them and no event is ever published.
	if c.Cart.ActiveTTL <= c.Cart.AbandonedAfter {
		return fmt.Errorf(
			"cart.active_ttl (%s) must exceed cart.abandoned_after (%s) so the marker has a buffer to run in",
			c.Cart.ActiveTTL, c.Cart.AbandonedAfter,
		)
	}
	if c.Cart.AbandonedTTL <= 0 {
		return fmt.Errorf("cart.abandoned_ttl must be positive")
	}
	if c.Cart.ProductValidationTTL <= 0 {
		return fmt.Errorf("cart.product_validation_ttl must be positive")
	}
	if c.Cart.IdempotencyTTL <= 0 {
		return fmt.Errorf("cart.idempotency_ttl must be positive")
	}
	return nil
}

// validateMediaURL accepts an absolute https URL, or http as well when https
// is not required.
func validateMediaURL(name, value string, requireHTTPS bool) error {
	if value == "" {
		return fmt.Errorf("%s is required", name)
	}
	parsed, err := url.Parse(value)
	if err != nil || parsed.Host == "" {
		return fmt.Errorf("%s must be an absolute URL, got %q", name, value)
	}
	switch {
	case parsed.Scheme == "https":
		return nil
	case parsed.Scheme == "http" && !requireHTTPS:
		return nil
	case parsed.Scheme == "http":
		return fmt.Errorf("%s must use https unless media.require_https is false", name)
	default:
		return fmt.Errorf("%s must be an http(s) URL, got %q", name, value)
	}
}

func applyDefaults(c *Config) {
	if c.App.Port == 0 {
		c.App.Port = 8080
	}
	if c.App.Host == "" {
		c.App.Host = "0.0.0.0"
	}
	if c.App.ShutdownTimeout == 0 {
		c.App.ShutdownTimeout = 30 * time.Second
	}
	if c.App.ReadinessTimeout == 0 {
		c.App.ReadinessTimeout = 2 * time.Second
	}
	if c.Redis.PoolSize == 0 {
		c.Redis.PoolSize = 100
	}
	if c.Redis.DialTimeout == 0 {
		c.Redis.DialTimeout = 5 * time.Second
	}
	if c.Redis.ReadTimeout == 0 {
		c.Redis.ReadTimeout = 3 * time.Second
	}
	if c.Redis.WriteTimeout == 0 {
		c.Redis.WriteTimeout = 3 * time.Second
	}
	if c.Cart.BucketCount == 0 {
		c.Cart.BucketCount = domain.BucketCount
	}
	if len(c.Cart.InternalCallers) == 0 {
		c.Cart.InternalCallers = []string{"checkout-service"}
	}
	if c.Kafka.RequiredAcks == "" {
		c.Kafka.RequiredAcks = "all"
	}
	if c.Kafka.ProduceTimeout == 0 {
		c.Kafka.ProduceTimeout = 10 * time.Second
	}
	if c.Kafka.EventDataSchema == "" {
		c.Kafka.EventDataSchema = "/contracts/events/cart/v1/schemas/cart-abandoned.schema.json"
	}
	if c.Product.DialTimeout == 0 {
		c.Product.DialTimeout = 5 * time.Second
	}
	if c.Product.GetSkuTimeout == 0 {
		c.Product.GetSkuTimeout = 200 * time.Millisecond
	}
	if c.Product.GetBatchSkusTimeout == 0 {
		c.Product.GetBatchSkusTimeout = 200 * time.Millisecond
	}
	if c.Product.CacheSize == 0 {
		c.Product.CacheSize = 4096
	}
	if c.Logging.Level == "" {
		c.Logging.Level = "info"
	}
}
