package kafka

import (
	"time"
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
	return DefaultConfig()
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
		MaxWaitTime:       10 * time.Second,
		MinBytes:          1,
		MaxBytes:          10485760, // 10MB
	}
}
