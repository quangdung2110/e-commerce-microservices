package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

func TestProfileGetReturnsNotFoundForUnknownUser(t *testing.T) {
	h := newHarness(t)

	_, err := h.profileService.Get(context.Background(), uuid.New())

	// A profile that was never bootstrapped and one the retention worker purged
	// are indistinguishable to a client, on purpose.
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
}

func TestProfileGetReturnsNotFoundOnceTheAccountIsInItsRetentionWindow(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)
	h.deletions.deletions[uuid.New()] = entity.Deletion{UserID: userID}

	_, err := h.profileService.Get(context.Background(), userID)

	require.ErrorIs(t, err, apperr.ErrProfileNotFound,
		"the anonymized row is kept for the purge, not for the owner")
}

func TestProfileUpdateStoresNamesAndPublishesDisplayName(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	profile, err := h.profileService.Update(context.Background(), userID, entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    stringPtr("Nguyen"),
		SetLastName:  true,
		LastName:     stringPtr("Van A"),
		SetGender:    true,
		Gender:       vo.GenderMale,
	})
	require.NoError(t, err)
	require.Equal(t, "Nguyen Van A", profile.DisplayName())

	events := h.publisher.events()
	require.Len(t, events, 1)
	require.Equal(t, event.TypeUserProfileUpdated, events[0].Type)
	require.Equal(t, userID.String(), events[0].Key, "the partition key is the user id")

	data, ok := events[0].Data.(event.ProfileUpdatedData)
	require.True(t, ok)
	require.Equal(t, "Nguyen Van A", data.SenderName)
	// The payload carries the derived name only: no date of birth, gender,
	// phone number or address ever reaches Kafka.
	require.Equal(t, userID.String(), data.UserID)
}

func TestProfileUpdateWithoutChangesWritesNothing(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.profileService.Update(context.Background(), userID, entity.ProfileChanges{
		SetGender: true,
		Gender:    vo.GenderUnknown,
	})
	require.NoError(t, err)

	// Repeating a patch that asks for the state the profile already has is a
	// no-op: no write, and no event that would move a consumer's updated_at.
	require.Zero(t, h.profiles.updates)
	require.Empty(t, h.publisher.events())
}

func TestProfileUpdateClearsNameWithExplicitNull(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.profileService.Update(context.Background(), userID, entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    stringPtr("Nguyen"),
	})
	require.NoError(t, err)

	profile, err := h.profileService.Update(context.Background(), userID, entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    nil, // merge-patch null removes the member
	})
	require.NoError(t, err)
	require.Nil(t, profile.FirstName)
	require.Equal(t, constants.DisplayNameFallback, profile.DisplayName(),
		"a nameless profile still needs a non-empty display name")
}

func TestProfileUpdateRejectsFutureDateOfBirth(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	tomorrow := testNow.Add(48 * time.Hour)
	_, err := h.profileService.Update(context.Background(), userID, entity.ProfileChanges{
		SetDateOfBirth: true,
		DateOfBirth:    &tomorrow,
	})

	require.ErrorIs(t, err, apperr.ErrInvalidProfileField)
	require.Empty(t, h.publisher.events())
}

func TestProfileUpdateRejectsBlankName(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.profileService.Update(context.Background(), userID, entity.ProfileChanges{
		SetLastName: true,
		LastName:    stringPtr("   "),
	})

	// The schema uses minLength 1, so clearing a name is done with null.
	require.ErrorIs(t, err, apperr.ErrInvalidProfileField)
}

func TestProfileUpdateSurvivesPublishFailure(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)
	h.publisher.failWith = apperr.ErrDependencyUnavailable

	profile, err := h.profileService.Update(context.Background(), userID, entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    stringPtr("Nguyen"),
	})

	// ADR-004: the transaction already committed, so a broker outage must not
	// tell the client the update failed. The daily sweep closes the gap.
	require.NoError(t, err)
	require.NotNil(t, profile.FirstName)

	stored, ok := h.profiles.get(userID)
	require.True(t, ok)
	require.Equal(t, "Nguyen", *stored.FirstName)
}
