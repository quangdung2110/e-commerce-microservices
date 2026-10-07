package service

import (
	"context"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// ReconciliationResult is one sweep of the projection reconciliation worker.
type ReconciliationResult struct {
	Scanned   int
	Published int
	Failed    int
}

// ReconciliationService implements WF-USER-008 and ADR-007: because User
// publishes directly after commit and keeps no outbox, an exhausted retry can
// leave Chat or Notification behind. Once a day the service walks the current
// state of every active user and republishes the two contract events as
// corrective snapshots.
//
// Each snapshot gets a fresh CloudEvent id, because it is a new statement of
// current state rather than a redelivery, and keeps the `updated_at` stored in
// PostgreSQL so a consumer can drop it when it already holds something newer.
// USER_DELETED is never republished: Auth owns that fact (ADR-006).
type ReconciliationService struct {
	projections port.ProjectionRepository
	emitter     *EventEmitter
	logger      *zap.Logger
}

// NewReconciliationService wires the reconciliation sweep.
func NewReconciliationService(projections port.ProjectionRepository, emitter *EventEmitter, logger *zap.Logger) *ReconciliationService {
	return &ReconciliationService{projections: projections, emitter: emitter, logger: logger}
}

// Reconcile walks every active user in batches and republishes their current
// snapshot.
//
// The cursor lives only for the duration of the run: no checkpoint table is
// created, so an interrupted sweep is simply repeated by the next run. batchGap
// throttles the walk so a full sweep cannot saturate PostgreSQL or Kafka.
func (s *ReconciliationService) Reconcile(ctx context.Context, batchSize int, batchGap time.Duration) (ReconciliationResult, error) {
	if batchSize <= 0 {
		batchSize = defaultReconciliationBatch
	}

	var (
		result ReconciliationResult
		cursor uuid.UUID
	)
	for {
		if err := ctx.Err(); err != nil {
			return result, err
		}

		batch, err := s.projections.ListActive(ctx, cursor, batchSize)
		if err != nil {
			return result, err
		}
		if len(batch) == 0 {
			return result, nil
		}

		for _, snapshot := range batch {
			result.Scanned++
			cursor = snapshot.UserID

			// A corrective snapshot carries no request correlation: it belongs
			// to the sweep, not to the original business flow.
			profileEvent := event.NewProfileUpdated(
				snapshot.UserID, snapshot.SenderName, snapshot.ProfileUpdatedAt, event.Metadata{})
			if s.emitter.Emit(ctx, profileEvent) {
				result.Published++
			} else {
				result.Failed++
			}

			if snapshot.Preferences == nil {
				continue
			}
			preferencesEvent := event.NewPreferencesUpdated(
				snapshot.UserID,
				snapshot.Preferences.EmailEnabled,
				snapshot.Preferences.InAppEnabled,
				snapshot.Preferences.MarketingEnabled,
				snapshot.Preferences.UpdatedAt,
				event.Metadata{},
			)
			if s.emitter.Emit(ctx, preferencesEvent) {
				result.Published++
			} else {
				result.Failed++
			}
		}

		if len(batch) < batchSize {
			return result, nil
		}
		if batchGap > 0 {
			timer := time.NewTimer(batchGap)
			select {
			case <-ctx.Done():
				timer.Stop()
				return result, ctx.Err()
			case <-timer.C:
			}
		}
	}
}

// defaultReconciliationBatch keeps one query small enough to stay off the slow
// query log even on a large table.
const defaultReconciliationBatch = 500
