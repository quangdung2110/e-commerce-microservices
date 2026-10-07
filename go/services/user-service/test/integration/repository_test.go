//go:build integration

package integration_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/vo"
	"github.com/ecommerce/user-service/internal/infrastructure/postgres"
)

var testNow = time.Date(2026, 8, 28, 12, 0, 0, 0, time.UTC)

func seedProfile(t *testing.T) (uuid.UUID, *postgres.ProfileRepository) {
	t.Helper()

	profiles := postgres.NewProfileRepository(testPool)
	userID := uuid.New()
	created, err := profiles.Insert(context.Background(), entity.NewProfile(userID, testNow))
	require.NoError(t, err)
	require.True(t, created)
	return userID, profiles
}

func newAddress(t *testing.T, userID uuid.UUID, isDefault bool) *entity.Address {
	t.Helper()

	address, err := entity.NewAddress(userID, entity.AddressInput{
		RecipientName:  "Nguyen Van A",
		RecipientPhone: "+84901234567",
		AddressLine:    "12 Nguyen Hue",
		ProvinceCode:   "79",
		DistrictCode:   "760",
		WardCode:       "26734",
	}, isDefault, testNow)
	require.NoError(t, err)
	return address
}

func TestProfileInsertIsIdempotent(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, profiles := seedProfile(t)

	// A redelivered USER_VERIFIED_SUCCESS must not overwrite an owner's edit.
	stored, err := profiles.GetByID(ctx, userID)
	require.NoError(t, err)
	_, err = stored.Apply(entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    stringPtr("Nguyen"),
	}, testNow)
	require.NoError(t, err)
	require.NoError(t, profiles.Update(ctx, stored))

	created, err := profiles.Insert(ctx, entity.NewProfile(userID, testNow))
	require.NoError(t, err)
	require.False(t, created)

	reread, err := profiles.GetByID(ctx, userID)
	require.NoError(t, err)
	require.NotNil(t, reread.FirstName)
	require.Equal(t, "Nguyen", *reread.FirstName)
}

func TestProfileUpdateReadsBackTheStoredTimestamp(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, profiles := seedProfile(t)

	profile, err := profiles.GetByID(ctx, userID)
	require.NoError(t, err)
	before := profile.UpdatedAt

	_, err = profile.Apply(entity.ProfileChanges{SetGender: true, Gender: vo.GenderFemale}, testNow)
	require.NoError(t, err)
	require.NoError(t, profiles.Update(ctx, profile))

	// The schema trigger owns updated_at, and the published event has to carry
	// exactly what the database stored (ADR-007).
	require.True(t, profile.UpdatedAt.After(before))
	require.Equal(t, time.UTC, profile.UpdatedAt.Location())

	reread, err := profiles.GetByID(ctx, userID)
	require.NoError(t, err)
	require.Equal(t, profile.UpdatedAt, reread.UpdatedAt)
	require.Equal(t, vo.GenderFemale, reread.Gender)
}

func TestProfileGetMissingReturnsDomainError(t *testing.T) {
	resetState(t)
	profiles := postgres.NewProfileRepository(testPool)

	_, err := profiles.GetByID(context.Background(), uuid.New())

	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
}

func TestAddressRequiresAnExistingProfile(t *testing.T) {
	resetState(t)
	addresses := postgres.NewAddressRepository(testPool)

	err := addresses.Insert(context.Background(), newAddress(t, uuid.New(), false))

	// The foreign key is mapped to the domain error the API returns.
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
}

func TestOnlyOneDefaultAddressPerUser(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, _ := seedProfile(t)
	addresses := postgres.NewAddressRepository(testPool)

	require.NoError(t, addresses.Insert(ctx, newAddress(t, userID, true)))

	err := addresses.Insert(ctx, newAddress(t, userID, true))

	// USER-BR-003: the partial unique index is the last guard behind the lock.
	require.ErrorIs(t, err, apperr.ErrDefaultAddressConflict)
}

