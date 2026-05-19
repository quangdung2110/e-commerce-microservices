package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/auth-service/internal/common/config"
)

var ErrNotFound = errors.New("record not found")

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

func (c *Connection) BeginTx(ctx context.Context) (Transaction, error) {
	tx, err := c.Pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to begin transaction: %w", err)
	}
	return &transaction{tx: tx}, nil
}

type Transaction interface {
	Commit(ctx context.Context) error
	Rollback(ctx context.Context) error
	QueryRow(ctx context.Context, sql string, args ...interface{}) pgx.Row
	Query(ctx context.Context, sql string, args ...interface{}) (pgx.Rows, error)
	Exec(ctx context.Context, sql string, args ...interface{}) (pgxconn, error)
}

type transaction struct {
	tx pgx.Tx
}

func (t *transaction) Commit(ctx context.Context) error {
	return t.tx.Commit(ctx)
}

func (t *transaction) Rollback(ctx context.Context) error {
	return t.tx.Rollback(ctx)
}

func (t *transaction) QueryRow(ctx context.Context, sql string, args ...interface{}) pgx.Row {
	return t.tx.QueryRow(ctx, sql, args...)
}

func (t *transaction) Query(ctx context.Context, sql string, args ...interface{}) (pgx.Rows, error) {
	return t.tx.Query(ctx, sql, args...)
}

func (t *transaction) Exec(ctx context.Context, sql string, args ...interface{}) (pgxconn, error) {
	result, err := t.tx.Exec(ctx, sql, args...)
	return &pgxResult{result: result}, err
}

type pgxconn interface {
	RowsAffected() int64
}

type pgxResult struct {
	result pgxconn
}

func (r *pgxResult) RowsAffected() int64 {
	return r.result.RowsAffected()
}
