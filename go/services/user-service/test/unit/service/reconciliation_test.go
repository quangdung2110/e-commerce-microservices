package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/common/constants"
	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/port"
)

func TestReconcilePublishesTheCurrentSnapshotOfEveryActiveUser(t *testing.T) {
	h := newHarness(t)
	profileUpdatedAt := testNow.Add(-72 * time.Hour)
	preferencesUpdatedAt := testNow.Add(-48 * time.Hour)
	userID := uuid.New()

	h.projections.snapshots = []port.ProjectionSnapshot{{
		UserID:           userID,
		SenderName:       "Nguyen Van A",
		ProfileUpdatedAt: profileUpdatedAt,
		Preferences: &port.PreferencesSnapshot{
			EmailEnabled:     true,
			InAppEnabled:     false,
			MarketingEnabled: false,
			UpdatedAt:        preferencesUpdatedAt,
		},
	}}

	result, err := h.reconcileService.Reconcile(context.Background(), 100, 0)
	require.NoError(t, err)
	require.Equal(t, 1, result.Scanned)
	require.Equal(t, 2, result.Published, "one profile event and one preferences event")
	require.Zero(t, result.Failed)

	events := h.publisher.events()
	require.Len(t, events, 2)
	require.Equal(t, event.TypeUserProfileUpdated, events[0].Type)
	require.Equal(t, event.TypeUserPreferencesUpdated, events[1].Type)

	// ADR-007: a corrective snapshot keeps the stored updated_at, so a consumer
	// that already holds something newer can drop it.
	profileData, ok := events[0].Data.(event.ProfileUpdatedData)
	require.True(t, ok)
	require.Equal(t, event.FormatTime(profileUpdatedAt), profileData.UpdatedAt)

	preferencesData, ok := events[1].Data.(event.PreferencesUpdatedData)
	require.True(t, ok)
	require.Equal(t, event.FormatTime(preferencesUpdatedAt), preferencesData.UpdatedAt)

	// It is a new statement of current state, not a redelivery, so it carries a
	// fresh CloudEvent id.
	require.NotEqual(t, events[0].ID, events[1].ID)
	require.NotEmpty(t, events[0].ID)
}

func TestReconcileUsesTheFallbackNameForANamelessProfile(t *testing.T) {
	h := newHarness(t)
	h.projections.snapshots = []port.ProjectionSnapshot{{
		UserID:           uuid.New(),
		SenderName:       constants.DisplayNameFallback,
		ProfileUpdatedAt: testNow,
	}}

	result, err := h.reconcileService.Reconcile(context.Background(), 100, 0)
	require.NoError(t, err)
	require.Equal(t, 1, result.Published, "a user without preferences still gets its name reconciled")

	data, ok := h.publisher.events()[0].Data.(event.ProfileUpdatedData)
	require.True(t, ok)
	require.Equal(t, constants.DisplayNameFallback, data.SenderName,
		"sender_name must never be empty")
}

func TestReconcileWalksEveryBatch(t *testing.T) {
	h := newHarness(t)
	for i := 0; i < 5; i++ {
		h.projections.snapshots = append(h.projections.snapshots, port.ProjectionSnapshot{
			UserID:           uuid.New(),
			SenderName:       constants.DisplayNameFallback,
			ProfileUpdatedAt: testNow,
		})
	}

	result, err := h.reconcileService.Reconcile(context.Background(), 2, 0)

	// The cursor lives only for the run: no checkpoint table is involved.
	require.NoError(t, err)
	require.Equal(t, 5, result.Scanned)
	require.Equal(t, 5, result.Published)
}

func TestReconcileCountsPublishFailures(t *testing.T) {
	h := newHarness(t)
	h.projections.snapshots = []port.ProjectionSnapshot{{
		UserID:           uuid.New(),
		SenderName:       "Nguyen Van A",
		ProfileUpdatedAt: testNow,
	}}
	h.publisher.failWith = apperr.ErrDependencyUnavailable

	result, err := h.reconcileService.Reconcile(context.Background(), 100, 0)

	// A broker outage does not abort the sweep: the failure is counted so the
	// reconciliation alert can fire, and the next run tries again.
	require.NoError(t, err)
	require.Equal(t, 1, result.Scanned)
	require.Equal(t, 1, result.Failed)
	require.Zero(t, result.Published)
}
