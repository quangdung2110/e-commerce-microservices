// Package entity_test covers the rules the aggregates enforce on their own,
// without any adapter or use case around them.
package entity_test

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

var testNow = time.Date(2026, 8, 28, 12, 0, 0, 0, time.UTC)

func namePtr(value string) *string { return &value }

func TestNewProfileStartsEmpty(t *testing.T) {
	userID := uuid.New()

	profile := entity.NewProfile(userID, testNow)

	require.Equal(t, userID, profile.ID)
	require.Nil(t, profile.FirstName)
	require.Nil(t, profile.LastName)
	require.Nil(t, profile.DateOfBirth)
	require.Equal(t, vo.GenderUnknown, profile.Gender)
}

func TestDisplayNameJoinsTheParts(t *testing.T) {
	profile := entity.NewProfile(uuid.New(), testNow)

	profile.FirstName = namePtr("Nguyen")
	require.Equal(t, "Nguyen", profile.DisplayName())

	profile.LastName = namePtr("Van A")
	require.Equal(t, "Nguyen Van A", profile.DisplayName())
}

func TestDisplayNameFallsBackForANamelessProfile(t *testing.T) {
	profile := entity.NewProfile(uuid.New(), testNow)

	// `sender_name` has minLength 1 in the event schema and `display_name` in
	// the HTTP schema, so an empty profile still needs a name.
	require.Equal(t, constants.DisplayNameFallback, profile.DisplayName())
}

func TestDisplayNameStaysWithinTheContractBound(t *testing.T) {
	profile := entity.NewProfile(uuid.New(), testNow)
	profile.FirstName = namePtr(strings.Repeat("a", 100))
	profile.LastName = namePtr(strings.Repeat("b", 100))

	require.LessOrEqual(t, len([]rune(profile.DisplayName())), constants.MaxDisplayNameLength)
}

func TestApplyRejectsAnOversizedName(t *testing.T) {
	profile := entity.NewProfile(uuid.New(), testNow)

	changed, err := profile.Apply(entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    namePtr(strings.Repeat("a", 101)),
	}, testNow)

	require.ErrorIs(t, err, apperr.ErrInvalidProfileField)
	require.False(t, changed)
}

func TestApplyAcceptsTodayAsDateOfBirth(t *testing.T) {
	profile := entity.NewProfile(uuid.New(), testNow)
	today := testNow

	changed, err := profile.Apply(entity.ProfileChanges{
		SetDateOfBirth: true,
		DateOfBirth:    &today,
	}, testNow)

	require.NoError(t, err)
	require.True(t, changed)
	require.NotNil(t, profile.DateOfBirth)
}

func TestApplyReportsEveryInvalidFieldAtOnce(t *testing.T) {
	profile := entity.NewProfile(uuid.New(), testNow)
	future := testNow.Add(48 * time.Hour)

	_, err := profile.Apply(entity.ProfileChanges{
		SetFirstName:   true,
		FirstName:      namePtr(" "),
		SetDateOfBirth: true,
		DateOfBirth:    &future,
		SetGender:      true,
		Gender:         vo.Gender("ROBOT"),
	}, testNow)

	appErr := apperr.From(err)
	require.Len(t, appErr.Fields, 3)
}

func TestAnonymizeClearsEveryIdentifyingField(t *testing.T) {
	profile := entity.NewProfile(uuid.New(), testNow)
	birthday := testNow.Add(-30 * 365 * 24 * time.Hour)
	_, err := profile.Apply(entity.ProfileChanges{
		SetFirstName:   true,
		FirstName:      namePtr("Nguyen"),
		SetLastName:    true,
		LastName:       namePtr("Van A"),
		SetDateOfBirth: true,
		DateOfBirth:    &birthday,
		SetGender:      true,
		Gender:         vo.GenderMale,
	}, testNow)
	require.NoError(t, err)

	profile.Anonymize(testNow)

	require.True(t, profile.IsAnonymized())
	require.Equal(t, constants.DisplayNameFallback, profile.DisplayName())
}

func TestAddressValidationAcceptsAWellFormedPayload(t *testing.T) {
	address, err := entity.NewAddress(uuid.New(), entity.AddressInput{
		RecipientName:  "  Nguyen Van A  ",
		RecipientPhone: "+84901234567",
		AddressLine:    " 12 Nguyen Hue ",
		ProvinceCode:   "79",
		DistrictCode:   "760",
		WardCode:       "26734",
	}, true, testNow)

	require.NoError(t, err)
	require.Equal(t, "Nguyen Van A", address.RecipientName, "values are trimmed")
	require.Equal(t, "12 Nguyen Hue", address.AddressLine)
	require.True(t, address.IsDefault)
}

