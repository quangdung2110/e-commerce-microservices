// Package config loads the Auth Service runtime configuration from a YAML file
// with environment-variable overrides. Secrets never live in the file: values
// such as ${DB_PASSWORD} are expanded from the environment at load time.
package config

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/viper"
)

type Config struct {
	App           AppConfig           `mapstructure:"app"`
	Server        ServerConfig        `mapstructure:"server"`
	Internal      InternalConfig      `mapstructure:"internal"`
	Database      DatabaseConfig      `mapstructure:"database"`
	Redis         RedisConfig         `mapstructure:"redis"`
	Kafka         KafkaConfig         `mapstructure:"kafka"`
	JWT           JWTConfig           `mapstructure:"jwt"`
	Refresh       RefreshConfig       `mapstructure:"refresh"`
	Security      SecurityConfig      `mapstructure:"security"`
	OTP           OTPConfig           `mapstructure:"otp"`
	PasswordReset PasswordResetConfig `mapstructure:"password_reset"`
	Notification  NotificationConfig  `mapstructure:"notification"`
	Keys          KeysConfig          `mapstructure:"keys"`
	Workers       WorkersConfig       `mapstructure:"workers"`
	Logging       LoggingConfig       `mapstructure:"logging"`
}

type AppConfig struct {
	Name string `mapstructure:"name"`
	Host string `mapstructure:"host"`
	Port int    `mapstructure:"port"`
	Env  string `mapstructure:"env"`
}

func (a AppConfig) IsProduction() bool { return a.Env == "production" }

// IsDevelopment is true only for a local run. Every other environment, staging
// included, must carry real secrets (see validateSecrets).
func (a AppConfig) IsDevelopment() bool { return a.Env == "development" || a.Env == "dev" }

type ServerConfig struct {
	ReadTimeout     time.Duration `mapstructure:"read_timeout"`
	WriteTimeout    time.Duration `mapstructure:"write_timeout"`
	IdleTimeout     time.Duration `mapstructure:"idle_timeout"`
	ShutdownTimeout time.Duration `mapstructure:"shutdown_timeout"`
}

// InternalConfig configures the cluster-internal server that serves the
// notification delivery and notification contact APIs. It is never exposed
// through the public gateway. Transport security and caller admission belong to
// the service mesh (mTLS plus an AuthorizationPolicy per principal and path);
// the application only checks the X-Service-Identity header against
// AllowedCallers as defence in depth (ADR-007).
type InternalConfig struct {
	Enabled bool   `mapstructure:"enabled"`
	Host    string `mapstructure:"host"`
	Port    int    `mapstructure:"port"`
	// AllowedCallers lists the service identities that may resolve a delivery or
	// a contact.
	AllowedCallers []string `mapstructure:"allowed_callers"`
	// SigningKey authenticates the caller without a mesh: every request carries
	// X-Service-Signature, an HMAC-SHA256 under this key shared with
	// Notification (mesh ADR-004). Empty disables the check, which only
	// development accepts.
	SigningKey string `mapstructure:"signing_key"`
	// SignatureTolerance bounds the clock difference and the replay window of
	// a signed request.
	SignatureTolerance time.Duration `mapstructure:"signature_tolerance"`
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
	Username         string        `mapstructure:"username"`
	TLSEnabled       bool          `mapstructure:"tls_enabled"`
	Host             string        `mapstructure:"host"`
	Port             int           `mapstructure:"port"`
	Password         string        `mapstructure:"password"`
	DB               int           `mapstructure:"db"`
	PoolSize         int           `mapstructure:"pool_size"`
	MinIdleConns     int           `mapstructure:"min_idle_conns"`
	DialTimeout      time.Duration `mapstructure:"dial_timeout"`
	ReadTimeout      time.Duration `mapstructure:"read_timeout"`
	WriteTimeout     time.Duration `mapstructure:"write_timeout"`
	RevocationPrefix string        `mapstructure:"revocation_prefix"`
	StatePrefix      string        `mapstructure:"state_prefix"`
}

func (r RedisConfig) Addr() string { return fmt.Sprintf("%s:%d", r.Host, r.Port) }

