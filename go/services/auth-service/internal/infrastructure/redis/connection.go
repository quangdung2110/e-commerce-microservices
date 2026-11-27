// Package redis implements the TTL-bound security state of ADR-004: revocation
// signals, rate limits, one-time secrets and notification delivery state.
//
// Every adapter here fails closed. A Redis outage is reported as
// ErrDependencyUnavailable so a security check is never skipped silently.
package redis

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/config"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/keybuilder"
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

// errUnexpectedScriptResult means a Lua script returned a shape the adapter does
// not understand, which is a bug rather than an outage.
var errUnexpectedScriptResult = errors.New("redis: unexpected script result")

// store is the shared state of every Redis adapter.
type store struct {
	client *redis.Client
	keys   *keybuilder.Builder
}

// mapError turns a driver failure into the fail-closed domain error. redis.Nil
// is a normal "missing key" and must be handled by the caller, never here.
func mapError(err error) error {
	if err == nil {
		return nil
	}
	return apperr.ErrDependencyUnavailable.WithCause(err)
}
