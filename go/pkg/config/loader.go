package config

import (
	"fmt"
	"strings"

	"github.com/spf13/viper"
)

// AppConfig holds application configuration
type AppConfig struct {
	Server   ServerConfig
	Kafka    KafkaConfig
	Redis    RedisConfig
	Database DatabaseConfig
	Temporal TemporalConfig
}

// ServerConfig holds HTTP server configuration
type ServerConfig struct {
	Port int    `mapstructure:"port"`
	Host string `mapstructure:"host"`
	Mode string `mapstructure:"mode"`
}

// KafkaConfig holds Kafka configuration
type KafkaConfig struct {
	Brokers         []string `mapstructure:"brokers"`
	GroupID         string   `mapstructure:"group_id"`
	Topics          []string `mapstructure:"topics"`
	DeadLetterTopic string   `mapstructure:"dead_letter_topic"`
}

// RedisConfig holds Redis configuration
type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
}

// DatabaseConfig holds database configuration
type DatabaseConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Name     string `mapstructure:"name"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	SSLMode  string `mapstructure:"ssl_mode"`
}

// TemporalConfig holds Temporal configuration
type TemporalConfig struct {
	HostPort  string `mapstructure:"host_port"`
	Namespace string `mapstructure:"namespace"`
}

// LoadConfig loads configuration from environment variables and config files
// Equivalent to Java's @ConfigurationProperties pattern
func LoadConfig(serviceName string) (*AppConfig, error) {
	// Set config name and paths
	viper.SetConfigName("application")
	viper.SetConfigType("yaml")
	viper.AddConfigPath(".")
	viper.AddConfigPath("./config")
	viper.AddConfigPath(fmt.Sprintf("./services/%s/config", serviceName))

	// Set default values
	setDefaults()

	// Read config file (optional)
	if err := viper.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			return nil, fmt.Errorf("failed to read config file: %w", err)
		}
		// Config file not found, continue with env vars and defaults
	}

	// Enable environment variable override
	viper.AutomaticEnv()
	viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	// Unmarshal config into struct
	var config AppConfig
	if err := viper.Unmarshal(&config); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	return &config, nil
}

// setDefaults sets default configuration values
func setDefaults() {
	// Server defaults
	viper.SetDefault("server.port", 8080)
	viper.SetDefault("server.host", "0.0.0.0")
	viper.SetDefault("server.mode", "development")

	// Kafka defaults
	viper.SetDefault("kafka.brokers", []string{"localhost:9092"})
	viper.SetDefault("kafka.group_id", "default-group")

	// Redis defaults
	viper.SetDefault("redis.host", "localhost")
	viper.SetDefault("redis.port", 6379)
	viper.SetDefault("redis.db", 0)

	// Database defaults
	viper.SetDefault("database.host", "localhost")
	viper.SetDefault("database.port", 5432)
	viper.SetDefault("database.ssl_mode", "disable")

	// Temporal defaults
	viper.SetDefault("temporal.host_port", "localhost:7233")
	viper.SetDefault("temporal.namespace", "default")
}

// GetString retrieves a string configuration value
func GetString(key string) string {
	return viper.GetString(key)
}

// GetInt retrieves an integer configuration value
func GetInt(key string) int {
	return viper.GetInt(key)
}

// GetBool retrieves a boolean configuration value
func GetBool(key string) bool {
	return viper.GetBool(key)
}

// GetStringSlice retrieves a string slice configuration value
func GetStringSlice(key string) []string {
	return viper.GetStringSlice(key)
}
