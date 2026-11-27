// Package redis implements the cache-aside address list, the worker lock and
// the API rate limiter.
//
// Unlike a security-critical store, the address cache degrades instead of
// failing closed: when Redis cannot answer, reads fall back to PostgreSQL and
// mutations still commit (ADR-002). The lock and the limiter do fail closed,
// because skipping them would let two sweeps run at once or remove a limit.
package redis

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/user-service/internal/common/config"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/common/keybuilder"
)

// NewClient opens the Redis client shared by every adapter.
func NewClient(ctx context.Context, cfg config.RedisConfig) (*redis.Client, error) {
	options := &redis.Options{
		Addr:         cfg.Addr(),
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
	client := redis.NewClient(options)

	if err := client.Ping(ctx).Err(); err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("redis: ping: %w", err)
	}
	return client, nil
}

// errUnexpectedScriptResult means a Lua script returned a shape the adapter
// does not understand, which is a bug rather than an outage.
var errUnexpectedScriptResult = errors.New("redis: unexpected script result")

// store is the shared state of every Redis adapter.
type store struct {
	client *redis.Client
	keys   *keybuilder.Builder
}

// mapError turns a driver failure into a domain error. redis.Nil is a normal
// "missing key" and must be handled by the caller, never here.
func mapError(err error) error {
	if err == nil {
		return nil
	}
	return apperr.ErrDependencyUnavailable.WithCause(err)
}
