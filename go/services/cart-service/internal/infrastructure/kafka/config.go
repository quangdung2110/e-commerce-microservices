package kafka

import (
	"strings"
	"time"

	"github.com/IBM/sarama"
)

// Config holds Kafka producer/consumer configuration.
// It is built from the app-level config.KafkaSection in loader.go.
type Config struct {
	Brokers           []string
	TopicCartEvents  string
	TopicOrderEvents string

	Producer ProducerConfig
	Consumer ConsumerConfig
}

type ProducerConfig struct {
	MaxMessageBytes int
	RequiredAcks   int
	Timeout        time.Duration
	RetryMax       int
	RetryBackoff   time.Duration
}

type ConsumerConfig struct {
	GroupID            string
	SessionTimeout     time.Duration
	HeartbeatInterval  time.Duration
	RebalanceTimeout   time.Duration
	AutoOffsetReset   string
	EnableAutoCommit  bool
}

// NewSaramaConfig creates a Sarama config from our Config.
// The isProducer flag selects producer vs consumer settings.
func NewSaramaConfig(cfg Config, isProducer bool) (*sarama.Config, error) {
	config := sarama.NewConfig()

	if isProducer {
		if cfg.Producer.MaxMessageBytes > 0 {
			config.Producer.MaxMessageBytes = cfg.Producer.MaxMessageBytes
		}
		config.Producer.Return.Successes = true
		config.Producer.Return.Errors = true
		config.Producer.Retry.Max = cfg.Producer.RetryMax
		config.Producer.Retry.Backoff = cfg.Producer.RetryBackoff
		config.Producer.Timeout = cfg.Producer.Timeout

		switch cfg.Producer.RequiredAcks {
		case -1:
			config.Producer.RequiredAcks = sarama.WaitForAll
		case 0:
			config.Producer.RequiredAcks = sarama.NoResponse
		default:
			config.Producer.RequiredAcks = sarama.WaitForLocal
		}
	} else {
		config.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
			sarama.NewBalanceStrategyRoundRobin(),
		}
		config.Consumer.Group.Session.Timeout = cfg.Consumer.SessionTimeout
		config.Consumer.Group.Heartbeat.Interval = cfg.Consumer.HeartbeatInterval
		config.Consumer.Group.Rebalance.Timeout = cfg.Consumer.RebalanceTimeout
		config.Consumer.Return.Errors = true

		if cfg.Consumer.AutoOffsetReset == "earliest" {
			config.Consumer.Offsets.Initial = sarama.OffsetOldest
		} else {
			config.Consumer.Offsets.Initial = sarama.OffsetNewest
		}

		config.Consumer.Offsets.AutoCommit.Enable = cfg.Consumer.EnableAutoCommit
	}

	return config, nil
}

// BrokerList returns comma-separated broker list.
func BrokerList(cfg Config) string {
	return strings.Join(cfg.Brokers, ",")
}
