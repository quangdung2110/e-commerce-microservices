package main

import (
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/redis"

	"gorm.io/gorm"
)

// Infrastructure holds all initialized infrastructure clients.
type Infrastructure struct {
	// Redis
	RedisClient *redis.Client
	CartLuaRepo port.CartLuaRepository
	CacheRepo   port.CacheRepository
	OutboxWriter port.OutboxWriter
	RedisHealth port.HealthChecker

	// PostgreSQL
	PGDB     *gorm.DB
	CartRepo port.CartRepository
	PGHealth port.HealthChecker

	// Kafka
	KafkaPublisher port.KafkaPublisher

	// gRPC
	ProductClient port.ProductClient
}