func TestAnotherUserMayHaveTheirOwnDefault(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	first, _ := seedProfile(t)
	second, _ := seedProfile(t)
	addresses := postgres.NewAddressRepository(testPool)

	require.NoError(t, addresses.Insert(ctx, newAddress(t, first, true)))
	require.NoError(t, addresses.Insert(ctx, newAddress(t, second, true)))
}

func TestAddressListIsOrderedForCheckout(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, _ := seedProfile(t)
	addresses := postgres.NewAddressRepository(testPool)

	older := newAddress(t, userID, false)
	older.CreatedAt = testNow.Add(-2 * time.Hour)
	newer := newAddress(t, userID, false)
	newer.CreatedAt = testNow.Add(-time.Hour)
	fallback := newAddress(t, userID, true)
	fallback.CreatedAt = testNow.Add(-3 * time.Hour)

	require.NoError(t, addresses.Insert(ctx, older))
	require.NoError(t, addresses.Insert(ctx, newer))
	require.NoError(t, addresses.Insert(ctx, fallback))

	list, err := addresses.ListByUser(ctx, userID)
	require.NoError(t, err)
	require.Len(t, list, 3)
	require.Equal(t, fallback.ID, list[0].ID, "default first")
	require.Equal(t, newer.ID, list[1].ID, "then newest")
	require.Equal(t, older.ID, list[2].ID)
}

func TestAddressLookupIsScopedToTheOwner(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	owner, _ := seedProfile(t)
	stranger, _ := seedProfile(t)
	addresses := postgres.NewAddressRepository(testPool)

	address := newAddress(t, owner, true)
	require.NoError(t, addresses.Insert(ctx, address))

	_, err := addresses.GetOwned(ctx, stranger, address.ID)
	require.ErrorIs(t, err, apperr.ErrAddressNotFound)

	err = addresses.Delete(ctx, stranger, address.ID)
	require.ErrorIs(t, err, apperr.ErrAddressNotFound)
}

func TestSchemaRejectsANonE164Phone(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, _ := seedProfile(t)

	// The CHECK constraint mirrors the domain rule, so a bug that bypassed the
	// value object would still not corrupt the table.
	_, err := testPool.Exec(ctx, `
		INSERT INTO "user".user_addresses (
			id, user_id, is_default, recipient_name, recipient_phone,
			address_line, province_code, district_code, ward_code
		) VALUES ($1, $2, FALSE, 'Nguyen Van A', '0901234567', '12 Nguyen Hue', '79', '760', '26734')`,
		uuid.New(), userID)

	require.Error(t, err)
}

func TestPreferencesAreOnePerProfileAndCascade(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, profiles := seedProfile(t)
	preferences := postgres.NewPreferencesRepository(testPool)

	created, err := preferences.Insert(ctx, entity.NewDefaultPreferences(userID, testNow))
	require.NoError(t, err)
	require.True(t, created)

	again, err := preferences.Insert(ctx, entity.NewDefaultPreferences(userID, testNow))
	require.NoError(t, err)
	require.False(t, again, "one preferences row per profile")

	stored, err := preferences.GetByUser(ctx, userID)
	require.NoError(t, err)
	require.Equal(t, vo.LanguageVietnamese, stored.Language)
	require.Equal(t, vo.CurrencyVND, stored.Currency)
	require.True(t, stored.MarketingEnabled)

	// Deleting the profile cascades, which is the safety net behind the purge.
	deleted, err := profiles.HardDelete(ctx, userID)
	require.NoError(t, err)
	require.True(t, deleted)

	_, err = preferences.GetByUser(ctx, userID)
	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
}

