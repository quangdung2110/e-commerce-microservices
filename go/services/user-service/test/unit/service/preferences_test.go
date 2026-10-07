package service_test

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

func TestPreferencesReplaceWritesFlagsAndPublishesThem(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	preferences, err := h.preferenceService.Replace(context.Background(), userID, entity.NotificationChanges{
		EmailEnabled:     true,
		InAppEnabled:     false,
		MarketingEnabled: false,
	})
	require.NoError(t, err)
	require.False(t, preferences.MarketingEnabled)
	require.Equal(t, vo.LanguageVietnamese, preferences.Language, "language stays read-only")
	require.Equal(t, vo.CurrencyVND, preferences.Currency, "currency stays read-only")

	events := h.publisher.events()
	require.Len(t, events, 1)
	require.Equal(t, event.TypeUserPreferencesUpdated, events[0].Type)

	data, ok := events[0].Data.(event.PreferencesUpdatedData)
	require.True(t, ok)
	require.True(t, data.EmailEnabled)
	require.False(t, data.InAppEnabled)
	require.False(t, data.MarketingEnabled)
	// Only the three replicated flags travel; language and currency do not.
	require.Equal(t, userID.String(), data.UserID)
}

func TestPreferencesReplaceWithTheSameBodyIsANoOp(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	unchanged := entity.NotificationChanges{
		EmailEnabled:     true,
		InAppEnabled:     true,
		MarketingEnabled: true,
	}
	_, err := h.preferenceService.Replace(context.Background(), userID, unchanged)
	require.NoError(t, err)

	require.Zero(t, h.preferences.updates, "the defaults already say this")
	require.Empty(t, h.publisher.events(),
		"an unchanged PUT must not move updated_at for a consumer")
}

func TestPreferencesForAMissingProfileAre404(t *testing.T) {
	h := newHarness(t)

	_, err := h.preferenceService.Get(context.Background(), uuid.New())
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)

	_, err = h.preferenceService.Replace(context.Background(), uuid.New(), entity.NotificationChanges{})
	// WF-USER-005 prefers 404 over silently recreating a deleted account.
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
}
