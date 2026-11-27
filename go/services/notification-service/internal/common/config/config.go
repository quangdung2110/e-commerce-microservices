// Package config loads the Notification Service runtime configuration from a
// YAML file with environment-variable overrides (prefix NOTIFICATION_, so
// NOTIFICATION_DATABASE_HOST overrides database.host). Secrets never live in the
// file: ${DB_PASSWORD}-style references are expanded from the environment at
// load time. Design: docs/notification/13-development.md §3.
package config

import (
	"bytes"
	"fmt"
	"math"
	"os"
	"strings"
	"time"

	"github.com/spf13/viper"

	"github.com/ecommerce/notification-service/internal/common/pii"
)

// orderingLeaseMargin is the safety margin of deliver.ordering_lease over the
// worst-case send path.
const orderingLeaseMargin = 5 * time.Second

// Role selects which goroutine groups a process runs (13-development.md §1).
const (
	RoleAll      = "all"
	RoleAPI      = "api"
	RoleConsumer = "consumer"
	RoleDeliver  = "deliver"
	RoleTrack    = "track"
	RoleJobs     = "jobs"
)

type Config struct {
	App        AppConfig        `mapstructure:"app"`
	Server     ServerConfig     `mapstructure:"server"`
	Internal   InternalConfig   `mapstructure:"internal"`
	Database   DatabaseConfig   `mapstructure:"database"`
	Redis      RedisConfig      `mapstructure:"redis"`
	Kafka      KafkaConfig      `mapstructure:"kafka"`
	Email      EmailConfig      `mapstructure:"email"`
	Track      TrackConfig      `mapstructure:"track"`
	Auth       AuthConfig       `mapstructure:"auth"`
	Lanes      LanesConfig      `mapstructure:"lanes"`
	Deliver    DeliverConfig    `mapstructure:"deliver"`
	Frontend   FrontendConfig   `mapstructure:"frontend"`
	Bulk       BulkConfig       `mapstructure:"bulk"`
	Outbox     OutboxConfig     `mapstructure:"outbox"`
	Jobs       JobsConfig       `mapstructure:"jobs"`
	Contact    ContactConfig    `mapstructure:"contact"`
	Logging    LoggingConfig    `mapstructure:"logging"`
	Telemetry  TelemetryConfig  `mapstructure:"telemetry"`
	Templates  TemplatesConfig  `mapstructure:"templates"`
	Privacy    PrivacyConfig    `mapstructure:"privacy"`
	Extensions ExtensionsConfig `mapstructure:"extensions"`
}

type AppConfig struct {
	Name string `mapstructure:"name"`
	// Env is development | staging | production. development relaxes the
	// production-only checks and lets the privacy HMAC key fall back to a fixed
	// dev key.
	Env string `mapstructure:"env"`
	// Role is all | api | consumer | deliver | track | jobs.
	Role string `mapstructure:"role"`
	// Lane narrows consumer/deliver roles to one lane; empty means every lane.
	Lane string `mapstructure:"lane"`
	// InstanceID identifies this process in locked_by; defaults to hostname.
	InstanceID string `mapstructure:"instance_id"`
}

func (a AppConfig) IsDevelopment() bool { return a.Env == "development" || a.Env == "dev" }
func (a AppConfig) IsProduction() bool  { return a.Env == "production" }

func (a AppConfig) RunsRole(role string) bool {
	return a.Role == RoleAll || a.Role == role
}

// RunsLane reports whether a lane-scoped component should run in this process.
func (a AppConfig) RunsLane(lane string) bool {
	return a.Lane == "" || a.Lane == "-" || a.Lane == lane
}

// ServerConfig is the public listener (inbox API behind the gateway).
type ServerConfig struct {
	Host            string        `mapstructure:"host"`
	Port            int           `mapstructure:"port"`
	ReadTimeout     time.Duration `mapstructure:"read_timeout"`
	WriteTimeout    time.Duration `mapstructure:"write_timeout"`
	IdleTimeout     time.Duration `mapstructure:"idle_timeout"`
	ShutdownTimeout time.Duration `mapstructure:"shutdown_timeout"`
	TrustedProxies  []string      `mapstructure:"trusted_proxies"`
}

// InternalConfig is the cluster-internal listener (status, ops, metrics,
// health). Never published through the gateway.
type InternalConfig struct {
	Host string `mapstructure:"host"`
	Port int    `mapstructure:"port"`
	// StatusPrincipals are the X-Service-Identity values allowed on the status
	// endpoints. The mesh authenticates the caller; this list is defence in depth
	// (decisions.md D-21).
	StatusPrincipals []string `mapstructure:"status_principals"`
	// Principals allowed on the ops endpoints.
	OpsPrincipals []string `mapstructure:"ops_principals"`
}

type DatabaseConfig struct {
	Host            string        `mapstructure:"host"`
	Port            int           `mapstructure:"port"`
	Name            string        `mapstructure:"name"`
	Username        string        `mapstructure:"username"`
	Password        string        `mapstructure:"password"`
	SSLMode         string        `mapstructure:"sslmode"`
	ConnMaxLifetime time.Duration `mapstructure:"conn_max_lifetime"`
	ConnectTimeout  time.Duration `mapstructure:"connect_timeout"`
	// Pools is the bulkhead: one pgx pool per consumer, deliver lane, track and
	// api so bulk delivery can never starve the critical consumer
	// (06-backpressure.md §3).
	Pools DBPoolsConfig `mapstructure:"pools"`
}

