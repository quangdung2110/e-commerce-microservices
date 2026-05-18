package compliance

import (
	"context"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"user-service/internal/domain/entity"
	"user-service/internal/domain/port"
)

// Worker implements the GDPR Hard Delete worker (Stage 2)
// This worker runs on a schedule to hard delete user records after the retention window expires
type Worker struct {
	db           *pgxpool.Pool
	profileRepo  port.ProfileRepository
	deletionRepo port.UserDeletionRepository
	logger       *zap.Logger
}

// NewWorker creates a new ComplianceWorker
func NewWorker(
	db *pgxpool.Pool,
	profileRepo port.ProfileRepository,
	deletionRepo port.UserDeletionRepository,
	logger *zap.Logger,
) *Worker {
	return &Worker{
		db:           db,
		profileRepo:  profileRepo,
		deletionRepo: deletionRepo,
		logger:       logger,
	}
}

// Start schedules and starts the compliance worker
func (w *Worker) Start() error {
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
func (w *Worker) RunNow(ctx context.Context) error {
	w.runHardDelete()
	return nil
}

// runHardDelete processes all pending hard deletes
func (w *Worker) runHardDelete() {
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
func (w *Worker) hardDeleteUser(ctx context.Context, deletion *entity.UserDeletion) error {
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
func (w *Worker) Stats(ctx context.Context) (total, pending int64, err error) {
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

// StatsHandler returns HTTP handler for viewing deletion statistics
type StatsHandler struct {
	worker *Worker
}

func NewStatsHandler(worker *Worker) *StatsHandler {
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
		"total_deletions":       total,
		"pending_hard_deletes": pending,
	})
}
