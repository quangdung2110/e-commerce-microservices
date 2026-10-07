// Package postgres implements port.Store on PostgreSQL with pgx v5: the queue
// mechanics of 02-pipeline.md §5, the dedup ledgers of 04-idempotency.md, the
// read models of 09-data-model.md and the partition jobs of §9.
package postgres

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/pgx/v5" // registers the pgx5:// driver
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/migrations"
)

// NewPool opens one connection pool. The service opens one pool per role
// (consumer, each deliver lane, track, api, jobs) so a bulk drain can never
// starve the critical consumer of connections — the bulkhead of
// 06-backpressure.md §3. appName lands in pg_stat_activity.application_name
// so ops can see which role holds which connection.
func NewPool(ctx context.Context, cfg config.DatabaseConfig, maxConns int32, appName string) (*pgxpool.Pool, error) {
	return NewPoolDSN(ctx, cfg.DSN(), cfg, maxConns, appName)
}

// NewPoolDSN is NewPool with an explicit DSN (notifctl, tests); cfg supplies
// only the timeouts.
func NewPoolDSN(ctx context.Context, dsn string, cfg config.DatabaseConfig, maxConns int32, appName string) (*pgxpool.Pool, error) {
	poolCfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("postgres: parse dsn: %w", err)
	}
	if maxConns <= 0 {
		maxConns = 2
	}
	poolCfg.MaxConns = maxConns
	poolCfg.MinConns = 1
	if cfg.ConnMaxLifetime > 0 {
		poolCfg.MaxConnLifetime = cfg.ConnMaxLifetime
	}
	if cfg.ConnectTimeout > 0 {
		poolCfg.ConnConfig.ConnectTimeout = cfg.ConnectTimeout
	}
	if poolCfg.ConnConfig.RuntimeParams == nil {
		poolCfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	// UTC everywhere: lease/backoff arithmetic is done in Go with the injected
	// clock and compared against timestamptz columns; a session timezone must
	// never shift what the queue considers "ready".
	poolCfg.ConnConfig.RuntimeParams["timezone"] = "UTC"
	poolCfg.ConnConfig.RuntimeParams["application_name"] = appName

	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return nil, fmt.Errorf("postgres: create pool: %w", err)
	}

	timeout := cfg.ConnectTimeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	pingCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("postgres: ping: %w", err)
	}
	return pool, nil
}

// Migrate applies every pending embedded migration (idempotent).
func Migrate(ctx context.Context, dsn string) error {
	m, err := newMigrator(dsn)
	if err != nil {
		return err
	}
	defer closeMigrator(m)
	return runMigration(ctx, m, func() error { return m.Up() })
}

// MigrateDown rolls back exactly one migration step (notifctl migrate down).
func MigrateDown(ctx context.Context, dsn string) error {
	m, err := newMigrator(dsn)
	if err != nil {
		return err
	}
	defer closeMigrator(m)
	return runMigration(ctx, m, func() error { return m.Steps(-1) })
}

func newMigrator(dsn string) (*migrate.Migrate, error) {
	src, err := iofs.New(migrations.FS, ".")
	if err != nil {
		return nil, fmt.Errorf("postgres: migration source: %w", err)
	}
	m, err := migrate.NewWithSourceInstance("iofs", src, migrateURL(dsn))
	if err != nil {
		return nil, fmt.Errorf("postgres: migrator: %w", err)
	}
	return m, nil
}

// migrateURL rewrites the scheme to the one golang-migrate registered for its
// pgx v5 driver; the driver itself dials with the plain postgres scheme.
func migrateURL(dsn string) string {
	for _, scheme := range []string{"postgres://", "postgresql://"} {
		if strings.HasPrefix(dsn, scheme) {
			return "pgx5://" + strings.TrimPrefix(dsn, scheme)
		}
	}
	return dsn
}

// runMigration runs op and honours ctx cancellation: golang-migrate has no
// context API, so a cancelled context triggers GracefulStop, which finishes
// the statement in flight and stops before the next one.
func runMigration(ctx context.Context, m *migrate.Migrate, op func() error) error {
	done := make(chan error, 1)
	go func() { done <- op() }()
	select {
	case err := <-done:
		if err != nil && !errors.Is(err, migrate.ErrNoChange) {
			return fmt.Errorf("postgres: migrate: %w", err)
		}
		return nil
	case <-ctx.Done():
		m.GracefulStop <- true
		<-done
		return fmt.Errorf("postgres: migrate: %w", ctx.Err())
	}
}

func closeMigrator(m *migrate.Migrate) {
	_, _ = m.Close()
}