type DBPoolsConfig struct {
	Consumer             int32 `mapstructure:"consumer"`
	DeliverCritical      int32 `mapstructure:"deliver_critical"`
	DeliverTransactional int32 `mapstructure:"deliver_transactional"`
	DeliverBulk          int32 `mapstructure:"deliver_bulk"`
	Track                int32 `mapstructure:"track"`
	API                  int32 `mapstructure:"api"`
	Jobs                 int32 `mapstructure:"jobs"`
}

// For returns the deliver pool size of a lane.
func (p DBPoolsConfig) For(lane string) int32 {
	switch lane {
	case "critical":
		return p.DeliverCritical
	case "bulk":
		return p.DeliverBulk
	default:
		return p.DeliverTransactional
	}
}

func (d DatabaseConfig) DSN() string {
	return fmt.Sprintf("postgres://%s:%s@%s:%d/%s?sslmode=%s",
		d.Username, d.Password, d.Host, d.Port, d.Name, d.SSLMode)
}

type RedisConfig struct {
	Username     string        `mapstructure:"username"`
	TLSEnabled   bool          `mapstructure:"tls_enabled"`
	Host         string        `mapstructure:"host"`
	Port         int           `mapstructure:"port"`
	Password     string        `mapstructure:"password"`
	DB           int           `mapstructure:"db"`
	PoolSize     int           `mapstructure:"pool_size"`
	MinIdleConns int           `mapstructure:"min_idle_conns"`
	DialTimeout  time.Duration `mapstructure:"dial_timeout"`
	ReadTimeout  time.Duration `mapstructure:"read_timeout"`
	WriteTimeout time.Duration `mapstructure:"write_timeout"`
	KeyPrefix    string        `mapstructure:"key_prefix"`
}

func (r RedisConfig) Addr() string { return fmt.Sprintf("%s:%d", r.Host, r.Port) }

// KafkaConfig lists every topic Notification reads or writes and the consumer
// group per source (01-lanes.md §5, D-19).
type KafkaConfig struct {
	TLSEnabled bool     `mapstructure:"tls_enabled"`
	Brokers    []string `mapstructure:"brokers"`
	ClientID   string   `mapstructure:"client_id"`
	Version    string   `mapstructure:"version"`

	// SASL/SCRAM-SHA-512 principal of the service (mesh ADR-003). An empty
	// username keeps PLAINTEXT, for tests and a standalone broker.
	SASLUsername string `mapstructure:"sasl_username"`
	SASLPassword string `mapstructure:"sasl_password"`

	SessionTimeout    time.Duration `mapstructure:"session_timeout"`
	HeartbeatInterval time.Duration `mapstructure:"heartbeat_interval"`
	MaxProcessingTime time.Duration `mapstructure:"max_processing_time"`

	// Per-message retry inside the partition goroutine before dead-lettering
	// (only for infrastructure errors; contract errors go straight to a FAILED
	// request row).
	ConsumeMaxAttempts int           `mapstructure:"consume_max_attempts"`
	ConsumeMinBackoff  time.Duration `mapstructure:"consume_min_backoff"`
	ConsumeMaxBackoff  time.Duration `mapstructure:"consume_max_backoff"`

	Sources  KafkaSourcesConfig `mapstructure:"sources"`
	Outcomes KafkaTopicConfig   `mapstructure:"outcomes"`
}

// KafkaSourceConfig is one consumed topic with its own consumer group.
type KafkaSourceConfig struct {
	Enabled bool   `mapstructure:"enabled"`
	Topic   string `mapstructure:"topic"`
	Group   string `mapstructure:"group"`
	DLQ     string `mapstructure:"dlq"`
}

type KafkaSourcesConfig struct {
	Critical      KafkaSourceConfig `mapstructure:"critical"`
	Transactional KafkaSourceConfig `mapstructure:"transactional"`
	Bulk          KafkaSourceConfig `mapstructure:"bulk"`
	Auth          KafkaSourceConfig `mapstructure:"auth"`
	Review        KafkaSourceConfig `mapstructure:"review"`
	Cart          KafkaSourceConfig `mapstructure:"cart"`
	Preferences   KafkaSourceConfig `mapstructure:"preferences"`
	UserLifecycle KafkaSourceConfig `mapstructure:"user_lifecycle"`
}

type KafkaTopicConfig struct {
	Topic string `mapstructure:"topic"`
}

// EmailConfig selects the email adapter (D-09): smtp for Mailpit in dev/CI,
// ses for production.
type EmailConfig struct {
	Provider    string `mapstructure:"provider"` // smtp | ses
	FromAddress string `mapstructure:"from_address"`
	FromName    string `mapstructure:"from_name"`
	// UnsubscribeURL is the frontend preference page used for List-Unsubscribe
	// on bulk mail (12-render.md §5).
	UnsubscribeURL string     `mapstructure:"unsubscribe_url"`
	SMTP           SMTPConfig `mapstructure:"smtp"`
	SES            SESConfig  `mapstructure:"ses"`
	// Timeouts apply to any provider (06-backpressure.md §6.2).
	ConnectTimeout time.Duration `mapstructure:"connect_timeout"`
	SendTimeout    time.Duration `mapstructure:"send_timeout"`
}

type SMTPConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Username string `mapstructure:"username"`
	Password string `mapstructure:"password"`
	// MaxConns bounds the connection bulkhead per lane.
	MaxConnsPerLane int `mapstructure:"max_conns_per_lane"`
}

type SESConfig struct {
	Region                                string `mapstructure:"region"`
	Endpoint                              string `mapstructure:"endpoint"`
	ConfigurationSetCriticalTransactional string `mapstructure:"configuration_set_critical_transactional"`
	ConfigurationSetBulk                  string `mapstructure:"configuration_set_bulk"`
	MaxConnsPerLane                       int    `mapstructure:"max_conns_per_lane"`
	// Max24HourSend / MaxSendRate mirror the account quota for the daily
	// counter and the bulk stop threshold (06-backpressure.md §4.1).
	Max24HourSend      int64   `mapstructure:"max_24_hour_send"`
	BulkStopDailyRatio float64 `mapstructure:"bulk_stop_daily_ratio"`
}

// TrackConfig configures the SES → SNS → SQS path (03-tracking.md).
type TrackConfig struct {
	Enabled         bool          `mapstructure:"enabled"`
	QueueURL        string        `mapstructure:"queue_url"`
	AWSEndpoint     string        `mapstructure:"aws_endpoint"`
	AWSRegion       string        `mapstructure:"aws_region"`
	WaitTime        time.Duration `mapstructure:"wait_time"`
	BatchSize       int32         `mapstructure:"batch_size"`
	VerifySignature bool          `mapstructure:"verify_signature"`
	// AllowedCertHosts lists hosts a SigningCertURL may point at. Production:
	// sns.<region>.amazonaws.com; dev adds the LocalStack host.
	AllowedCertHosts      []string      `mapstructure:"allowed_cert_hosts"`
	MaxClockSkew          time.Duration `mapstructure:"max_clock_skew"`
	WorkerBatch           int           `mapstructure:"worker_batch"`
	WorkerPoll            time.Duration `mapstructure:"worker_poll"`
	UnmatchedRetry        time.Duration `mapstructure:"unmatched_retry"`
	UnmatchedAlertAfter   int           `mapstructure:"unmatched_alert_after"`
	UnmatchedGiveUp       time.Duration `mapstructure:"unmatched_give_up"`
	SoftBounceSuppression time.Duration `mapstructure:"soft_bounce_suppression"`
	// CorrelateProvider is the provider whose deliveries SES events are matched
	// against: ses in production; mailpit in dev, where sample SES events are
	// pushed for mail that Mailpit delivered (13-development.md §2.2).
	CorrelateProvider string `mapstructure:"correlate_provider"`
}

// AuthConfig is the client for the Auth internal API (02-pipeline.md §2.3).
type AuthConfig struct {
	BaseURL         string        `mapstructure:"base_url"`
	Timeout         time.Duration `mapstructure:"timeout"`
	ServiceIdentity string        `mapstructure:"service_identity"`
	// SigningKey is shared with Auth (AUTH_INTERNAL_SIGNING_KEY): every call
	// carries X-Service-Signature, the proof of caller Auth checks while there
	// is no mesh (mesh ADR-004). Required outside development.
	SigningKey string `mapstructure:"signing_key"`
	// Client-side limits per lane so a bulk drain never floods Auth
	// (06-backpressure.md §3). 0 = unlimited.
	RatePerSecond LaneValues[float64] `mapstructure:"rate_per_second"`
	MaxConns      LaneValues[int]     `mapstructure:"max_conns"`
	Breaker       BreakerConfig       `mapstructure:"breaker"`
}

// LaneValues holds one value per lane.
type LaneValues[T any] struct {
	Critical      T `mapstructure:"critical"`
	Transactional T `mapstructure:"transactional"`
	Bulk          T `mapstructure:"bulk"`
}

func (l LaneValues[T]) For(lane string) T {
	switch lane {
	case "critical":
		return l.Critical
	case "bulk":
		return l.Bulk
	default:
		return l.Transactional
	}
}

type BreakerConfig struct {
	WindowSize        int           `mapstructure:"window_size"`
	FailureRatio      float64       `mapstructure:"failure_ratio"`
	OpenDuration      time.Duration `mapstructure:"open_duration"`
	HalfOpenSuccesses int           `mapstructure:"half_open_successes"`
}

// LanesConfig sizes the per-lane bulkheads and quota (01-lanes.md §5).
type LanesConfig struct {
	Critical      LaneConfig `mapstructure:"critical"`
	Transactional LaneConfig `mapstructure:"transactional"`
	Bulk          LaneConfig `mapstructure:"bulk"`
}

func (l LanesConfig) For(lane string) LaneConfig {
	switch lane {
	case "critical":
		return l.Critical
	case "bulk":
		return l.Bulk
	default:
		return l.Transactional
	}
}