type KafkaConfig struct {
	TLSEnabled         bool          `mapstructure:"tls_enabled"`
	Brokers            []string      `mapstructure:"brokers"`
	IdentityTopic      string        `mapstructure:"identity_topic"`
	NotificationTopic  string        `mapstructure:"notification_topic"`
	PublishMaxAttempts int           `mapstructure:"publish_max_attempts"`
	PublishBackoff     time.Duration `mapstructure:"publish_backoff"`
	PublishTimeout     time.Duration `mapstructure:"publish_timeout"`
	// SASL/SCRAM-SHA-512 principal of the service (mesh ADR-003). An empty
	// username keeps PLAINTEXT, for tests and a standalone broker.
	SASLUsername string `mapstructure:"sasl_username"`
	SASLPassword string `mapstructure:"sasl_password"`
	// Enabled allows a local run without a broker. Publish failures are still
	// logged and counted so the reconciliation gap of ADR-005 stays visible.
	Enabled bool `mapstructure:"enabled"`
}

type JWTConfig struct {
	Issuer         string        `mapstructure:"issuer"`
	Audience       string        `mapstructure:"audience"`
	Algorithm      string        `mapstructure:"algorithm"`
	AccessTokenTTL time.Duration `mapstructure:"access_token_ttl"`
	// ClockSkew widens nbf so a verifier with a slightly slow clock still accepts
	// a freshly minted token.
	ClockSkew time.Duration `mapstructure:"clock_skew"`
	// KeyCacheTTL bounds how long the signer caches decrypted key material.
	KeyCacheTTL time.Duration `mapstructure:"key_cache_ttl"`
	// JwksCacheControl is returned verbatim on the JWKS endpoint.
	JwksCacheControl string `mapstructure:"jwks_cache_control"`
}

type RefreshConfig struct {
	// SlidingTTL is the lifetime of the current refresh token. Every rotation
	// extends it, bounded by AbsoluteTTL.
	SlidingTTL   time.Duration `mapstructure:"sliding_ttl"`
	AbsoluteTTL  time.Duration `mapstructure:"absolute_ttl"`
	CookieName   string        `mapstructure:"cookie_name"`
	CookiePath   string        `mapstructure:"cookie_path"`
	CookieDomain string        `mapstructure:"cookie_domain"`
	// CookieSecure must stay true outside local development.
	CookieSecure bool `mapstructure:"cookie_secure"`
}

type Argon2Config struct {
	Memory      uint32 `mapstructure:"memory"`
	Iterations  uint32 `mapstructure:"iterations"`
	Parallelism uint8  `mapstructure:"parallelism"`
	SaltLength  uint32 `mapstructure:"salt_length"`
	KeyLength   uint32 `mapstructure:"key_length"`
}

type LockoutConfig struct {
	MaxFailedAttempts int           `mapstructure:"max_failed_attempts"`
	LockDuration      time.Duration `mapstructure:"lock_duration"`
	// CounterResetAfter drops a stale failed-login streak so an old mistake does
	// not combine with a new one months later.
	CounterResetAfter time.Duration `mapstructure:"counter_reset_after"`
}

type RateLimitRule struct {
	Limit  int           `mapstructure:"limit"`
	Window time.Duration `mapstructure:"window"`
}

func (r RateLimitRule) Enabled() bool { return r.Limit > 0 && r.Window > 0 }

type RateLimitConfig struct {
	LoginPerIP         RateLimitRule `mapstructure:"login_per_ip"`
	LoginPerAccount    RateLimitRule `mapstructure:"login_per_account"`
	RegistrationPerIP  RateLimitRule `mapstructure:"registration_per_ip"`
	OtpPerAccount      RateLimitRule `mapstructure:"otp_per_account"`
	PasswordResetPerIP RateLimitRule `mapstructure:"password_reset_per_ip"`
	RefreshPerIP       RateLimitRule `mapstructure:"refresh_per_ip"`
	// InternalPerResource is applied per (calling service, delivery or user id) on
	// the internal listener.
	InternalPerResource RateLimitRule `mapstructure:"internal_per_resource"`
}

type PasswordPolicyConfig struct {
	MinLength int `mapstructure:"min_length"`
	MaxLength int `mapstructure:"max_length"`
}

