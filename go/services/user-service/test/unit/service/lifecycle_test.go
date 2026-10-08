package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

// retentionWindow is the 90-day window Auth publishes with USER_DELETED.
const retentionWindow = 90 * 24 * time.Hour

func TestBootstrapCreatesProfileAndDefaultPreferences(t *testing.T) {
	h := newHarness(t)
	userID := uuid.New()

	result, err := h.lifecycleService.Bootstrap(context.Background(), userID, testNow)
	require.NoError(t, err)
	require.True(t, result.ProfileCreated)
	require.True(t, result.PreferencesCreated)

	profile, ok := h.profiles.get(userID)
	require.True(t, ok)
	require.Equal(t, userID, profile.ID, "USER-INV-006: the id is the one Auth published")
	require.Nil(t, profile.FirstName, "the profile starts empty")
	require.Equal(t, vo.GenderUnknown, profile.Gender)

	preferences, err := h.preferences.GetByUser(context.Background(), userID)
	require.NoError(t, err)
	require.True(t, preferences.EmailEnabled)
	require.True(t, preferences.InAppEnabled)
	require.False(t, preferences.MarketingEnabled, "marketing is opt-in, like Notification's default")
	require.Equal(t, vo.LanguageVietnamese, preferences.Language)
	require.Equal(t, vo.CurrencyVND, preferences.Currency)
}

func TestBootstrapIsIdempotentAndKeepsOwnerEdits(t *testing.T) {
	h := newHarness(t)
	userID := uuid.New()

	_, err := h.lifecycleService.Bootstrap(context.Background(), userID, testNow)
	require.NoError(t, err)

	// The owner edits the profile between the two deliveries.
	edited, ok := h.profiles.get(userID)
	require.True(t, ok)
	edited.FirstName = stringPtr("Nguyen")
	require.NoError(t, h.profiles.Update(context.Background(), &edited))

	result, err := h.lifecycleService.Bootstrap(context.Background(), userID, testNow)
	require.NoError(t, err)
	require.False(t, result.ProfileCreated, "a redelivery creates nothing")
	require.False(t, result.PreferencesCreated)

	stored, ok := h.profiles.get(userID)
	require.True(t, ok)
	require.NotNil(t, stored.FirstName, "USER-BR-005: the edit survives the replay")
	require.Equal(t, "Nguyen", *stored.FirstName)
}

func TestBootstrapIsRefusedAfterDeletion(t *testing.T) {
	h := newHarness(t)
	userID := uuid.New()

	_, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, testNow.Add(retentionWindow), vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	result, err := h.lifecycleService.Bootstrap(context.Background(), userID, testNow)
	require.NoError(t, err)
	require.True(t, result.SkippedDeleted, "deletion is terminal")
	require.False(t, result.ProfileCreated)

	_, ok := h.profiles.get(userID)
	require.False(t, ok, "a late verification event must not resurrect the account")
}

func TestAnonymizeScrubsProfileAndRemovesOwnedData(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.profileService.Update(context.Background(), userID, mustNameChanges())
	require.NoError(t, err)
	_, err = h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)

	hardDeleteAfter := testNow.Add(retentionWindow)
	result, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, hardDeleteAfter, vo.DeletionGDPRRequest.String())
	require.NoError(t, err)
	require.True(t, result.ProfileAnonymized)
	require.Equal(t, int64(1), result.AddressesDeleted)
	require.True(t, result.PreferencesDeleted)

	profile, ok := h.profiles.get(userID)
	require.True(t, ok, "the row survives until the retention worker purges it")
	require.True(t, profile.IsAnonymized(), "USER-INV-005: the PII is gone immediately")
	require.Zero(t, h.addresses.count())

	_, err = h.preferences.GetByUser(context.Background(), userID)
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)

	// The cached copy of the address book is dropped with the data itself.
	_, cached := h.cache.cached(userID)
	require.False(t, cached)

	// Auth owns the deletion fact: User republishes nothing (ADR-006).
	require.NotContains(t, h.publisher.typesPublished(), "com.ecommerce.auth.user-deleted.v1")
}

func TestAnonymizeSerializesWithAddressMutations(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	before := h.addresses.locks
	_, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, testNow.Add(retentionWindow), vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	// A create that already passed its "profile is active" check must finish
	// before the address book is emptied, or its row would outlive the deletion.
	require.Equal(t, before+1, h.addresses.locks, "the anonymization takes the per-user address lock")
}

