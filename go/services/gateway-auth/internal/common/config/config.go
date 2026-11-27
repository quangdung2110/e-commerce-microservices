// Package config loads gateway-auth configuration from a YAML file with
// environment overrides. The contract variables shared with auth-service
// (docs/gateway/runtime-contract.md section 6) keep their exact names; every
// other key is overridable with the GATEWAY_AUTH_ prefix.
package config

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	Server  ServerConfig  `mapstructure:"server"`
	JWT     JWTConfig     `mapstructure:"jwt"`
	JWKS    JWKSConfig    `mapstructure:"jwks"`
	Redis   RedisConfig   `mapstructure:"redis"`
	Logging LoggingConfig `mapstructure:"logging"`
}

type ServerConfig struct {
	Port            int           `mapstructure:"port"`
	ReadTimeout     time.Duration `mapstructure:"read_timeout"`
	WriteTimeout    time.Duration `mapstructure:"write_timeout"`
	VerifyDeadline  time.Duration `mapstructure:"verify_deadline"`
	ShutdownTimeout time.Duration `mapstructure:"shutdown_timeout"`
}

type JWTConfig struct {
	Issuer            string        `mapstructure:"issuer"`
	Audience          string        `mapstructure:"audience"`
	AllowedAlgorithms []string      `mapstructure:"allowed_algorithms"`
	Leeway            time.Duration `mapstructure:"leeway"`
}

type JWKSConfig struct {
	URL                string        `mapstructure:"url"`
	RefreshInterval    time.Duration `mapstructure:"refresh_interval"`
	FetchTimeout       time.Duration `mapstructure:"fetch_timeout"`
	MinRefreshInterval time.Duration `mapstructure:"min_refresh_interval"`
	RetiredKeyGrace    time.Duration `mapstructure:"retired_key_grace"`
	MaxStale           time.Duration `mapstructure:"max_stale"`
	MaxKeys            int           `mapstructure:"max_keys"`
}

type RedisConfig struct {
	RequireTLS            bool          `mapstructure:"require_tls"`
	URL                   string        `mapstructure:"url"`
	Timeout               time.Duration `mapstructure:"timeout"`
	RevocationPrefix      string        `mapstructure:"revocation_prefix"`
	JWKSInvalidateChannel string        `mapstructure:"jwks_invalidate_channel"`
}

type LoggingConfig struct {
	Level string `mapstructure:"level"`
}

// supportedAlgorithms lists what the key source can verify. Auth signs RS256
// only; "none" and HMAC are never acceptable for a verifier that holds no secret.
var supportedAlgorithms = map[string]bool{"RS256": true}

// Load reads path and applies environment overrides.
func Load(path string) (*Config, error) {
	v := viper.New()
	v.SetConfigFile(path)
	v.SetEnvPrefix("GATEWAY_AUTH")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()
	v.SetDefault("redis.require_tls", false)

	// Contract names shared with auth-service.
	bindings := map[string]string{
		"jwt.issuer":             "AUTH_JWT_ISSUER",
		"jwt.audience":           "AUTH_JWT_AUDIENCE",
		"jwt.allowed_algorithms": "AUTH_JWT_ALLOWED_ALGORITHMS",
		"jwks.url":               "AUTH_JWKS_URL",
		"redis.url":              "AUTH_REVOCATION_REDIS_URL",
	}
	for key, env := range bindings {
		if err := v.BindEnv(key, env); err != nil {
			return nil, fmt.Errorf("bind %s: %w", env, err)
		}
	}

	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("read config %s: %w", path, err)
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("decode config: %w", err)
	}
	// A comma-separated env value arrives as one element.
	cfg.JWT.AllowedAlgorithms = splitList(cfg.JWT.AllowedAlgorithms)

	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func splitList(values []string) []string {
	var out []string
	for _, value := range values {
		for _, part := range strings.Split(value, ",") {
			if part = strings.TrimSpace(part); part != "" {
				out = append(out, part)
			}
		}
	}
	return out
}

// Validate rejects configurations that would weaken verification. The service
// refuses to start rather than run in a fail-open shape.
func (c *Config) Validate() error {
	var problems []string
	if c.Server.Port <= 0 {
		problems = append(problems, "server.port must be positive")
	}
	if c.Server.VerifyDeadline <= 0 {
		problems = append(problems, "server.verify_deadline must be positive")
	}
	if strings.TrimSpace(c.JWT.Issuer) == "" {
		problems = append(problems, "jwt.issuer (AUTH_JWT_ISSUER) is required")
	}
	if strings.TrimSpace(c.JWT.Audience) == "" {
		problems = append(problems, "jwt.audience (AUTH_JWT_AUDIENCE) is required")
	}
	if len(c.JWT.AllowedAlgorithms) == 0 {
		problems = append(problems, "jwt.allowed_algorithms (AUTH_JWT_ALLOWED_ALGORITHMS) is required")
	}
	for _, alg := range c.JWT.AllowedAlgorithms {
		if !supportedAlgorithms[alg] {
			problems = append(problems, fmt.Sprintf("jwt.allowed_algorithms: %q is not supported", alg))
		}
	}
	if c.JWT.Leeway < 0 || c.JWT.Leeway > time.Minute {
		problems = append(problems, "jwt.leeway must be between 0 and 1m")
	}
	if strings.TrimSpace(c.JWKS.URL) == "" {
		problems = append(problems, "jwks.url (AUTH_JWKS_URL) is required")
	}
	if c.JWKS.RefreshInterval <= 0 || c.JWKS.FetchTimeout <= 0 || c.JWKS.MinRefreshInterval <= 0 {
		problems = append(problems, "jwks refresh_interval, fetch_timeout and min_refresh_interval must be positive")
	}
	if c.JWKS.RetiredKeyGrace <= 0 || c.JWKS.MaxStale <= 0 || c.JWKS.MaxKeys <= 0 {
		problems = append(problems, "jwks retired_key_grace, max_stale and max_keys must be positive")
	}
	if strings.TrimSpace(c.Redis.URL) == "" {
		problems = append(problems, "redis.url (AUTH_REVOCATION_REDIS_URL) is required")
	}
	if c.Redis.Timeout <= 0 {
		problems = append(problems, "redis.timeout must be positive")
	}
	if c.Redis.RevocationPrefix == "" || c.Redis.JWKSInvalidateChannel == "" {
		problems = append(problems, "redis revocation_prefix and jwks_invalidate_channel are required")
	}
	if len(problems) > 0 {
		return errors.New("invalid configuration: " + strings.Join(problems, "; "))
	}
	return nil
}
