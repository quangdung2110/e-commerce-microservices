package helper

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
)

type TestDatabase struct {
	Pool    *pgxpool.Pool
	Cleanup func()
}

func SetupPostgresContainer(t *testing.T) *TestDatabase {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	// Skip test if not running integration tests
	if os.Getenv("INTEGRATION_TEST") != "1" {
		t.Skip("Skipping integration test. Set INTEGRATION_TEST=1 to run.")
	}

	container, err := postgres.Run(ctx,
		"postgres:16-alpine",
		postgres.WithDatabase("auth_test"),
		postgres.WithUsername("postgres"),
		postgres.WithPassword("postgres"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(30*time.Second),
		),
	)
	require.NoError(t, err)

	connStr, err := container.ConnectionString(ctx)
	require.NoError(t, err)

	poolConfig, err := pgxpool.ParseConfig(connStr)
	require.NoError(t, err)
	poolConfig.MaxConns = 10
	poolConfig.MinConns = 2

	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	require.NoError(t, err)

	// Run migrations
	err = RunMigrations(ctx, pool)
	require.NoError(t, err, "Failed to run migrations")

	cleanup := func() {
		pool.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		container.Terminate(ctx)
	}

	return &TestDatabase{
		Pool:    pool,
		Cleanup: cleanup,
	}
}

func RunMigrations(ctx context.Context, pool *pgxpool.Pool) error {
	// Try to find migration file relative to current working directory
	paths := []string{
		"internal/infrastructure/postgres/migrations/001_initial_schema.up.sql",
		"../internal/infrastructure/postgres/migrations/001_initial_schema.up.sql",
		"../../internal/infrastructure/postgres/migrations/001_initial_schema.up.sql",
	}

	var migrationSQL []byte
	var err error

	for _, path := range paths {
		migrationSQL, err = os.ReadFile(filepath.Join(os.Getenv("PWD"), path))
		if err == nil {
			break
		}
	}

	if err != nil {
		// Fallback: try current directory
		migrationSQL, err = os.ReadFile("internal/infrastructure/postgres/migrations/001_initial_schema.up.sql")
		if err != nil {
			return fmt.Errorf("failed to find migration file: %w", err)
		}
	}

	_, err = pool.Exec(ctx, string(migrationSQL))
	if err != nil {
		return fmt.Errorf("failed to execute migration: %w", err)
	}

	return nil
}

func CleanupTables(ctx context.Context, pool *pgxpool.Pool, tables ...string) error {
	if len(tables) == 0 {
		tables = []string{
			"audit_logs",
			"refresh_tokens",
			"jwt_keys",
			"users",
		}
	}

	for _, table := range tables {
		_, err := pool.Exec(ctx, fmt.Sprintf("TRUNCATE TABLE %s CASCADE", table))
		if err != nil {
			return fmt.Errorf("failed to truncate table %s: %w", table, err)
		}
	}

	return nil
}
