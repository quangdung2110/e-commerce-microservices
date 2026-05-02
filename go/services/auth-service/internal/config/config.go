package config

import (
	"fmt"
	"strings"

	"github.com/spf13/viper"
	pkgConfig "github.com/yourusername/spring-event-sourcing/go/pkg/config"
)

// Config holds auth-service specific configuration
type Config struct {
	Server           pkgConfig.ServerConfig
	Database         pkgConfig.DatabaseConfig
	Redis            pkgConfig.RedisConfig
	Kafka            pkgConfig.KafkaConfig
	JWT              JWTConfig
	Security         SecurityConfig
	ExternalServices ExternalServicesConfig
	OTP              OTPConfig
	CORS             CORSConfig
	JWKS             JWKSConfig
	Workers          WorkerConfig
	Debezium         DebeziumConfig
}

// JWTConfig holds JWT configuration
type JWTConfig struct {
	AccessTokenSecret   string `mapstructure:"access_token_secret"`
	RefreshTokenSecret  string `mapstructure:"refresh_token_secret"`
	AccessTokenExpiry   int    `mapstructure:"access_token_expiry"`  // minutes
	RefreshTokenExpiry  int    `mapstructure:"refresh_token_expiry"` // days
	Issuer              string `mapstructure:"issuer"`
	TokenRotationWindow int    `mapstructure:"token_rotation_window"` // minutes
}

// SecurityConfig holds security configuration
type SecurityConfig struct {
	BcryptCost               int  `mapstructure:"bcrypt_cost"`
	MaxLoginAttempts         int  `mapstructure:"max_login_attempts"`
	LoginAttemptWindow       int  `mapstructure:"login_attempt_window"` // minutes
	IPBlockDuration          int  `mapstructure:"ip_block_duration"`    // minutes
	OTPMaxAttempts           int  `mapstructure:"otp_max_attempts"`
	SessionConcurrencyLimit  int  `mapstructure:"session_concurrency_limit"`
	RefreshTokenReusePeriod  int  `mapstructure:"refresh_token_reuse_period"` // seconds
	PasswordMinLength        int  `mapstructure:"password_min_length"`
	PasswordRequireUppercase bool `mapstructure:"password_require_uppercase"`
	PasswordRequireLowercase bool `mapstructure:"password_require_lowercase"`
	PasswordRequireDigit     bool `mapstructure:"password_require_digit"`
	PasswordRequireSpecial   bool `mapstructure:"password_require_special"`
}

// OTPConfig holds OTP configuration
type OTPConfig struct {
	EmailTTL int `mapstructure:"email_ttl"` // minutes
	SMSTTL   int `mapstructure:"sms_ttl"`   // minutes
}

// ExternalServicesConfig holds external service URLs
type ExternalServicesConfig struct {
	UserServiceURL         string `mapstructure:"user_service_url"`
	NotificationServiceURL string `mapstructure:"notification_service_url"`
}

// CORSConfig holds CORS configuration
type CORSConfig struct {
	AllowOrigins     []string `mapstructure:"allow_origins"`
	AllowMethods     []string `mapstructure:"allow_methods"`
	AllowHeaders     []string `mapstructure:"allow_headers"`
	ExposeHeaders    []string `mapstructure:"expose_headers"`
	AllowCredentials bool     `mapstructure:"allow_credentials"`
	MaxAge           int      `mapstructure:"max_age"` // seconds
}

// JWKSConfig holds JWKS configuration for RSA key pairs
type JWKSConfig struct {
	Algorithm        string `mapstructure:"algorithm"`         // RS256
	RotationInterval string `mapstructure:"rotation_interval"` // e.g., "2160h" for 90 days
	PhaseOutDays     int    `mapstructure:"phase_out_days"`    // days to keep PHASE_OUT key
	KeySizeBits      int    `mapstructure:"key_size_bits"`     // 2048 or 4096
	Enabled          bool   `mapstructure:"enabled"`
}

// WorkerConfig holds background worker configuration
type WorkerConfig struct {
	Enabled         bool `mapstructure:"enabled"`
	TokenCleanup    WorkerTaskConfig `mapstructure:"token_cleanup"`
	AuditCleanup    WorkerTaskConfig `mapstructure:"audit_cleanup"`
	KeyRotation     WorkerTaskConfig `mapstructure:"key_rotation"`
}

// WorkerTaskConfig holds configuration for individual worker tasks
type WorkerTaskConfig struct {
	Enabled       bool   `mapstructure:"enabled"`
	Cron          string `mapstructure:"cron"`           // cron expression
	RetentionDays int    `mapstructure:"retention_days"` // days to retain data
}

// DebeziumConfig holds Debezium CDC configuration
type DebeziumConfig struct {
	Enabled        bool   `mapstructure:"enabled"`
	ConnectorName string `mapstructure:"connector_name"`
	DatabaseHost  string `mapstructure:"database_host"`
	DatabasePort  int    `mapstructure:"database_port"`
	DatabaseName  string `mapstructure:"database_name"`
}

