package config

import (
	"strings"
	"testing"
	"time"
)

func TestLoadDefaultsAreValid(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("defaults must validate: %v", err)
	}
	if cfg.Deliver.OrderingLease != 45*time.Second || cfg.Deliver.OrderingHeartbeatInterval != 10*time.Second || cfg.Deliver.OrderingBusyRetry != 2*time.Second {
		t.Fatalf("ordering defaults: %+v", cfg.Deliver)
	}
	k, err := cfg.Keyring()
	if err != nil {
		t.Fatalf("development falls back to the dev key: %v", err)
	}
	if k.ActiveVersion() != 1 {
		t.Fatalf("active version %d", k.ActiveVersion())
	}
}

func TestShippedApplicationYAMLIsValid(t *testing.T) {
	cfg, err := Load("../../../configs/application.yaml")
	if err != nil {
		t.Fatalf("configs/application.yaml: %v", err)
	}
	if cfg.Deliver.OrderingLease != 45*time.Second || cfg.Privacy.HMACKeyVersion != 1 {
		t.Fatalf("yaml values not applied: %+v %+v", cfg.Deliver, cfg.Privacy)
	}
}

// The shared Redis requires a password (mesh ADR-002): configs/application.yaml
// reads it from REDIS_PASSWORD, which docker-compose sets.
func TestShippedApplicationYAMLReadsTheRedisPassword(t *testing.T) {
	t.Setenv("REDIS_PASSWORD", "redis-secret-from-env")
	cfg, err := Load("../../../configs/application.yaml")
	if err != nil {
		t.Fatalf("configs/application.yaml: %v", err)
	}
	if cfg.Redis.Password != "redis-secret-from-env" {
		t.Fatalf("redis.password = %q, want the REDIS_PASSWORD value", cfg.Redis.Password)
	}
}

func TestValidateOrderingAndPrivacy(t *testing.T) {
	base := func(t *testing.T) *Config {
		t.Helper()
		cfg, err := Load("")
		if err != nil {
			t.Fatal(err)
		}
		return cfg
	}
	cases := []struct {
		name    string
		mutate  func(*Config)
		wantErr string
	}{
		{"ordering lease shorter than the send path", func(c *Config) { c.Deliver.OrderingLease = 20 * time.Second }, "ordering_lease"},
		{"heartbeat too slow", func(c *Config) { c.Deliver.OrderingHeartbeatInterval = 20 * time.Second }, "ordering_heartbeat_interval"},
		{"busy retry zero", func(c *Config) { c.Deliver.OrderingBusyRetry = 0 }, "ordering_busy_retry"},
		{"key version zero", func(c *Config) { c.Privacy.HMACKeyVersion = 0 }, "hmac_key_version"},
		{"staging without key", func(c *Config) { c.App.Env = "staging" }, "NOTIFICATION_PRIVACY_HMAC_KEY"},
		{"short key outside development", func(c *Config) { c.App.Env = "staging"; c.Privacy.HMACKey = "short" }, "at least 32 bytes"},
		{"bad previous keys", func(c *Config) { c.Privacy.PreviousHMACKeys = "nope" }, "previous_hmac_keys"},
		{"previous redefines active", func(c *Config) { c.Privacy.PreviousHMACKeys = "1:abc" }, "redefines active"},
		{"staging with a real key", func(c *Config) {
			c.App.Env = "staging"
			c.Privacy.HMACKey = strings.Repeat("k", 32)
			c.Privacy.HMACKeyVersion = 2
			c.Privacy.PreviousHMACKeys = "1:" + strings.Repeat("o", 32)
		}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := base(t)
			tc.mutate(cfg)
			err := cfg.Validate()
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("want error containing %q, got %v", tc.wantErr, err)
			}
		})
	}
}

func TestPrivacyKeyFromEnvironment(t *testing.T) {
	t.Setenv("NOTIFICATION_APP_ENV", "staging")
	t.Setenv("NOTIFICATION_PRIVACY_HMAC_KEY", strings.Repeat("s", 40))
	t.Setenv("NOTIFICATION_PRIVACY_HMAC_KEY_VERSION", "3")
	cfg, err := Load("")
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	k, err := cfg.Keyring()
	if err != nil {
		t.Fatal(err)
	}
	if k.ActiveVersion() != 3 {
		t.Fatalf("version from env: %d", k.ActiveVersion())
	}
}

// D-21: the mesh authenticates callers, so production needs no TLS material
// at the application layer and the allowlists must not be empty.
func TestInternalIdentityConfiguration(t *testing.T) {
	cfg, err := Load("")
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(cfg.Internal.StatusPrincipals, ","); got != "order-service,finance-ops" {
		t.Fatalf("status principals: %s", got)
	}
	if got := strings.Join(cfg.Internal.OpsPrincipals, ","); got != "notification-ops" {
		t.Fatalf("ops principals: %s", got)
	}

	prod := *cfg
	prod.App.Env = "production"
	prod.Email.Provider = "ses"
	prod.Privacy.HMACKey = strings.Repeat("p", 32)
	if err := prod.Validate(); err != nil {
		t.Fatalf("production must not require application-level mTLS: %v", err)
	}

	for name, mutate := range map[string]func(*Config){
		"status": func(c *Config) { c.Internal.StatusPrincipals = []string{" "} },
		"ops":    func(c *Config) { c.Internal.OpsPrincipals = nil },
	} {
		c := *cfg
		mutate(&c)
		if err := c.Validate(); err == nil || !strings.Contains(err.Error(), "internal."+name+"_principals") {
			t.Fatalf("%s: want an allowlist error, got %v", name, err)
		}
	}
}

func TestInternalPrincipalsFromEnvironment(t *testing.T) {
	t.Setenv("NOTIFICATION_INTERNAL_STATUS_PRINCIPALS", "order-service,support-tool")
	cfg, err := Load("")
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(cfg.Internal.StatusPrincipals, ","); got != "order-service,support-tool" {
		t.Fatalf("override from env: %s", got)
	}
}