type LaneConfig struct {
	EmailWorkers int `mapstructure:"email_workers"`
	InAppWorkers int `mapstructure:"in_app_workers"`
	BatchSize    int `mapstructure:"batch_size"`
	// EmailQuotaPerSecond is this lane's reserved share of the provider rate.
	EmailQuotaPerSecond float64 `mapstructure:"email_quota_per_second"`
	// BreakerOpenRetry is how far a row is pushed when the provider breaker is
	// open (06-backpressure.md §5).
	BreakerOpenRetry time.Duration `mapstructure:"breaker_open_retry"`
	// MaxAttempts before a retryable error becomes FAILED (provider_error).
	MaxAttempts int `mapstructure:"max_attempts"`
}

// DeliverConfig holds lane-independent delivery mechanics (02-pipeline.md §5).
type DeliverConfig struct {
	Lease             time.Duration `mapstructure:"lease"`
	HeartbeatInterval time.Duration `mapstructure:"heartbeat_interval"`
	IdleMinSleep      time.Duration `mapstructure:"idle_min_sleep"`
	IdleMaxSleep      time.Duration `mapstructure:"idle_max_sleep"`
	ReaperInterval    time.Duration `mapstructure:"reaper_interval"`
	RetryBase         time.Duration `mapstructure:"retry_base"`
	RetryMax          time.Duration `mapstructure:"retry_max"`
	RetryJitter       time.Duration `mapstructure:"retry_jitter"`
	RateLimitWait     time.Duration `mapstructure:"rate_limit_wait"`
	RetryBudgetRatio  float64       `mapstructure:"retry_budget_ratio"`
	RetryBudgetDefer  time.Duration `mapstructure:"retry_budget_defer"`
	// LimiterFallbackReplicas is the divisor used when Redis is down: each
	// replica limits itself to quota/replicas (fail-closed, 06 §4.2).
	LimiterFallbackReplicas int `mapstructure:"limiter_fallback_replicas"`
	// ControlPollInterval is how often workers re-read lane pause state.
	ControlPollInterval time.Duration `mapstructure:"control_poll_interval"`
	Breaker             BreakerConfig `mapstructure:"breaker"`
	// BreakerAutoPauseAfter is how long the provider breaker may stay unhealthy
	// before the watchdog pauses bulk email (06-backpressure.md §5); 0 disables.
	BreakerAutoPauseAfter time.Duration `mapstructure:"breaker_auto_pause_after"`
	// HealthProbeInterval is how often the watchdog looks at the breaker and, when
	// it is half-open and idle, asks the provider directly; 0 disables the watchdog.
	HealthProbeInterval time.Duration `mapstructure:"health_probe_interval"`
	// HealthProbeIdle is how long a half-open breaker must have gone without any
	// probe before the health check is used instead of waiting for a real row.
	HealthProbeIdle time.Duration `mapstructure:"health_probe_idle"`
	// UseListenNotify wakes critical workers through Postgres NOTIFY.
	UseListenNotify bool `mapstructure:"use_listen_notify"`
	// OrderingLease is how long a worker owns the per-Order-subject
	// serialisation lease (05-ordering.md §4 cách 3); it must outlive the
	// whole resolve → render → rate-limit → provider call path.
	OrderingLease time.Duration `mapstructure:"ordering_lease"`
	// OrderingHeartbeatInterval extends OrderingLease while the row is in flight.
	OrderingHeartbeatInterval time.Duration `mapstructure:"ordering_heartbeat_interval"`
	// OrderingBusyRetry is how far a row is pushed when another delivery of the
	// same Order holds the lease (released, attempt not counted).
	OrderingBusyRetry time.Duration `mapstructure:"ordering_busy_retry"`
}

type FrontendConfig struct {
	BaseURL string `mapstructure:"base_url"`
	// CartPath is appended to BaseURL for CART_ABANDONED (the one URL
	// Notification assembles itself, 07-templates.md §2).
	CartPath string `mapstructure:"cart_path"`
}

type BulkConfig struct {
	QuietFrom          string `mapstructure:"quiet_from"` // "22:00"
	QuietTo            string `mapstructure:"quiet_to"`   // "08:00"
	DefaultTimezone    string `mapstructure:"default_timezone"`
	FrequencyCapPerDay int    `mapstructure:"frequency_cap_per_day"`
}

type OutboxConfig struct {
	// Publisher is debezium (CDC, rows only purged) or poller (this service
	// publishes and marks published_at).
	Publisher    string        `mapstructure:"publisher"`
	PollInterval time.Duration `mapstructure:"poll_interval"`
	Batch        int           `mapstructure:"batch"`
}

type JobsConfig struct {
	PartitionInterval    time.Duration   `mapstructure:"partition_interval"`
	PartitionAheadMonths int             `mapstructure:"partition_ahead_months"`
	PurgeInterval        time.Duration   `mapstructure:"purge_interval"`
	PurgeBatch           int             `mapstructure:"purge_batch"`
	RetentionDays        RetentionConfig `mapstructure:"retention_days"`
	MetricsInterval      time.Duration   `mapstructure:"metrics_interval"`
}