func TestAddressRejectsANonE164Phone(t *testing.T) {
	for _, phone := range []string{"0901234567", "+0901234567", "+84", "84901234567", ""} {
		_, err := entity.NewAddress(uuid.New(), entity.AddressInput{
			RecipientName:  "Nguyen Van A",
			RecipientPhone: phone,
			AddressLine:    "12 Nguyen Hue",
			ProvinceCode:   "79",
			DistrictCode:   "760",
			WardCode:       "26734",
		}, false, testNow)

		require.ErrorIs(t, err, apperr.ErrInvalidAddress, "phone %q must be rejected", phone)
	}
}

func TestAddressNormalizesAHumanTypedPhone(t *testing.T) {
	address, err := entity.NewAddress(uuid.New(), entity.AddressInput{
		RecipientName:  "Nguyen Van A",
		RecipientPhone: "0084 (901) 234-567",
		AddressLine:    "12 Nguyen Hue",
		ProvinceCode:   "79",
		DistrictCode:   "760",
		WardCode:       "26734",
	}, false, testNow)

	require.NoError(t, err)
	require.Equal(t, "+84901234567", address.RecipientPhone.String())
}

func TestDefaultAddressRefusesDeletionWhileOthersExist(t *testing.T) {
	address, err := entity.NewAddress(uuid.New(), entity.AddressInput{
		RecipientName:  "Nguyen Van A",
		RecipientPhone: "+84901234567",
		AddressLine:    "12 Nguyen Hue",
		ProvinceCode:   "79",
		DistrictCode:   "760",
		WardCode:       "26734",
	}, true, testNow)
	require.NoError(t, err)

	require.ErrorIs(t, address.EnsureDeletable(2), apperr.ErrDefaultAddressCannotBeDeleted)
	require.NoError(t, address.EnsureDeletable(1),
		"the only address has no replacement to promote, so it may go")

	address.IsDefault = false
	require.NoError(t, address.EnsureDeletable(2))
}

func TestNewPreferencesAreOptedOutOfMarketing(t *testing.T) {
	preferences := entity.NewDefaultPreferences(uuid.New(), testNow)

	require.True(t, preferences.EmailEnabled)
	require.True(t, preferences.InAppEnabled)
	require.False(t, preferences.MarketingEnabled,
		"marketing is opt-in: the default must match Notification's no-row default")
}

func TestPreferencesReplaceReportsWhetherAnythingChanged(t *testing.T) {
	preferences := entity.NewDefaultPreferences(uuid.New(), testNow)

	require.False(t, preferences.Replace(entity.NotificationChanges{
		EmailEnabled:     true,
		InAppEnabled:     true,
		MarketingEnabled: false,
	}, testNow), "the defaults already say this")

	require.True(t, preferences.Replace(entity.NotificationChanges{
		EmailEnabled:     true,
		InAppEnabled:     true,
		MarketingEnabled: true,
	}, testNow))
	require.True(t, preferences.MarketingEnabled)
}

func TestDeletionRequiresADeadlineAfterTheDeletion(t *testing.T) {
	userID := uuid.New()

	_, err := entity.NewDeletion(userID, testNow, testNow, vo.DeletionGDPRRequest, testNow)
	require.Error(t, err, "hard_delete_after must be later than deleted_at")

	_, err = entity.NewDeletion(userID, testNow, testNow.Add(time.Hour), vo.DeletionReason("OTHER"), testNow)
	require.Error(t, err, "the reason must be one the schema allows")
}

func TestDeletionIsDueOnlyAfterTheDeadline(t *testing.T) {
	deadline := testNow.Add(90 * 24 * time.Hour)
	deletion, err := entity.NewDeletion(uuid.New(), testNow, deadline, vo.DeletionGDPRRequest, testNow)
	require.NoError(t, err)

	require.False(t, deletion.IsDue(deadline.Add(-time.Second)))
	require.ErrorIs(t, deletion.EnsureDue(testNow), apperr.ErrRetentionNotExpired)

	require.True(t, deletion.IsDue(deadline), "the deadline itself is due")
	require.NoError(t, deletion.EnsureDue(deadline.Add(time.Second)))
}
