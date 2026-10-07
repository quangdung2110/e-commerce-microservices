// Package postgres implements the durable repositories on PostgreSQL. Every
// enum parameter is cast explicitly so a value that is not part of the schema
// enum fails at the database boundary instead of being coerced.
package postgres

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/auth-service/internal/common/config"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
)

// DBTX is the subset of pgx used by the repositories. Both a pool and a
// transaction satisfy it, so a repository joins an open transaction unchanged.
type DBTX interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

type txContextKey struct{}

// NewPool opens the connection pool. The session timezone is pinned to UTC so
// interval arithmetic in the schema matches the timestamps computed in Go, in
// particular the exact 90-day hard-delete window.
func NewPool(ctx context.Context, cfg config.DatabaseConfig) (*pgxpool.Pool, error) {
	poolCfg, err := pgxpool.ParseConfig(cfg.DSN())
	if err != nil {
		return nil, fmt.Errorf("postgres: parse dsn: %w", err)
	}

	poolCfg.MaxConns = cfg.MaxOpenConns
	poolCfg.MinConns = cfg.MinConns
	poolCfg.MaxConnLifetime = cfg.ConnMaxLifetime
	poolCfg.ConnConfig.ConnectTimeout = cfg.ConnectTimeout
	if poolCfg.ConnConfig.RuntimeParams == nil {
		poolCfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	poolCfg.ConnConfig.RuntimeParams["timezone"] = "UTC"
	poolCfg.ConnConfig.RuntimeParams["application_name"] = "auth-service"
	if cfg.StatementTimeout > 0 {
		poolCfg.ConnConfig.RuntimeParams["statement_timeout"] =
			strconv.FormatInt(cfg.StatementTimeout.Milliseconds(), 10)
	}

	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return nil, fmt.Errorf("postgres: create pool: %w", err)
	}

	pingCtx, cancel := context.WithTimeout(ctx, cfg.ConnectTimeout)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("postgres: ping: %w", err)
	}
	return pool, nil
}

// TxManager runs a unit of work in one transaction and passes it down through
// the context.
type TxManager struct {
	pool *pgxpool.Pool
}

// NewTxManager wires the transaction manager.
func NewTxManager(pool *pgxpool.Pool) *TxManager {
	return &TxManager{pool: pool}
}

// WithinTx executes fn inside a transaction. A nested call joins the existing
// transaction instead of opening a second one.
func (m *TxManager) WithinTx(ctx context.Context, fn func(ctx context.Context) error) error {
	return withinTx(ctx, m.pool, fn)
}

// withinTx is the shared transaction helper: a repository that must write more
// than one table uses it directly, and joins an outer transaction when one is
// already open.
func withinTx(ctx context.Context, pool *pgxpool.Pool, fn func(ctx context.Context) error) error {
	if txFromContext(ctx) != nil {
		return fn(ctx)
	}

	tx, err := pool.Begin(ctx)
	if err != nil {
		return mapError(err)
	}

	if err := fn(context.WithValue(ctx, txContextKey{}, tx)); err != nil {
		// Use a detached context so the rollback still runs when the request
		// context is already cancelled.
		rollbackCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		if rollbackErr := tx.Rollback(rollbackCtx); rollbackErr != nil && !errors.Is(rollbackErr, pgx.ErrTxClosed) {
			return apperr.From(err).WithCause(fmt.Errorf("rollback failed: %w", rollbackErr))
		}
		return err
	}

	if err := tx.Commit(ctx); err != nil {
		return mapError(err)
	}
	return nil
}

func txFromContext(ctx context.Context) pgx.Tx {
	tx, _ := ctx.Value(txContextKey{}).(pgx.Tx)
	return tx
}

// repository is the shared executor resolution of every repository.
type repository struct {
	pool *pgxpool.Pool
}

func (r repository) db(ctx context.Context) DBTX {
	if tx := txFromContext(ctx); tx != nil {
		return tx
	}
	return r.pool
}

// Unique constraint names of the schema, used to map a violation to a domain
// error the client can act on.
const (
	constraintAccountsEmail    = "uq_accounts_email_normalized"
	constraintRefreshTokenHash = "uq_refresh_tokens_token_hash"
	constraintOneActiveKey     = "uq_jwt_keys_one_active"
)

// mapError converts a driver error into the domain vocabulary. Driver detail
// never reaches the client: it is attached as the internal cause only.
func mapError(err error) error {
	if err == nil {
		return nil
	}

	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		switch pgErr.ConstraintName {
		case constraintAccountsEmail:
			return apperr.ErrEmailAlreadyRegistered.WithCause(err)
		case constraintRefreshTokenHash, constraintOneActiveKey:
			return apperr.ErrInternal.WithCause(err)
		}
		if pgErr.Code == "23514" {
			// A CHECK violation means the domain produced a state the schema
			// forbids; that is a bug, not client input.
			return apperr.ErrInternal.WithCause(err)
		}
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return apperr.ErrDependencyUnavailable.WithCause(err)
	}
	return apperr.ErrInternal.WithCause(err)
}
