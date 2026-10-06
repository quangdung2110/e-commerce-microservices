package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"cart-service/internal/domain"
)

// The shipped configuration is part of the deliverable: if it does not satisfy
// the service's own invariants, the container will not start.
func TestShippedConfigurationIsValid(t *testing.T) {
	cfg, err := Load(filepath.Join("..", "..", "..", "configs", "application.yaml"))
	if err != nil {
		t.Fatalf("load the shipped configuration: %v", err)
	}

	if cfg.Cart.BucketCount != domain.BucketCount {
		t.Fatalf("bucket_count = %d, want %d", cfg.Cart.BucketCount, domain.BucketCount)
	}
	if cfg.Cart.AbandonedAfter != 30*24*time.Hour {
		t.Fatalf("abandoned_after = %s, want 30 days", cfg.Cart.AbandonedAfter)
	}
	if cfg.Cart.ActiveTTL != 37*24*time.Hour {
		t.Fatalf("active_ttl = %s, want 37 days", cfg.Cart.ActiveTTL)
	}
	if cfg.Cart.AbandonedTTL != 7*24*time.Hour {
		t.Fatalf("abandoned_ttl = %s, want 7 days", cfg.Cart.AbandonedTTL)
	}
	if cfg.Kafka.TopicCartEvents != "t.cart.events" {
		t.Fatalf("topic_cart_events = %q", cfg.Kafka.TopicCartEvents)
	}
	if cfg.Kafka.RequiredAcks != "all" {
		t.Fatalf("required_acks = %q; anything but all forfeits the durability the outbox provides", cfg.Kafka.RequiredAcks)
	}
	// A hard deadline on Product is mandatory, not advisory.
	if cfg.Product.GetBatchSkusTimeout <= 0 || cfg.Product.GetBatchSkusTimeout > time.Second {
		t.Fatalf("get_batch_skus_timeout = %s, want a hard sub-second deadline", cfg.Product.GetBatchSkusTimeout)
	}
}

func baseConfig() *Config {
	return &Config{
		Redis:   Redis{Addrs: []string{"localhost:6379"}},
		Kafka:   Kafka{Brokers: []string{"localhost:9092"}, TopicCartEvents: "t.cart.events"},
		Product: Product{Address: "localhost:9091"},
		Media: Media{
			CDNBaseURL:     "https://cdn.example.test/media",
			PlaceholderURL: "https://cdn.example.test/media/placeholder/product.svg",
			RequireHTTPS:   true,
		},
		Cart: Cart{
			BucketCount:          domain.BucketCount,
			AbandonedAfter:       30 * 24 * time.Hour,
			ActiveTTL:            37 * 24 * time.Hour,
			AbandonedTTL:         7 * 24 * time.Hour,
			ProductValidationTTL: 45 * time.Second,
			IdempotencyTTL:       24 * time.Hour,
		},
	}
}

// The bucket count is a data contract. Accepting a different value at runtime
// would route carts to keys the running namespace does not own.
func TestValidateRejectsAChangedBucketCount(t *testing.T) {
	cfg := baseConfig()
	cfg.Cart.BucketCount = 128

	err := cfg.Validate()
	if err == nil {
		t.Fatal("a changed bucket count was accepted")
	}
	if !strings.Contains(err.Error(), "namespace") {
		t.Fatalf("the error does not explain the constraint: %v", err)
	}
}

// Without the TTL buffer, carts expire before the marker can ever see them and
// CART_ABANDONED is silently never published.
func TestValidateRejectsATTLShorterThanTheAbandonmentWindow(t *testing.T) {
	cfg := baseConfig()
	cfg.Cart.ActiveTTL = 20 * 24 * time.Hour

	if err := cfg.Validate(); err == nil {
		t.Fatal("an active ttl shorter than the abandonment window was accepted")
	}
}

func TestValidateRequiresItsDependencies(t *testing.T) {
	cases := map[string]func(*Config){
		"redis":       func(c *Config) { c.Redis.Addrs = nil },
		"kafka":       func(c *Config) { c.Kafka.Brokers = nil },
		"topic":       func(c *Config) { c.Kafka.TopicCartEvents = "" },
		"product":     func(c *Config) { c.Product.Address = "" },
		"cdn":         func(c *Config) { c.Media.CDNBaseURL = "" },
		"placeholder": func(c *Config) { c.Media.PlaceholderURL = "" },
	}

	for name, mutate := range cases {
		cfg := baseConfig()
		mutate(cfg)
		if err := cfg.Validate(); err == nil {
			t.Fatalf("a configuration missing %s was accepted", name)
		}
	}
}

// A malformed media URL would put broken image links in every cart response.
func TestValidateRejectsUnusableMediaURLs(t *testing.T) {
	cases := map[string]func(*Config){
		"relative cdn":         func(c *Config) { c.Media.CDNBaseURL = "/media" },
		"trailing slash":       func(c *Config) { c.Media.CDNBaseURL = "https://cdn.example.test/media/" },
		"http cdn":             func(c *Config) { c.Media.CDNBaseURL = "http://cdn.example.test/media" },
		"http placeholder":     func(c *Config) { c.Media.PlaceholderURL = "http://cdn.example.test/p.svg" },
		"non-http placeholder": func(c *Config) { c.Media.PlaceholderURL = "ftp://cdn.example.test/p.svg" },
	}

	for name, mutate := range cases {
		cfg := baseConfig()
		mutate(cfg)
		if err := cfg.Validate(); err == nil {
			t.Fatalf("%s was accepted", name)
		}
	}
}

// Local docker-compose serves MinIO over plain http through the gateway.
func TestValidateAllowsHTTPMediaWhenHTTPSIsNotRequired(t *testing.T) {
	cfg := baseConfig()
	cfg.Media = Media{
		CDNBaseURL:     "http://localhost:8088/media",
		PlaceholderURL: "http://localhost:8088/media/placeholder/product.svg",
		RequireHTTPS:   false,
	}

	if err := cfg.Validate(); err != nil {
		t.Fatalf("local http media was rejected: %v", err)
	}
}

// require_https defaults to true: leaving it out of a file must not quietly
// allow plain http.
func TestLoadDefaultsToRequiringHTTPS(t *testing.T) {
	path := filepath.Join(t.TempDir(), "application.yaml")
	content := strings.Join([]string{
		"redis: {addrs: [\"localhost:6379\"]}",
		"kafka: {brokers: [\"localhost:9092\"], topic_cart_events: t.cart.events}",
		"product: {address: \"localhost:9091\"}",
		"media: {cdn_base_url: \"http://localhost:8088/media\", placeholder_url: \"http://localhost:8088/media/p.svg\"}",
		"cart: {abandoned_after: 720h, active_ttl: 888h, abandoned_ttl: 168h, product_validation_ttl: 45s, idempotency_ttl: 24h}",
	}, "\n")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	if _, err := Load(path); err == nil || !strings.Contains(err.Error(), "https") {
		t.Fatalf("Load accepted http media without media.require_https=false: %v", err)
	}
}
