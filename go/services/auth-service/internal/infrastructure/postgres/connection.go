package postgres

import (
	"context"
	"fmt"
	"time"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Connection struct {
	Pool *pgxpool.Pool
}

func NewConnection(cfg config.DatabaseConfig) (*Connection, error) {
	dsn := fmt.Sprintf(
		"postgres://%s:%s@%s:%d/%s?sslmode=%s",
		cfg.Username,
		cfg.Password,
		cfg.Host,
		cfg.Port,
		cfg.Name,
		cfg.SSLMode,
	)

	poolConfig, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to parse database config: %w", err)
	}

	poolConfig.MaxConns = int32(cfg.MaxOpenConns)
	poolConfig.MinConns = int32(cfg.MaxIdleConns)
	poolConfig.MaxConnLifetime = cfg.ConnMaxLifetime

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create connection pool: %w", err)
	}

	if err := pool.Ping(ctx); err != nil {
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	return &Connection{Pool: pool}, nil
}

func (c *Connection) Ping(ctx context.Context) error {
	return c.Pool.Ping(ctx)
}

func (c *Connection) Close() {
	c.Pool.Close()
}
