package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/robfig/cron/v3"
	"go.uber.org/fx"
	"go.uber.org/zap"

	"user-service/internal/domain/entity"
	"user-service/internal/domain/port"
	"user-service/internal/infrastructure/postgres"
)

// ComplianceWorker implements the GDPR Hard Delete worker (Stage 2)
// This worker runs on a schedule to hard delete user records after the retention window expires
type ComplianceWorker struct {
	db           *pgxpool.Pool
	profileRepo  port.ProfileRepository
	deletionRepo port.UserDeletionRepository
	logger       *zap.Logger
}

// NewComplianceWorker creates a new ComplianceWorker
func NewComplianceWorker(
	db *pgxpool.Pool,
	profileRepo port.ProfileRepository,
	deletionRepo port.UserDeletionRepository,
	logger *zap.Logger,
) *ComplianceWorker {
	return &ComplianceWorker{
		db:           db,
		profileRepo:  profileRepo,
		deletionRepo: deletionRepo,
		logger:       logger,
	}
}

// Start schedules and starts the compliance worker
func (w *ComplianceWorker) Start() error {
	c := cron.New()

	// Schedule hard delete to run daily at 2 AM
	_, err := c.AddFunc("0 2 * * *", w.runHardDelete)
	if err != nil {
		return fmt.Errorf("failed to schedule hard delete cron: %w", err)
	}

	c.Start()
	w.logger.Info("Compliance worker started", zap.String("schedule", "0 2 * * *"))
	return nil
}

// RunNow triggers an immediate hard delete run (for testing/manual execution)
func (w *ComplianceWorker) RunNow(ctx context.Context) error {
	w.runHardDelete()
	return nil
}

// runHardDelete processes all pending hard deletes
func (w *ComplianceWorker) runHardDelete() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()

	w.logger.Info("Starting GDPR hard delete batch")

	totalDeleted := 0
	totalFailed := 0

	for {
		// Get batch of records ready for hard delete (max 100 at a time)
		deletions, err := w.deletionRepo.GetPendingHardDeletes(ctx, 100)
		if err != nil {
			w.logger.Error("Failed to get pending deletions", zap.Error(err))
			break
		}

		if len(deletions) == 0 {
			break
		}

		w.logger.Info("Processing hard delete batch", zap.Int("count", len(deletions)))

		for _, deletion := range deletions {
			if err := w.hardDeleteUser(ctx, deletion); err != nil {
				w.logger.Error("Failed to hard delete user",
					zap.String("user_id", deletion.UserID.String()),
					zap.String("deletion_id", deletion.ID.String()),
					zap.Error(err),
				)
				totalFailed++
				continue
			}
			totalDeleted++
			w.logger.Info("Hard deleted user",
				zap.String("user_id", deletion.UserID.String()),
			)
		}
	}

	w.logger.Info("GDPR hard delete batch completed",
		zap.Int("total_deleted", totalDeleted),
		zap.Int("total_failed", totalFailed),
	)
}

// hardDeleteUser performs the actual hard delete of a user record
func (w *ComplianceWorker) hardDeleteUser(ctx context.Context, deletion *entity.UserDeletion) error {
	tx, err := w.db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback(ctx)

	// Hard delete user_profiles (addresses & preferences already deleted in Stage 1)
	if err := w.profileRepo.DeleteTx(ctx, tx, deletion.UserID); err != nil {
		return fmt.Errorf("failed to delete profile: %w", err)
	}

	// Delete the deletion record from user_deletions
	if err := w.deletionRepo.DeleteTx(ctx, tx, deletion.ID); err != nil {
		return fmt.Errorf("failed to delete deletion record: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	return nil
}

// Stats returns statistics about pending and completed deletions
func (w *ComplianceWorker) Stats(ctx context.Context) (total, pending int64, err error) {
	total, err = w.deletionRepo.Count(ctx)
	if err != nil {
		return 0, 0, fmt.Errorf("failed to count total deletions: %w", err)
	}

	pending, err = w.deletionRepo.CountPending(ctx)
	if err != nil {
		return 0, 0, fmt.Errorf("failed to count pending deletions: %w", err)
	}

	return total, pending, nil
}

// main entry point for the compliance worker
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
		fx.Invoke(func(lc fx.Lifecycle, worker *ComplianceWorker, logger *zap.Logger) {
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

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
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

func NewPostgresPool(logger *zap.Logger, cfg *Config) (*pgxpool.Pool, error) {
	dsn := fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=%s",
		cfg.DB.User,
		cfg.DB.Password,
		cfg.DB.Host,
		cfg.DB.Port,
		cfg.DB.Database,
		cfg.DB.SSLMode,
	)

	poolConfig, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to parse database config: %w", err)
	}

	poolConfig.MaxConns = 10
	poolConfig.MinConns = 2

	pool, err := pgxpool.NewWithConfig(context.Background(), poolConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create connection pool: %w", err)
	}

	logger.Info("PostgreSQL connection pool created")
	return pool, nil
}

func NewProfileRepository(db *pgxpool.Pool) port.ProfileRepository {
	return postgres.NewPostgresProfileRepository(db)
}

func NewUserDeletionRepository(db *pgxpool.Pool) port.UserDeletionRepository {
	return postgres.NewPostgresUserDeletionRepository(db)
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

// StatsHandler returns HTTP handler for viewing deletion statistics
type StatsHandler struct {
	worker *ComplianceWorker
}

func NewStatsHandler(worker *ComplianceWorker) *StatsHandler {
	return &StatsHandler{worker: worker}
}

func (h *StatsHandler) Handle(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()

	total, pending, err := h.worker.Stats(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"total_deletions":    total,
		"pending_hard_deletes": pending,
	})
}