// LoadAuthConfig loads auth-service specific configuration
func LoadAuthConfig() (*Config, error) {
	// Set config name and paths
	viper.SetConfigName("application")
	viper.SetConfigType("yaml")
	viper.AddConfigPath(".")
	viper.AddConfigPath("./config")
	viper.AddConfigPath("../config")

	// Set default values
	setAuthDefaults()

	// Read config file (optional)
	if err := viper.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			return nil, fmt.Errorf("failed to read config file: %w", err)
		}
	}

	// Enable environment variable override
	viper.AutomaticEnv()
	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	viper.SetEnvPrefix("AUTH")

	var config Config
	if err := viper.Unmarshal(&config); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	return &config, nil
}

func setAuthDefaults() {
	// Server defaults
	viper.SetDefault("server.port", 8081)
	viper.SetDefault("server.host", "0.0.0.0")
	viper.SetDefault("server.mode", "development")

	// Database defaults
	viper.SetDefault("database.host", "localhost")
	viper.SetDefault("database.port", 5432)
	viper.SetDefault("database.name", "auth_db")
	viper.SetDefault("database.user", "postgres")
	viper.SetDefault("database.password", "postgres")
	viper.SetDefault("database.ssl_mode", "disable")

	// Redis defaults
	viper.SetDefault("redis.host", "localhost")
	viper.SetDefault("redis.port", 6379)
	viper.SetDefault("redis.db", 0)
	viper.SetDefault("redis.password", "")

	// Kafka defaults
	viper.SetDefault("kafka.brokers", []string{"localhost:9092"})
	viper.SetDefault("kafka.group_id", "auth-service-group")

	// JWT defaults
	viper.SetDefault("jwt.access_token_secret", "your-secret-key-min-32-chars-long")
	viper.SetDefault("jwt.refresh_token_secret", "your-refresh-secret-key-min-32-chars")
	viper.SetDefault("jwt.access_token_ttl_minutes", 15) // 15 minutes
	viper.SetDefault("jwt.refresh_token_ttl_days", 90)   // 90 days
	viper.SetDefault("jwt.issuer", "auth-service")
	viper.SetDefault("jwt.token_rotation_window", 5) // 5 minutes

	// Security defaults
	viper.SetDefault("security.bcrypt_cost", 12)
	viper.SetDefault("security.max_login_attempts", 5)
	viper.SetDefault("security.login_attempt_window", 15)
	viper.SetDefault("security.ip_block_duration", 60)
	viper.SetDefault("security.otp_max_attempts", 3)
	viper.SetDefault("security.session_concurrency_limit", 5)
	viper.SetDefault("security.refresh_token_reuse_period", 10)
	viper.SetDefault("security.password_min_length", 8)
	viper.SetDefault("security.password_require_uppercase", true)
	viper.SetDefault("security.password_require_lowercase", true)
	viper.SetDefault("security.password_require_digit", true)
	viper.SetDefault("security.password_require_special", true)

	// OTP defaults
	viper.SetDefault("otp.email_ttl", 15) // 15 minutes
	viper.SetDefault("otp.sms_ttl", 5)    // 5 minutes

	// External services defaults
	viper.SetDefault("externalservices.user_service_url", "http://localhost:8082")
	viper.SetDefault("externalservices.notification_service_url", "http://localhost:8083")

	// CORS defaults
	viper.SetDefault("cors.allow_origins", []string{"http://localhost:3000"})
	viper.SetDefault("cors.allow_methods", []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"})
	viper.SetDefault("cors.allow_headers", []string{"Origin", "Content-Type", "Authorization"})
	viper.SetDefault("cors.expose_headers", []string{"Content-Length", "Authorization"})
	viper.SetDefault("cors.allow_credentials", true)
	viper.SetDefault("cors.max_age", 86400) // 24 hours

	// JWKS defaults
	viper.SetDefault("jwks.algorithm", "RS256")
	viper.SetDefault("jwks.rotation_interval", "2160h") // 90 days
	viper.SetDefault("jwks.phase_out_days", 7)
	viper.SetDefault("jwks.key_size_bits", 4096)
	viper.SetDefault("jwks.enabled", true)

	// Workers defaults
	viper.SetDefault("workers.enabled", true)
	viper.SetDefault("workers.token_cleanup.enabled", true)
	viper.SetDefault("workers.token_cleanup.cron", "0 2 * * *")
	viper.SetDefault("workers.token_cleanup.retention_days", 7)
	viper.SetDefault("workers.audit_cleanup.enabled", true)
	viper.SetDefault("workers.audit_cleanup.cron", "0 3 * * *")
	viper.SetDefault("workers.audit_cleanup.retention_days", 180)
	viper.SetDefault("workers.key_rotation.enabled", true)
	viper.SetDefault("workers.key_rotation.cron", "0 0 1 */3 *")
	viper.SetDefault("workers.key_rotation.retention_days", 0)

	// Debezium defaults
	viper.SetDefault("debezium.enabled", false)
	viper.SetDefault("debezium.connector_name", "auth-outbox-connector")
	viper.SetDefault("debezium.database_host", "localhost")
	viper.SetDefault("debezium.database_port", 5432)
	viper.SetDefault("debezium.database_name", "auth_db")
}