type SecurityConfig struct {
	Argon2         Argon2Config         `mapstructure:"argon2"`
	Lockout        LockoutConfig        `mapstructure:"lockout"`
	RateLimit      RateLimitConfig      `mapstructure:"rate_limit"`
	PasswordPolicy PasswordPolicyConfig `mapstructure:"password_policy"`
	// TrustedProxies tells gin which hops may set the client IP headers: the
	// gateway address only.
	TrustedProxies []string `mapstructure:"trusted_proxies"`
	// ClientIPHeaders are read, in order, when the request comes from a trusted
	// proxy. Traefik sets X-Real-Ip to the client it saw (or keeps the one the
	// frontend, its only trusted hop, forwarded), so X-Real-Ip names the browser
	// whether or not the request went through Next (mesh ADR-004).
	ClientIPHeaders []string `mapstructure:"client_ip_headers"`
}

type OTPConfig struct {
	TTL          time.Duration `mapstructure:"ttl"`
	MaxAttempts  int           `mapstructure:"max_attempts"`
	ResendLimit  int           `mapstructure:"resend_limit"`
	ResendWindow time.Duration `mapstructure:"resend_window"`
}

type PasswordResetConfig struct {
	TTL time.Duration `mapstructure:"ttl"`
	// URLTemplate receives the opaque token in the fragment so it never reaches a
	// server log. The {token} placeholder is replaced at send time.
	URLTemplate string `mapstructure:"url_template"`
}

type NotificationConfig struct {
	// DeliveryTTL bounds how long Notification can resolve the render content.
	DeliveryTTL time.Duration `mapstructure:"delivery_ttl"`
}

type KeysConfig struct {
	RSABits int `mapstructure:"rsa_bits"`
	// RotationInterval is the age at which the ACTIVE signing key is rotated.
	RotationInterval time.Duration `mapstructure:"rotation_interval"`
	// RetirementDelay must be at least the maximum access-token lifetime so a
	// PHASE_OUT key stays in JWKS while tokens it signed can still be used.
	RetirementDelay time.Duration `mapstructure:"retirement_delay"`
	// MasterKeyBase64 is the AES-256-GCM key encrypting private key material.
	MasterKeyBase64 string `mapstructure:"master_key"`
	// MasterKeyVersion is written to encryption_key_version on new rows.
	MasterKeyVersion int16 `mapstructure:"master_key_version"`
	// PreviousMasterKeysBase64 maps an older version to its key so rows written
	// before a master-key rotation stay decryptable.
	PreviousMasterKeysBase64 map[string]string `mapstructure:"previous_master_keys"`
}

type WorkerConfig struct {
	Enabled  bool          `mapstructure:"enabled"`
	Interval time.Duration `mapstructure:"interval"`
	Timeout  time.Duration `mapstructure:"timeout"`
	Batch    int           `mapstructure:"batch"`
}

type WorkersConfig struct {
	LockTTL             time.Duration `mapstructure:"lock_ttl"`
	RefreshTokenCleanup WorkerConfig  `mapstructure:"refresh_token_cleanup"`
	KeyRotation         WorkerConfig  `mapstructure:"key_rotation"`
	KeyRetirement       WorkerConfig  `mapstructure:"key_retirement"`
	AutoUnlock          WorkerConfig  `mapstructure:"auto_unlock"`
	AccountHardDelete   WorkerConfig  `mapstructure:"account_hard_delete"`
	// RefreshTokenRetention keeps revoked or expired rows for a while so an
	// incident can still be investigated before cleanup removes them.
	RefreshTokenRetention time.Duration `mapstructure:"refresh_token_retention"`
}

type LoggingConfig struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
}

// MasterKey decodes and validates the configured AES-256-GCM master key.
func (k KeysConfig) MasterKey() ([]byte, error) {
	key, err := decodeKey(k.MasterKeyBase64)
	if err != nil {
		return nil, fmt.Errorf("keys.master_key %w", err)
	}
	return key, nil
}

// PreviousMasterKeys decodes the historical master keys by version.
func (k KeysConfig) PreviousMasterKeys() (map[int16][]byte, error) {
	out := make(map[int16][]byte, len(k.PreviousMasterKeysBase64))
	for version, encoded := range k.PreviousMasterKeysBase64 {
		parsed, err := strconv.ParseInt(version, 10, 16)
		if err != nil {
			return nil, fmt.Errorf("keys.previous_master_keys has a non-numeric version %q", version)
		}
		key, err := decodeKey(encoded)
		if err != nil {
			return nil, fmt.Errorf("keys.previous_master_keys[%s] %w", version, err)
		}
		out[int16(parsed)] = key
	}
	return out, nil
}