type RetentionConfig struct {
	Request            int `mapstructure:"request"`
	Delivery           int `mapstructure:"delivery"`
	ProviderEvent      int `mapstructure:"provider_event"`
	Inbox              int `mapstructure:"inbox"`
	IngestDedup        int `mapstructure:"ingest_dedup"`
	DeliveryDedup      int `mapstructure:"delivery_dedup"`
	ProviderEventDedup int `mapstructure:"provider_event_dedup"`
	Outbox             int `mapstructure:"outbox"`
	OpsIdempotency     int `mapstructure:"ops_idempotency"`
}

type ContactConfig struct {
	CacheTTL time.Duration `mapstructure:"cache_ttl"`
}

type LoggingConfig struct {
	Level  string `mapstructure:"level"`
	Format string `mapstructure:"format"`
}

type TelemetryConfig struct {
	MetricsPath string `mapstructure:"metrics_path"`
}

type TemplatesConfig struct {
	DefaultLocale string `mapstructure:"default_locale"`
}

// PrivacyConfig holds the versioned HMAC keys that pseudonymise Order subjects
// and deleted user ids in long-lived tables (09-data-model.md §3, §10;
// 11-privacy.md §2). The key is a secret: set it through
// NOTIFICATION_PRIVACY_HMAC_KEY, never in the YAML file.
type PrivacyConfig struct {
	HMACKey        string `mapstructure:"hmac_key"`
	HMACKeyVersion int    `mapstructure:"hmac_key_version"`
	// PreviousHMACKeys keeps older versions readable during a rotation
	// ("version:key,version:key"); new rows always use the active key.
	PreviousHMACKeys string `mapstructure:"previous_hmac_keys"`
}

// devHMACKey is used only when app.env=development and no key is configured,
// mirroring the development-only X-Service-Identity leniency.
const devHMACKey = "notification-development-only-hmac-key-not-a-secret"

// Keyring builds the privacy keyring. Validate has already checked the input.
func (c *Config) Keyring() (*pii.Keyring, error) {
	key := c.Privacy.HMACKey
	if key == "" && c.App.IsDevelopment() {
		key = devHMACKey
	}
	prev, err := pii.ParsePreviousKeys(c.Privacy.PreviousHMACKeys)
	if err != nil {
		return nil, fmt.Errorf("config: privacy.previous_hmac_keys: %w", err)
	}
	return pii.NewKeyring(int16(c.Privacy.HMACKeyVersion), []byte(key), prev)
}

// ExtensionsConfig holds rarely-changed knobs.
type ExtensionsConfig struct {
	// ReplayModeDefaultTTL bounds an ops replay-mode switch.
	ReplayModeDefaultTTL time.Duration `mapstructure:"replay_mode_default_ttl"`
	IdempotencyTTL       time.Duration `mapstructure:"idempotency_ttl"`
}

