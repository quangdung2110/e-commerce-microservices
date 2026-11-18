//go:build integration

// Package integration_test exercises the adapters and the HTTP surface against
// real PostgreSQL and Redis instances started with testcontainers.
//
// The unit suite in test/unit covers the business rules with in-memory doubles.
// This suite covers what only a real dependency can prove: the SQL, the schema
// constraints, the Lua scripts, the crypto adapters and the wiring of the HTTP
// layer. Run it with: make test-integration
package integration_test

import (
	"context"
	"log"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	postgresmodule "github.com/testcontainers/testcontainers-go/modules/postgres"
	redismodule "github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
	redisadapter "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

const (
	postgresImage = "postgres:16-alpine"
	redisImage    = "redis:7-alpine"
)

// Shared across the suite: starting a container per test would dominate the
// runtime, so each test resets state instead.
var (
	testPool      *pgxpool.Pool
	testRedis     *redis.Client
	testDBConf    config.DatabaseConfig
	testRedisConf config.RedisConfig
)

func TestMain(m *testing.M) {
	ctx := context.Background()

	pgContainer, err := startPostgres(ctx)
	if err != nil {
		log.Fatalf("integration: start postgres: %v", err)
	}
	redisContainer, err := startRedis(ctx)
	if err != nil {
		terminate(ctx, pgContainer)
		log.Fatalf("integration: start redis: %v", err)
	}

	code := 1
	func() {
		defer func() {
			if testPool != nil {
				testPool.Close()
			}
			if testRedis != nil {
				_ = testRedis.Close()
			}
			terminate(ctx, pgContainer, redisContainer)
		}()
		code = m.Run()
	}()

	os.Exit(code)
}

func startPostgres(ctx context.Context) (testcontainers.Container, error) {
	// The schema under test is the one the service ships, so a drift between the
	// migration and the repositories fails the suite.
	schema, err := filepath.Abs(filepath.Join("..", "..", "migrations", "0001_initial_auth_schema.up.sql"))
	if err != nil {
		return nil, err
	}

	container, err := postgresmodule.Run(ctx, postgresImage,
		postgresmodule.WithDatabase("auth_db"),
		postgresmodule.WithUsername("postgres"),
		postgresmodule.WithPassword("postgres"),
		postgresmodule.WithInitScripts(schema),
		testcontainers.WithEnv(map[string]string{"TZ": "UTC"}),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(2*time.Minute),
		),
	)
	if err != nil {
		return nil, err
	}

	host, err := container.Host(ctx)
	if err != nil {
		return nil, err
	}
	mapped, err := container.MappedPort(ctx, "5432/tcp")
	if err != nil {
		return nil, err
	}

	testDBConf = config.DatabaseConfig{
		Host:             host,
		Port:             int(mapped.Num()),
		Name:             "auth_db",
		Username:         "postgres",
		Password:         "postgres",
		SSLMode:          "disable",
		MaxOpenConns:     8,
		MinConns:         1,
		ConnMaxLifetime:  5 * time.Minute,
		ConnectTimeout:   10 * time.Second,
		StatementTimeout: 10 * time.Second,
	}

	testPool, err = postgres.NewPool(ctx, testDBConf)
	if err != nil {
		return nil, err
	}
	return container, nil
}

func startRedis(ctx context.Context) (testcontainers.Container, error) {
	container, err := redismodule.Run(ctx, redisImage)
	if err != nil {
		return nil, err
	}

	host, err := container.Host(ctx)
	if err != nil {
		return nil, err
	}
	mapped, err := container.MappedPort(ctx, "6379/tcp")
	if err != nil {
		return nil, err
	}

	testRedisConf = config.RedisConfig{
		Host:             host,
		Port:             int(mapped.Num()),
		DB:               0,
		PoolSize:         8,
		MinIdleConns:     1,
		DialTimeout:      5 * time.Second,
		ReadTimeout:      5 * time.Second,
		WriteTimeout:     5 * time.Second,
		RevocationPrefix: "auth:revocation",
		StatePrefix:      "auth",
	}

	testRedis, err = redisadapter.NewClient(ctx, testRedisConf)
	if err != nil {
		return nil, err
	}
	return container, nil
}

func terminate(ctx context.Context, containers ...testcontainers.Container) {
	for _, container := range containers {
		if container == nil {
			continue
		}
		if err := container.Terminate(ctx); err != nil {
			log.Printf("integration: terminate container: %v", err)
		}
	}
}

// resetState gives each test an empty database and cache, so tests stay
// independent of their order.
func resetState(t *testing.T) {
	t.Helper()
	ctx := context.Background()

	if _, err := testPool.Exec(ctx, "TRUNCATE auth.accounts CASCADE"); err != nil {
		t.Fatalf("truncate accounts: %v", err)
	}
	if _, err := testPool.Exec(ctx, "TRUNCATE auth.jwt_keys"); err != nil {
		t.Fatalf("truncate jwt keys: %v", err)
	}
	if err := testRedis.FlushDB(ctx).Err(); err != nil {
		t.Fatalf("flush redis: %v", err)
	}
}
