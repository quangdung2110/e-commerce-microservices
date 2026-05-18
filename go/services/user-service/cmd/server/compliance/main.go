package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"go.uber.org/fx"
	"go.uber.org/zap"

	"user-service/internal/domain/port"
	"user-service/internal/infrastructure/postgres"
	"user-service/internal/workers/compliance"
)

// @title User Service Compliance Worker
// @version 1.0
// @description GDPR Compliance Worker - Hard Delete after 90 days retention

func main() {
	runNow := flag.Bool("run-now", false, "Run hard delete immediately instead of scheduling")
	flag.Parse()

	if *runNow {
		fmt.Println("Running in one-shot mode")
	}

	app := fx.New(
		fx.Provide(
			NewLogger,
			NewConfig,
			NewPostgresPool,
			NewProfileRepository,
			NewUserDeletionRepository,
			NewComplianceWorker,
		),
		fx.Invoke(func(lc fx.Lifecycle, worker *compliance.Worker, logger *zap.Logger) {
			lc.Append(fx.Hook{
				OnStart: func(ctx context.Context) error {
					if *runNow {
						logger.Info("Running hard delete immediately")
						go worker.RunNow(ctx)
					} else {
						logger.Info("Starting compliance worker scheduler")
						if err := worker.Start(); err != nil {
							return err
						}
					}
					return nil
				},
				OnStop: func(ctx context.Context) error {
					logger.Info("Compliance worker stopped")
					return nil
				},
			})
		}),
	)

	ctx := context.Background()
	if err := app.Start(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to start compliance worker: %v\n", err)
		os.Exit(1)
	}

	// Wait for interrupt signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	fmt.Println("\nShutting down compliance worker...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30)
	defer cancel()

	if err := app.Stop(shutdownCtx); err != nil {
		fmt.Fprintf(os.Stderr, "Worker forced to shutdown: %v\n", err)
	}
}

// Logger setup
func NewLogger() (*zap.Logger, error) {
	return zap.NewProduction()
}

// Config holds configuration
type Config struct {
	DB DBConfig
}

// DBConfig holds database configuration
type DBConfig struct {
	Host     string
	Port     string
	User     string
	Password string
	Database string
	SSLMode  string
}

func NewConfig() *Config {
	return &Config{
		DB: DBConfig{
			Host:     getEnv("DB_HOST", "localhost"),
			Port:     getEnv("DB_PORT", "5432"),
			User:     getEnv("DB_USER", "postgres"),
			Password: getEnv("DB_PASSWORD", "postgres"),
			Database: getEnv("DB_NAME", "user_service"),
			SSLMode:  getEnv("DB_SSLMODE", "disable"),
		},
	}
}

func NewPostgresPool(logger *zap.Logger, cfg *Config) (*postgres.PoolWrapper, error) {
	return postgres.NewPoolWrapper(logger, postgres.PoolConfig{
		Host:     cfg.DB.Host,
		Port:     cfg.DB.Port,
		User:     cfg.DB.User,
		Password: cfg.DB.Password,
		Database: cfg.DB.Database,
		SSLMode:  cfg.DB.SSLMode,
		MaxConns: 10,
		MinConns: 2,
	})
}

func NewProfileRepository(db *postgres.PoolWrapper) port.ProfileRepository {
	return postgres.NewPostgresProfileRepository(db.Pool)
}

func NewUserDeletionRepository(db *postgres.PoolWrapper) port.UserDeletionRepository {
	return postgres.NewPostgresUserDeletionRepository(db.Pool)
}

func NewComplianceWorker(
	db *postgres.PoolWrapper,
	profileRepo port.ProfileRepository,
	deletionRepo port.UserDeletionRepository,
	logger *zap.Logger,
) *compliance.Worker {
	return compliance.NewWorker(db.Pool, profileRepo, deletionRepo, logger)
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
