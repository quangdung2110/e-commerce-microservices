package cartredis

import (
	"crypto/tls"
	"time"

	"github.com/redis/go-redis/v9"
)

// ClientConfig configures the shared Redis connection.
type ClientConfig struct {
	Addrs        []string
	Username     string
	Password     string
	DB           int
	PoolSize     int
	MinIdleConns int
	DialTimeout  time.Duration
	ReadTimeout  time.Duration
	WriteTimeout time.Duration
	// TLSEnabled turns on TLS. Production Redis is required to run with TLS,
	// AOF, a replica and maxmemory-policy noeviction (docs/cart/data.md §1).
	TLSEnabled bool
}

// NewClient builds a Redis client. A single address yields a standalone
// client; several addresses yield a cluster client. Both satisfy
// redis.UniversalClient, and the key layout is cluster-safe either way.
func NewClient(cfg ClientConfig) redis.UniversalClient {
	options := &redis.UniversalOptions{
		Addrs:        cfg.Addrs,
		Username:     cfg.Username,
		Password:     cfg.Password,
		DB:           cfg.DB,
		PoolSize:     cfg.PoolSize,
		MinIdleConns: cfg.MinIdleConns,
		DialTimeout:  cfg.DialTimeout,
		ReadTimeout:  cfg.ReadTimeout,
		WriteTimeout: cfg.WriteTimeout,
	}
	if cfg.TLSEnabled {
		options.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	}
	return redis.NewUniversalClient(options)
}
