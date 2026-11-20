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
		MarketingEnabled: false,
	}
	_, err := h.preferenceService.Replace(context.Background(), userID, unchanged)
	require.NoError(t, err)

	require.Zero(t, h.preferences.updates, "the defaults already say this")
	require.Empty(t, h.publisher.events(),
		"an unchanged PUT must not move updated_at for a consumer")
}

func optIn(t *testing.T, h *harness, userID uuid.UUID) {
	t.Helper()

	_, err := h.preferenceService.Replace(context.Background(), userID, entity.NotificationChanges{
		EmailEnabled:     false,
		InAppEnabled:     true,
		MarketingEnabled: true,
	})
	require.NoError(t, err)
}

func TestComplaintSwitchesMarketingOffAndPublishesIt(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)
	optIn(t, h, userID)

	result, err := h.preferenceService.DisableMarketing(context.Background(), userID)
	require.NoError(t, err)
	require.True(t, result.Disabled)

	stored, err := h.preferences.GetByUser(context.Background(), userID)
	require.NoError(t, err)
	require.False(t, stored.MarketingEnabled, "the source of truth now says marketing is off")
	require.False(t, stored.EmailEnabled, "the owner's other choices are left alone")
	require.True(t, stored.InAppEnabled)

	events := h.publisher.events()
	require.Len(t, events, 2, "the opt-in and the complaint each published a snapshot")
	data, ok := events[1].Data.(event.PreferencesUpdatedData)
	require.True(t, ok)
	require.Equal(t, event.TypeUserPreferencesUpdated, events[1].Type)
	require.False(t, data.MarketingEnabled)
	require.False(t, data.EmailEnabled)
	require.True(t, data.InAppEnabled)
}

func TestComplaintIsIdempotent(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)
	optIn(t, h, userID)

	_, err := h.preferenceService.DisableMarketing(context.Background(), userID)
	require.NoError(t, err)
	published := len(h.publisher.events())

	again, err := h.preferenceService.DisableMarketing(context.Background(), userID)
	require.NoError(t, err)
	require.False(t, again.Disabled, "a redelivered complaint changes nothing")
	require.Len(t, h.publisher.events(), published, "and publishes nothing")
}

func TestComplaintFromAUserWhoNeverOptedInPublishesNothing(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	result, err := h.preferenceService.DisableMarketing(context.Background(), userID)

	require.NoError(t, err)
	require.False(t, result.Disabled, "marketing was already off by default")
	require.Empty(t, h.publisher.events())
}

func TestComplaintForAnUnknownOrDeletedUserIsAcknowledged(t *testing.T) {
	h := newHarness(t)

	result, err := h.preferenceService.DisableMarketing(context.Background(), uuid.New())

	// Not an error: retrying would never create the preferences row, and
	// dead-lettering a complaint for a purged user helps nobody.
	require.NoError(t, err)
	require.False(t, result.Disabled)
	require.Empty(t, h.publisher.events())
}

func TestPreferencesForAMissingProfileAre404(t *testing.T) {
	h := newHarness(t)

	_, err := h.preferenceService.Get(context.Background(), uuid.New())
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)

	_, err = h.preferenceService.Replace(context.Background(), uuid.New(), entity.NotificationChanges{})
	// WF-USER-005 prefers 404 over silently recreating a deleted account.
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
}