// Load reads the YAML file (if any) and applies environment overrides.
func Load(path string) (*Config, error) {
	v := viper.New()
	setDefaults(v)

	v.SetEnvPrefix("NOTIFICATION")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	if path == "" {
		path = os.Getenv("NOTIFICATION_CONFIG_FILE")
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
	if cfg.App.InstanceID == "" {
		host, _ := os.Hostname()
		if host == "" {
			host = "local"
		}
		cfg.App.InstanceID = host
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

// Validate rejects configurations that would be unsafe to run.
func (c *Config) Validate() error {
	switch c.App.Role {
	case RoleAll, RoleAPI, RoleConsumer, RoleDeliver, RoleTrack, RoleJobs:
	default:
		return fmt.Errorf("config: app.role %q is not one of all|api|consumer|deliver|track|jobs", c.App.Role)
	}
	switch c.App.Lane {
	case "", "-", "critical", "transactional", "bulk":
	default:
		return fmt.Errorf("config: app.lane %q is not one of critical|transactional|bulk", c.App.Lane)
	}
	switch c.Email.Provider {
	case "smtp", "ses":
	default:
		return fmt.Errorf("config: email.provider %q is not smtp|ses", c.Email.Provider)
	}
	switch c.Outbox.Publisher {
	case "debezium", "poller":
	default:
		return fmt.Errorf("config: outbox.publisher %q is not debezium|poller", c.Outbox.Publisher)
	}
	if c.Kafka.SASLUsername != "" && c.Kafka.SASLPassword == "" {
		return fmt.Errorf("config: kafka.sasl_password is required with kafka.sasl_username")
	}
	if c.IsProduction() {
		if c.Email.Provider != "ses" {
			return fmt.Errorf("config: production requires email.provider=ses")
		}
		if c.Track.Enabled && !c.Track.VerifySignature {
			return fmt.Errorf("config: production requires track.verify_signature=true")
		}
	}
	if !hasName(c.Internal.StatusPrincipals) {
		return fmt.Errorf("config: internal.status_principals must name at least one service")
	}
	if !hasName(c.Internal.OpsPrincipals) {
		return fmt.Errorf("config: internal.ops_principals must name at least one service")
	}
	if c.Deliver.Lease <= c.Email.SendTimeout+c.Auth.Timeout {
		return fmt.Errorf("config: deliver.lease must exceed email.send_timeout + auth.timeout")
	}
	// The ordering lease spans resolve → render → rate-limit wait → provider
	// call; a lease that can expire mid-call lets two deliveries of one Order
	// reach the provider at once (05-ordering.md §4).
	minOrdering := c.Email.ConnectTimeout + c.Email.SendTimeout + c.Auth.Timeout + c.Deliver.RateLimitWait + orderingLeaseMargin
	if c.Deliver.OrderingLease <= minOrdering {
		return fmt.Errorf("config: deliver.ordering_lease must exceed email.connect_timeout + email.send_timeout + auth.timeout + deliver.rate_limit_wait + %s (= %s)", orderingLeaseMargin, minOrdering)
	}
	if c.Deliver.OrderingHeartbeatInterval <= 0 || c.Deliver.OrderingHeartbeatInterval*3 > c.Deliver.OrderingLease {
		return fmt.Errorf("config: deliver.ordering_heartbeat_interval must be > 0 and <= ordering_lease/3")
	}
	if c.Deliver.OrderingBusyRetry <= 0 {
		return fmt.Errorf("config: deliver.ordering_busy_retry must be > 0")
	}
	if c.Deliver.BreakerAutoPauseAfter < 0 || c.Deliver.HealthProbeInterval < 0 || c.Deliver.HealthProbeIdle < 0 {
		return fmt.Errorf("config: deliver.breaker_auto_pause_after, health_probe_interval and health_probe_idle must be >= 0")
	}
	if c.Privacy.HMACKeyVersion < 1 || c.Privacy.HMACKeyVersion > math.MaxInt16 {
		return fmt.Errorf("config: privacy.hmac_key_version must be in 1..%d", math.MaxInt16)
	}
	if !c.App.IsDevelopment() && len(c.Privacy.HMACKey) < pii.MinHMACKeyBytes {
		return fmt.Errorf("config: privacy.hmac_key (NOTIFICATION_PRIVACY_HMAC_KEY) must be at least %d bytes outside development", pii.MinHMACKeyBytes)
	}
	// Security review 2026-10-22, H1: outside development a committed or
	// placeholder secret stops the service like a missing one does.
	if !c.App.IsDevelopment() {
		if p := secretProblem("privacy.hmac_key (NOTIFICATION_PRIVACY_HMAC_KEY)", c.Privacy.HMACKey, pii.MinHMACKeyBytes); p != "" {
			return fmt.Errorf("config: %s", p)
		}
		if p := secretProblem("auth.signing_key (NOTIFICATION_AUTH_SIGNING_KEY)", c.Auth.SigningKey, 32); p != "" {
			return fmt.Errorf("config: %s", p)
		}
	}
	if _, err := c.Keyring(); err != nil {
		return err
	}
	return nil
}

func (c *Config) IsProduction() bool { return c.App.IsProduction() }

// hasName reports whether the list holds at least one non-blank entry.
func hasName(list []string) bool {
	for _, s := range list {
		if strings.TrimSpace(s) != "" {
			return true
		}
	}
	return false
}

func setDefaults(v *viper.Viper) {
	v.SetDefault("app.name", "notification-service")
	v.SetDefault("app.env", "development")
	v.SetDefault("app.role", RoleAll)
	v.SetDefault("app.lane", "")

	v.SetDefault("server.host", "0.0.0.0")
	v.SetDefault("server.port", 8080)
	v.SetDefault("server.read_timeout", "15s")
	v.SetDefault("server.write_timeout", "15s")
	v.SetDefault("server.idle_timeout", "120s")
	v.SetDefault("server.shutdown_timeout", "20s")

	v.SetDefault("internal.host", "0.0.0.0")
	v.SetDefault("internal.port", 8081)
	v.SetDefault("internal.status_principals", []string{"order-service", "finance-ops"})
	v.SetDefault("internal.ops_principals", []string{"notification-ops"})

	v.SetDefault("database.host", "localhost")
	v.SetDefault("database.port", 5432)
	v.SetDefault("database.name", "notification_db")
	v.SetDefault("database.username", "postgres")
	v.SetDefault("database.sslmode", "disable")
	v.SetDefault("database.conn_max_lifetime", "30m")
	v.SetDefault("database.connect_timeout", "5s")
	v.SetDefault("database.pools.consumer", 10)
	v.SetDefault("database.pools.deliver_critical", 5)
	v.SetDefault("database.pools.deliver_transactional", 10)
	v.SetDefault("database.pools.deliver_bulk", 15)
	v.SetDefault("database.pools.track", 5)
	v.SetDefault("database.pools.api", 10)
	v.SetDefault("database.pools.jobs", 3)

	v.SetDefault("redis.username", "")
	v.SetDefault("redis.tls_enabled", false)
	v.SetDefault("redis.host", "localhost")
	v.SetDefault("redis.port", 6379)
	v.SetDefault("redis.db", 0)
	v.SetDefault("redis.pool_size", 20)
	v.SetDefault("redis.min_idle_conns", 2)
	v.SetDefault("redis.dial_timeout", "1s")
	v.SetDefault("redis.read_timeout", "1s")
	v.SetDefault("redis.write_timeout", "1s")
	v.SetDefault("redis.key_prefix", "notification")

	v.SetDefault("kafka.tls_enabled", false)
	v.SetDefault("kafka.brokers", []string{"localhost:9092"})
	v.SetDefault("kafka.client_id", "notification-service")
	v.SetDefault("kafka.version", "3.5.0")
	v.SetDefault("kafka.sasl_username", "")
	v.SetDefault("kafka.sasl_password", "")
	v.SetDefault("kafka.session_timeout", "30s")
	v.SetDefault("kafka.heartbeat_interval", "3s")
	v.SetDefault("kafka.max_processing_time", "60s")
	v.SetDefault("kafka.consume_max_attempts", 5)
	v.SetDefault("kafka.consume_min_backoff", "200ms")
	v.SetDefault("kafka.consume_max_backoff", "5s")
	v.SetDefault("kafka.sources.critical", map[string]any{"enabled": true, "topic": "t.notification.critical.v1", "group": "notification-critical", "dlq": "t.notification.critical.v1.dlq"})
	v.SetDefault("kafka.sources.transactional", map[string]any{"enabled": true, "topic": "t.notification.transactional.v1", "group": "notification-transactional", "dlq": "t.notification.transactional.v1.dlq"})
	v.SetDefault("kafka.sources.bulk", map[string]any{"enabled": true, "topic": "t.notification.bulk.v1", "group": "notification-bulk", "dlq": "t.notification.bulk.v1.dlq"})
	v.SetDefault("kafka.sources.auth", map[string]any{"enabled": true, "topic": "t.auth.notification.events", "group": "notification-auth-critical", "dlq": "t.auth.notification.events.dlq"})
	v.SetDefault("kafka.sources.review", map[string]any{"enabled": true, "topic": "t.review.events", "group": "notification-review-transactional", "dlq": "t.review.events.dlq"})
	v.SetDefault("kafka.sources.cart", map[string]any{"enabled": true, "topic": "t.cart.events", "group": "notification-cart-bulk", "dlq": "t.cart.events.dlq"})
	v.SetDefault("kafka.sources.preferences", map[string]any{"enabled": true, "topic": "t.user.events", "group": "notification-preferences", "dlq": "t.user.events.dlq"})
	v.SetDefault("kafka.sources.user_lifecycle", map[string]any{"enabled": true, "topic": "t.auth.events", "group": "notification-user-lifecycle", "dlq": "t.auth.events.dlq"})
	v.SetDefault("kafka.outcomes.topic", "t.notification.outcomes.v1")

	v.SetDefault("email.provider", "smtp")
	v.SetDefault("email.from_address", "no-reply@ecommerce.local")
	v.SetDefault("email.from_name", "E-Commerce")
	v.SetDefault("email.unsubscribe_url", "http://localhost:3000/profile/preferences")
	v.SetDefault("email.connect_timeout", "2s")
	v.SetDefault("email.send_timeout", "10s")
	v.SetDefault("email.smtp.host", "localhost")
	v.SetDefault("email.smtp.port", 1025)
	v.SetDefault("email.smtp.max_conns_per_lane", 5)
	v.SetDefault("email.ses.region", "ap-southeast-1")
	v.SetDefault("email.ses.configuration_set_critical_transactional", "notification-critical-transactional")
	v.SetDefault("email.ses.configuration_set_bulk", "notification-bulk")
	v.SetDefault("email.ses.max_conns_per_lane", 10)
	v.SetDefault("email.ses.max_24_hour_send", 50000)
	v.SetDefault("email.ses.bulk_stop_daily_ratio", 0.8)

	v.SetDefault("track.enabled", true)
	v.SetDefault("track.queue_url", "http://localhost:4566/000000000000/notification-ses-events")
	v.SetDefault("track.aws_endpoint", "http://localhost:4566")
	v.SetDefault("track.aws_region", "ap-southeast-1")
	v.SetDefault("track.wait_time", "20s")
	v.SetDefault("track.batch_size", 10)
	v.SetDefault("track.verify_signature", true)
	v.SetDefault("track.allowed_cert_hosts", []string{"sns.ap-southeast-1.amazonaws.com", "localhost:4566", "localstack:4566"})
	v.SetDefault("track.max_clock_skew", "10m")
	v.SetDefault("track.worker_batch", 50)
	v.SetDefault("track.worker_poll", "1s")
	v.SetDefault("track.unmatched_retry", "30s")
	v.SetDefault("track.unmatched_alert_after", 3)
	v.SetDefault("track.unmatched_give_up", "24h")
	v.SetDefault("track.soft_bounce_suppression", "48h")
	v.SetDefault("track.correlate_provider", "ses")

	v.SetDefault("auth.base_url", "http://localhost:8081")
	v.SetDefault("auth.timeout", "2s")
	v.SetDefault("auth.service_identity", "notification-service")
	v.SetDefault("auth.signing_key", "")
	v.SetDefault("auth.rate_per_second.critical", 0)
	v.SetDefault("auth.rate_per_second.transactional", 30)
	v.SetDefault("auth.rate_per_second.bulk", 20)
	v.SetDefault("auth.max_conns.critical", 10)
	v.SetDefault("auth.max_conns.transactional", 10)
	v.SetDefault("auth.max_conns.bulk", 5)
	v.SetDefault("auth.breaker.window_size", 20)
	v.SetDefault("auth.breaker.failure_ratio", 0.5)
	v.SetDefault("auth.breaker.open_duration", "30s")
	v.SetDefault("auth.breaker.half_open_successes", 3)

	// Dev sizes are the 01-lanes.md §5 table divided by ~10.
	v.SetDefault("lanes.critical.email_workers", 2)
	v.SetDefault("lanes.critical.in_app_workers", 1)
	v.SetDefault("lanes.critical.batch_size", 5)
	v.SetDefault("lanes.critical.email_quota_per_second", 10)
	v.SetDefault("lanes.critical.breaker_open_retry", "30s")
	v.SetDefault("lanes.critical.max_attempts", 5)
	v.SetDefault("lanes.transactional.email_workers", 2)
	v.SetDefault("lanes.transactional.in_app_workers", 1)
	v.SetDefault("lanes.transactional.batch_size", 10)
	v.SetDefault("lanes.transactional.email_quota_per_second", 20)
	v.SetDefault("lanes.transactional.breaker_open_retry", "5m")
	v.SetDefault("lanes.transactional.max_attempts", 8)
	v.SetDefault("lanes.bulk.email_workers", 3)
	v.SetDefault("lanes.bulk.in_app_workers", 1)
	v.SetDefault("lanes.bulk.batch_size", 20)
	v.SetDefault("lanes.bulk.email_quota_per_second", 15)
	v.SetDefault("lanes.bulk.breaker_open_retry", "30m")
	v.SetDefault("lanes.bulk.max_attempts", 8)

	v.SetDefault("deliver.lease", "60s")
	v.SetDefault("deliver.heartbeat_interval", "20s")
	v.SetDefault("deliver.idle_min_sleep", "50ms")
	v.SetDefault("deliver.idle_max_sleep", "1s")
	v.SetDefault("deliver.reaper_interval", "30s")
	v.SetDefault("deliver.retry_base", "30s")
	v.SetDefault("deliver.retry_max", "1h")
	v.SetDefault("deliver.retry_jitter", "30s")
	v.SetDefault("deliver.rate_limit_wait", "5s")
	v.SetDefault("deliver.retry_budget_ratio", 0.2)
	v.SetDefault("deliver.retry_budget_defer", "15m")
	v.SetDefault("deliver.limiter_fallback_replicas", 4)
	v.SetDefault("deliver.control_poll_interval", "5s")
	v.SetDefault("deliver.breaker.window_size", 20)
	v.SetDefault("deliver.breaker.failure_ratio", 0.5)
	v.SetDefault("deliver.breaker.open_duration", "30s")
	v.SetDefault("deliver.breaker.half_open_successes", 3)
	v.SetDefault("deliver.breaker_auto_pause_after", "1h")
	v.SetDefault("deliver.health_probe_interval", "10s")
	v.SetDefault("deliver.health_probe_idle", "15s")
	v.SetDefault("deliver.use_listen_notify", true)
	v.SetDefault("deliver.ordering_lease", "45s")
	v.SetDefault("deliver.ordering_heartbeat_interval", "10s")
	v.SetDefault("deliver.ordering_busy_retry", "2s")

	v.SetDefault("frontend.base_url", "http://localhost:3000")
	v.SetDefault("frontend.cart_path", "/cart")

	v.SetDefault("bulk.quiet_from", "22:00")
	v.SetDefault("bulk.quiet_to", "08:00")
	v.SetDefault("bulk.default_timezone", "Asia/Ho_Chi_Minh")
	v.SetDefault("bulk.frequency_cap_per_day", 2)

	v.SetDefault("outbox.publisher", "debezium")
	v.SetDefault("outbox.poll_interval", "500ms")
	v.SetDefault("outbox.batch", 100)

	v.SetDefault("jobs.partition_interval", "1h")
	v.SetDefault("jobs.partition_ahead_months", 2)
	v.SetDefault("jobs.purge_interval", "1h")
	v.SetDefault("jobs.purge_batch", 10000)
	v.SetDefault("jobs.retention_days.request", 90)
	v.SetDefault("jobs.retention_days.delivery", 90)
	v.SetDefault("jobs.retention_days.provider_event", 30)
	v.SetDefault("jobs.retention_days.inbox", 90)
	v.SetDefault("jobs.retention_days.ingest_dedup", 30)
	v.SetDefault("jobs.retention_days.delivery_dedup", 30)
	v.SetDefault("jobs.retention_days.provider_event_dedup", 14)
	v.SetDefault("jobs.retention_days.outbox", 7)
	v.SetDefault("jobs.retention_days.ops_idempotency", 1)
	v.SetDefault("jobs.metrics_interval", "15s")

	v.SetDefault("contact.cache_ttl", "15m")

	v.SetDefault("logging.level", "info")
	v.SetDefault("logging.format", "json")
	v.SetDefault("telemetry.metrics_path", "/metrics")
	v.SetDefault("templates.default_locale", "vi")
	v.SetDefault("extensions.replay_mode_default_ttl", "6h")
	v.SetDefault("extensions.idempotency_ttl", "24h")

	v.SetDefault("privacy.hmac_key", "")
	v.SetDefault("privacy.hmac_key_version", 1)
	v.SetDefault("privacy.previous_hmac_keys", "")
}