func hasName(names []string) bool {
	for _, name := range names {
		if strings.TrimSpace(name) != "" {
			return true
		}
	}
	return false
}

func decodeKey(encoded string) ([]byte, error) {
	if encoded == "" {
		return nil, fmt.Errorf("is required")
	}
	key, err := base64.StdEncoding.DecodeString(strings.TrimSpace(encoded))
	if err != nil {
		return nil, fmt.Errorf("is not valid base64: %w", err)
	}
	if len(key) != 32 {
		return nil, fmt.Errorf("must decode to 32 bytes, got %d", len(key))
	}
	return key, nil
}

// Load reads the YAML configuration, expands environment references inside it
// and applies AUTH_-prefixed overrides, so AUTH_JWT_ISSUER overrides jwt.issuer.
func Load(path string) (*Config, error) {
	v := viper.New()
	setDefaults(v)

	v.SetEnvPrefix("AUTH")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	if path == "" {
		path = os.Getenv("AUTH_CONFIG_FILE")
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

// Validate rejects a configuration that would break a security invariant.
func (c *Config) Validate() error {
	var problems []string

	if c.JWT.Issuer == "" {
		problems = append(problems, "jwt.issuer is required")
	}
	if c.Kafka.SASLUsername != "" && c.Kafka.SASLPassword == "" {
		problems = append(problems, "kafka.sasl_password is required with kafka.sasl_username")
	}
	if c.JWT.Audience == "" {
		problems = append(problems, "jwt.audience is required")
	}
	if c.JWT.Algorithm != "RS256" {
		problems = append(problems, "jwt.algorithm must be RS256")
	}
	if c.JWT.AccessTokenTTL <= 0 {
		problems = append(problems, "jwt.access_token_ttl must be positive")
	}
	if c.Refresh.SlidingTTL <= 0 || c.Refresh.AbsoluteTTL <= 0 {
		problems = append(problems, "refresh TTLs must be positive")
	}
	if c.Refresh.SlidingTTL > c.Refresh.AbsoluteTTL {
		problems = append(problems, "refresh.sliding_ttl must not exceed refresh.absolute_ttl")
	}
	if _, err := c.Keys.MasterKey(); err != nil {
		problems = append(problems, err.Error())
	}
	if _, err := c.Keys.PreviousMasterKeys(); err != nil {
		problems = append(problems, err.Error())
	}
	// A PHASE_OUT key must outlive every access token it signed (ADR-003).
	if c.Keys.RetirementDelay < c.JWT.AccessTokenTTL {
		problems = append(problems, "keys.retirement_delay must be at least jwt.access_token_ttl")
	}
	if c.Security.PasswordPolicy.MinLength < 12 {
		problems = append(problems, "security.password_policy.min_length must be at least 12")
	}
	if c.Security.PasswordPolicy.MaxLength < c.Security.PasswordPolicy.MinLength {
		problems = append(problems, "security.password_policy.max_length must not be below min_length")
	}
	if c.OTP.TTL <= 0 || c.OTP.MaxAttempts <= 0 {
		problems = append(problems, "otp.ttl and otp.max_attempts must be positive")
	}
	if c.PasswordReset.URLTemplate == "" {
		problems = append(problems, "password_reset.url_template is required")
	}
	if c.App.IsProduction() && !c.Refresh.CookieSecure {
		problems = append(problems, "refresh.cookie_secure must be true in production")
	}
	if c.Internal.Enabled && !hasName(c.Internal.AllowedCallers) {
		// The internal API returns recipient addresses and, for verification and
		// reset templates, the secret itself, so an empty allowlist would refuse
		// every caller and is a misconfigured deployment rather than a valid
		// "closed" mode.
		problems = append(problems, "internal.allowed_callers must name at least one service when the internal API is enabled")
	}
	if c.Internal.Enabled && c.Internal.Port == c.App.Port {
		problems = append(problems, "internal.port must differ from app.port")
	}
	if c.Internal.SignatureTolerance <= 0 {
		problems = append(problems, "internal.signature_tolerance must be positive")
	}
	if !c.App.IsDevelopment() {
		problems = append(problems, c.secretProblems()...)
	}
	if len(problems) > 0 {
		return fmt.Errorf("invalid configuration: %s", strings.Join(problems, "; "))
	}
	return nil
}

// secretProblems rejects, outside development, a secret that is missing, short,
// a committed development value or a placeholder (security review 2026-10-22,
// H1): the master key decrypts every private JWT key in auth.jwt_keys, and the
// signing key is the only proof of the caller of the internal API without a
// mesh.
func (c *Config) secretProblems() []string {
	var problems []string
	if p := secretProblem("keys.master_key (AUTH_KEYS_MASTER_KEY)", c.Keys.MasterKeyBase64, 32); p != "" {
		problems = append(problems, p)
	}
	for version, key := range c.Keys.PreviousMasterKeysBase64 {
		if p := secretProblem("keys.previous_master_keys["+version+"]", key, 32); p != "" {
			problems = append(problems, p)
		}
	}
	if c.Internal.Enabled {
		if p := secretProblem("internal.signing_key (AUTH_INTERNAL_SIGNING_KEY)", c.Internal.SigningKey, 32); p != "" {
			problems = append(problems, p)
		}
	}
	return problems
}

func setDefaults(v *viper.Viper) {
	v.SetDefault("app.name", "auth-service")
	v.SetDefault("app.host", "0.0.0.0")
	v.SetDefault("app.port", 8080)
	v.SetDefault("app.env", "development")

	v.SetDefault("server.read_timeout", "15s")
	v.SetDefault("server.write_timeout", "15s")
	v.SetDefault("server.idle_timeout", "120s")
	v.SetDefault("server.shutdown_timeout", "20s")

	v.SetDefault("internal.enabled", true)
	v.SetDefault("internal.host", "0.0.0.0")
	v.SetDefault("internal.port", 8081)
	v.SetDefault("internal.allowed_callers", []string{"notification-service"})
	v.SetDefault("internal.signing_key", "")
	v.SetDefault("internal.signature_tolerance", "60s")

	v.SetDefault("database.host", "localhost")
	v.SetDefault("database.port", 5432)
	v.SetDefault("database.name", "auth_db")
	v.SetDefault("database.username", "postgres")
	v.SetDefault("database.sslmode", "disable")
	v.SetDefault("database.max_open_conns", 25)
	v.SetDefault("database.min_conns", 5)
	v.SetDefault("database.conn_max_lifetime", "30m")
	v.SetDefault("database.connect_timeout", "5s")
	v.SetDefault("database.statement_timeout", "5s")

	v.SetDefault("redis.username", "")
	v.SetDefault("redis.tls_enabled", false)
	v.SetDefault("redis.host", "localhost")
	v.SetDefault("redis.port", 6379)
	v.SetDefault("redis.db", 0)
	v.SetDefault("redis.pool_size", 20)
	v.SetDefault("redis.min_idle_conns", 5)
	v.SetDefault("redis.dial_timeout", "1s")
	v.SetDefault("redis.read_timeout", "1s")
	v.SetDefault("redis.write_timeout", "1s")
	v.SetDefault("redis.revocation_prefix", "auth:revocation")
	v.SetDefault("redis.state_prefix", "auth")

	v.SetDefault("kafka.enabled", true)
	v.SetDefault("kafka.tls_enabled", false)
	v.SetDefault("kafka.brokers", []string{"localhost:9092"})
	v.SetDefault("kafka.identity_topic", "t.auth.events")
	v.SetDefault("kafka.notification_topic", "t.auth.notification.events")
	v.SetDefault("kafka.publish_max_attempts", 3)
	v.SetDefault("kafka.publish_backoff", "200ms")
	v.SetDefault("kafka.publish_timeout", "5s")
	v.SetDefault("kafka.sasl_username", "")
	v.SetDefault("kafka.sasl_password", "")

	v.SetDefault("jwt.issuer", "https://auth.ecommerce.local")
	v.SetDefault("jwt.audience", "ecommerce-api")
	v.SetDefault("jwt.algorithm", "RS256")
	v.SetDefault("jwt.access_token_ttl", "15m")
	v.SetDefault("jwt.clock_skew", "30s")
	v.SetDefault("jwt.key_cache_ttl", "5m")
	v.SetDefault("jwt.jwks_cache_control", "public, max-age=600")

	v.SetDefault("refresh.sliding_ttl", "168h")
	v.SetDefault("refresh.absolute_ttl", "720h")
	v.SetDefault("refresh.cookie_name", "auth_refresh")
	v.SetDefault("refresh.cookie_path", "/api/v1/auth/sessions")
	v.SetDefault("refresh.cookie_secure", true)

	v.SetDefault("security.argon2.memory", 65536)
	v.SetDefault("security.argon2.iterations", 3)
	v.SetDefault("security.argon2.parallelism", 4)
	v.SetDefault("security.argon2.salt_length", 16)
	v.SetDefault("security.argon2.key_length", 32)
	v.SetDefault("security.lockout.max_failed_attempts", 5)
	v.SetDefault("security.lockout.lock_duration", "15m")
	v.SetDefault("security.lockout.counter_reset_after", "1h")
	v.SetDefault("security.trusted_proxies", []string{})
	v.SetDefault("security.client_ip_headers", []string{"X-Real-IP"})
	v.SetDefault("security.password_policy.min_length", 12)
	v.SetDefault("security.password_policy.max_length", 128)
	v.SetDefault("security.rate_limit.login_per_ip.limit", 20)
	v.SetDefault("security.rate_limit.login_per_ip.window", "5m")
	v.SetDefault("security.rate_limit.login_per_account.limit", 10)
	v.SetDefault("security.rate_limit.login_per_account.window", "5m")
	v.SetDefault("security.rate_limit.registration_per_ip.limit", 10)
	v.SetDefault("security.rate_limit.registration_per_ip.window", "1h")
	v.SetDefault("security.rate_limit.otp_per_account.limit", 5)
	v.SetDefault("security.rate_limit.otp_per_account.window", "15m")
	v.SetDefault("security.rate_limit.password_reset_per_ip.limit", 5)
	v.SetDefault("security.rate_limit.password_reset_per_ip.window", "1h")
	v.SetDefault("security.rate_limit.refresh_per_ip.limit", 60)
	v.SetDefault("security.rate_limit.refresh_per_ip.window", "5m")
	v.SetDefault("security.rate_limit.internal_per_resource.limit", 120)
	v.SetDefault("security.rate_limit.internal_per_resource.window", "1m")

	v.SetDefault("otp.ttl", "5m")
	v.SetDefault("otp.max_attempts", 5)
	v.SetDefault("otp.resend_limit", 3)
	v.SetDefault("otp.resend_window", "15m")

	v.SetDefault("password_reset.ttl", "15m")
	v.SetDefault("password_reset.url_template", "https://app.ecommerce.local/reset-password#token={token}")

	v.SetDefault("notification.delivery_ttl", "30m")

	v.SetDefault("keys.rsa_bits", 2048)
	v.SetDefault("keys.rotation_interval", "720h")
	v.SetDefault("keys.retirement_delay", "24h")
	v.SetDefault("keys.master_key_version", 1)

	v.SetDefault("workers.lock_ttl", "5m")
	v.SetDefault("workers.refresh_token_retention", "168h")
	v.SetDefault("workers.refresh_token_cleanup.enabled", true)
	v.SetDefault("workers.refresh_token_cleanup.interval", "1h")
	v.SetDefault("workers.refresh_token_cleanup.timeout", "2m")
	v.SetDefault("workers.refresh_token_cleanup.batch", 1000)
	v.SetDefault("workers.key_rotation.enabled", true)
	v.SetDefault("workers.key_rotation.interval", "1h")
	v.SetDefault("workers.key_rotation.timeout", "1m")
	v.SetDefault("workers.key_retirement.enabled", true)
	v.SetDefault("workers.key_retirement.interval", "1h")
	v.SetDefault("workers.key_retirement.timeout", "1m")
	v.SetDefault("workers.auto_unlock.enabled", true)
	v.SetDefault("workers.auto_unlock.interval", "1m")
	v.SetDefault("workers.auto_unlock.timeout", "1m")
	v.SetDefault("workers.auto_unlock.batch", 500)
	v.SetDefault("workers.account_hard_delete.enabled", true)
	v.SetDefault("workers.account_hard_delete.interval", "6h")
	v.SetDefault("workers.account_hard_delete.timeout", "5m")
	v.SetDefault("workers.account_hard_delete.batch", 500)

	v.SetDefault("logging.level", "info")
	v.SetDefault("logging.format", "json")
}
