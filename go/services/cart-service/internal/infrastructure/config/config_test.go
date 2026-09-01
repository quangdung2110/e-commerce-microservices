package config

import (
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
		"redis":   func(c *Config) { c.Redis.Addrs = nil },
		"kafka":   func(c *Config) { c.Kafka.Brokers = nil },
		"topic":   func(c *Config) { c.Kafka.TopicCartEvents = "" },
		"product": func(c *Config) { c.Product.Address = "" },
	}

	for name, mutate := range cases {
		cfg := baseConfig()
		mutate(cfg)
		if err := cfg.Validate(); err == nil {
			t.Fatalf("a configuration missing %s was accepted", name)
		}
	}
}
