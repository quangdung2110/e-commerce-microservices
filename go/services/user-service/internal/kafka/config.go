package kafka

import (
	"time"

	"github.com/spf13/viper"
)

// Config holds Kafka configuration
type Config struct {
	Brokers           []string
	AuthEventsTopic   string
	UserEventsTopic   string
	ConsumerGroup     string
	OffsetReset       string
	SessionTimeout    time.Duration
	HeartbeatInterval time.Duration
	MaxWaitTime       time.Duration
	MinBytes          int
	MaxBytes          int
}

// LoadConfig loads Kafka configuration from environment
func LoadConfig() *Config {
	brokers := viper.GetStringSlice("KAFKA_BROKERS")
	if len(brokers) == 0 {
		brokers = []string{"localhost:9092"}
	}

	offsetReset := viper.GetString("KAFKA_OFFSET_RESET")
	if offsetReset == "" {
		offsetReset = "earliest"
	}

	sessionTimeout := viper.GetDuration("KAFKA_SESSION_TIMEOUT")
	if sessionTimeout == 0 {
		sessionTimeout = 30 * time.Second
	}

	heartbeatInterval := viper.GetDuration("KAFKA_HEARTBEAT_INTERVAL")
	if heartbeatInterval == 0 {
		heartbeatInterval = 3 * time.Second
	}

	maxWaitTime := viper.GetDuration("KAFKA_MAX_WAIT_TIME")
	if maxWaitTime == 0 {
		maxWaitTime = 10 * time.Second
	}

	return &Config{
		Brokers:           brokers,
		AuthEventsTopic:    viper.GetString("KAFKA_AUTH_EVENTS_TOPIC"),
		UserEventsTopic:    viper.GetString("KAFKA_USER_EVENTS_TOPIC"),
		ConsumerGroup:     viper.GetString("KAFKA_CONSUMER_GROUP"),
		OffsetReset:       offsetReset,
		SessionTimeout:    sessionTimeout,
		HeartbeatInterval: heartbeatInterval,
		MaxWaitTime:       maxWaitTime,
		MinBytes:          viper.GetInt("KAFKA_MIN_BYTES"),
		MaxBytes:          viper.GetInt("KAFKA_MAX_BYTES"),
	}
}

// DefaultConfig returns a default Kafka configuration
func DefaultConfig() *Config {
	return &Config{
		Brokers:           []string{"localhost:9092"},
		AuthEventsTopic:   "t.auth.events",
		UserEventsTopic:   "t.user.events",
		ConsumerGroup:     "user-service",
		OffsetReset:       "earliest",
		SessionTimeout:    30 * time.Second,
		HeartbeatInterval: 3 * time.Second,
		MaxWaitTime:      10 * time.Second,
		MinBytes:          1,
		MaxBytes:          10485760, // 10MB
	}
}