func TestDeletedAccountCannotReadOrWriteItsProfileAgain(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.profileService.Update(context.Background(), userID, mustNameChanges())
	require.NoError(t, err)
	_, err = h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	publishedBefore := len(h.publisher.events())

	_, err = h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, testNow.Add(retentionWindow), vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	// The anonymized row survives until the purge, but no owner-facing path may
	// reach it: it reads as a profile that does not exist (USER-INV-005).
	_, err = h.profileService.Get(context.Background(), userID)
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)

	_, err = h.profileService.Update(context.Background(), userID, mustNameChanges())
	require.ErrorIs(t, err, apperr.ErrProfileNotFound, "a late PATCH must not write PII back")

	_, err = h.addressService.Create(context.Background(), userID, validAddress())
	require.ErrorIs(t, err, apperr.ErrProfileNotFound, "a late POST must not create an address")

	_, err = h.addressService.List(context.Background(), userID)
	require.ErrorIs(t, err, apperr.ErrProfileNotFound, "an empty book is not a 200 for a deleted account")

	_, err = h.preferenceService.Get(context.Background(), userID)
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)

	stored, ok := h.profiles.get(userID)
	require.True(t, ok)
	require.True(t, stored.IsAnonymized(), "the row stayed scrubbed")
	require.Zero(t, h.addresses.count())
	require.Len(t, h.publisher.events(), publishedBefore, "no USER_PROFILE_UPDATED for a deleted account")
}

func TestDuplicateDeletionKeepsTheOriginalDeadline(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	firstDeadline := testNow.Add(retentionWindow)
	_, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, firstDeadline, vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	result, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow.Add(time.Hour), testNow.Add(2*retentionWindow), vo.DeletionGDPRRequest.String())
	require.NoError(t, err)
	require.True(t, result.AlreadyProcessed, "USER-BR-006: a duplicate is a no-op")
	require.Equal(t, 1, h.deletions.count())

	due, err := h.deletions.ListDue(context.Background(), firstDeadline, 10)
	require.NoError(t, err)
	require.Len(t, due, 1, "the deadline was not extended by the duplicate")
}

func TestAnonymizeAcceptsADeletionThatArrivesBeforeTheProfile(t *testing.T) {
	h := newHarness(t)
	userID := uuid.New()

	result, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, testNow.Add(retentionWindow), vo.DeletionAdminDeletion.String())

	require.NoError(t, err)
	require.False(t, result.ProfileAnonymized, "there is nothing to scrub yet")
	require.Equal(t, 1, h.deletions.count(), "the retention record alone keeps deletion terminal")
}

func TestAnonymizeRejectsAnUnsupportedReason(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, testNow.Add(retentionWindow), "SPRING_CLEANING")

	require.ErrorIs(t, err, apperr.ErrValidation)
	require.Zero(t, h.deletions.count())
}

func TestPurgeSkipsRecordsBeforeTheirDeadline(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, testNow.Add(retentionWindow), vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	result, err := h.lifecycleService.PurgeDue(context.Background(), 100)
	require.NoError(t, err)
	require.Zero(t, result.Due, "USER-BR-007: nothing is deleted early")

	_, ok := h.profiles.get(userID)
	require.True(t, ok)
}

func TestPurgeRemovesProfileAndRecordOnceDue(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	hardDeleteAfter := testNow.Add(retentionWindow)
	_, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, hardDeleteAfter, vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	h.clock.Set(hardDeleteAfter.Add(time.Minute))
	result, err := h.lifecycleService.PurgeDue(context.Background(), 100)
	require.NoError(t, err)
	require.Equal(t, 1, result.Due)
	require.Equal(t, 1, result.Purged)
	require.Equal(t, hardDeleteAfter, result.OldestDue)

	_, ok := h.profiles.get(userID)
	require.False(t, ok, "the profile is hard-deleted")
	require.Zero(t, h.deletions.count(), "the retention record goes with it")

	// A second pass finds nothing left to do.
	again, err := h.lifecycleService.PurgeDue(context.Background(), 100)
	require.NoError(t, err)
	require.Zero(t, again.Due)
}

func TestPurgeSkipsARecordClaimedElsewhere(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	hardDeleteAfter := testNow.Add(retentionWindow)
	_, err := h.lifecycleService.Anonymize(context.Background(), userID,
		testNow, hardDeleteAfter, vo.DeletionGDPRRequest.String())
	require.NoError(t, err)

	// Simulate another replica holding the row with FOR UPDATE SKIP LOCKED.
	for id := range h.deletions.deletions {
		h.deletions.claimed[id] = true
	}

	h.clock.Set(hardDeleteAfter.Add(time.Minute))
	result, err := h.lifecycleService.PurgeDue(context.Background(), 100)
	require.NoError(t, err)
	require.Equal(t, 1, result.Due)
	require.Equal(t, 1, result.Skipped, "a locked record is left for its holder")
	require.Zero(t, result.Purged)

	_, ok := h.profiles.get(userID)
	require.True(t, ok)
}
