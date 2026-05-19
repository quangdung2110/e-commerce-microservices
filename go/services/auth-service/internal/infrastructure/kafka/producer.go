package kafka

import (
	"fmt"

	"github.com/IBM/sarama"
	"github.com/ecommerce/auth-service/internal/common/config"
)

type Producer struct {
	producer sarama.SyncProducer
	topicPrefix string
}

func NewProducer(cfg config.KafkaConfig) (*Producer, error) {
	saramaConfig := sarama.NewConfig()
	saramaConfig.Producer.Return.Successes = true
	saramaConfig.Producer.RequiredAcks = sarama.WaitForAll
	saramaConfig.Producer.Retry.Max = 3
	saramaConfig.Producer.Return.Errors = true

	producer, err := sarama.NewSyncProducer(cfg.Brokers, saramaConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create Kafka producer: %w", err)
	}

	return &Producer{
		producer:    producer,
		topicPrefix: cfg.TopicPrefix,
	}, nil
}

func (p *Producer) SendMessage(topic string, key string, value []byte) error {
	fullTopic := p.topicPrefix + "." + topic
	msg := &sarama.ProducerMessage{
		Topic: fullTopic,
		Key:   sarama.StringEncoder(key),
		Value: sarama.ByteEncoder(value),
	}

	_, _, err := p.producer.SendMessage(msg)
	return err
}

func (p *Producer) Close() error {
	return p.producer.Close()
}