func TestPreferencesUpdateOnlyMovesUpdatedAtOnARealChange(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, _ := seedProfile(t)
	preferences := postgres.NewPreferencesRepository(testPool)

	_, err := preferences.Insert(ctx, entity.NewDefaultPreferences(userID, testNow))
	require.NoError(t, err)

	stored, err := preferences.GetByUser(ctx, userID)
	require.NoError(t, err)
	before := stored.UpdatedAt

	// Writing the same values must not move the timestamp a consumer compares.
	require.NoError(t, preferences.Update(ctx, stored))
	require.Equal(t, before, stored.UpdatedAt)

	stored.MarketingEnabled = false
	require.NoError(t, preferences.Update(ctx, stored))
	require.True(t, stored.UpdatedAt.After(before))
}

func TestDeletionIsIdempotentOnTheUser(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, _ := seedProfile(t)
	deletions := postgres.NewDeletionRepository(testPool)

	deadline := testNow.Add(90 * 24 * time.Hour)
	first, err := entity.NewDeletion(userID, testNow, deadline, vo.DeletionGDPRRequest, testNow)
	require.NoError(t, err)
	created, err := deletions.Insert(ctx, first)
	require.NoError(t, err)
	require.True(t, created)

	second, err := entity.NewDeletion(userID, testNow.Add(time.Hour), deadline.Add(30*24*time.Hour), vo.DeletionGDPRRequest, testNow)
	require.NoError(t, err)
	created, err = deletions.Insert(ctx, second)
	require.NoError(t, err)
	require.False(t, created, "USER-BR-006: the duplicate is a no-op")

	due, err := deletions.ListDue(ctx, deadline, 10)
	require.NoError(t, err)
	require.Len(t, due, 1)
	require.Equal(t, deadline, due[0].HardDeleteAfter, "the deadline was not extended")
}

func TestDeletionSurvivesTheProfileItRefers(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	userID, profiles := seedProfile(t)
	deletions := postgres.NewDeletionRepository(testPool)

	deletion, err := entity.NewDeletion(userID, testNow, testNow.Add(time.Hour), vo.DeletionAdminDeletion, testNow)
	require.NoError(t, err)
	_, err = deletions.Insert(ctx, deletion)
	require.NoError(t, err)

	_, err = profiles.HardDelete(ctx, userID)
	require.NoError(t, err)

	// The record has no foreign key on purpose: it outlives the profile row.
	exists, err := deletions.ExistsForUser(ctx, userID)
	require.NoError(t, err)
	require.True(t, exists)
}

func TestSchemaRejectsADeadlineBeforeTheDeletion(t *testing.T) {
	resetState(t)

	_, err := testPool.Exec(context.Background(), `
		INSERT INTO "user".user_deletions (id, user_id, deleted_at, hard_delete_after, reason)
		VALUES ($1, $2, $3, $3, 'GDPR_REQUEST')`,
		uuid.New(), uuid.New(), testNow)

	require.Error(t, err)
}

func TestListDueIsOrderedByDeadline(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	deletions := postgres.NewDeletionRepository(testPool)

	later, err := entity.NewDeletion(uuid.New(), testNow, testNow.Add(2*time.Hour), vo.DeletionGDPRRequest, testNow)
	require.NoError(t, err)
	sooner, err := entity.NewDeletion(uuid.New(), testNow, testNow.Add(time.Hour), vo.DeletionGDPRRequest, testNow)
	require.NoError(t, err)
	_, err = deletions.Insert(ctx, later)
	require.NoError(t, err)
	_, err = deletions.Insert(ctx, sooner)
	require.NoError(t, err)

	due, err := deletions.ListDue(ctx, testNow.Add(3*time.Hour), 10)
	require.NoError(t, err)
	require.Len(t, due, 2)
	require.Equal(t, sooner.ID, due[0].ID, "the oldest deadline is purged first")

	notYet, err := deletions.ListDue(ctx, testNow, 10)
	require.NoError(t, err)
	require.Empty(t, notYet, "nothing is due before its deadline")
}

func stringPtr(value string) *string { return &value }
