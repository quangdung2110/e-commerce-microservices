package service_test

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
)

func TestFirstAddressBecomesDefault(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	first, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	require.True(t, first.IsDefault, "USER-BR-002: the first address is the default")

	second, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	require.False(t, second.IsDefault, "USER-BR-003: only one default may exist")
}

func TestAddressMutationsSerializePerUser(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	address, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	require.NoError(t, h.addressService.Delete(context.Background(), userID,
		mustSecondAddress(t, h, userID, address.ID)))

	// Every mutation takes the per-user lock first; the partial unique index is
	// only the last guard behind it (docs/user/data.md §5).
	require.GreaterOrEqual(t, h.addresses.locks, 2)
}

func TestAddressOfAnotherOwnerIsReportedMissing(t *testing.T) {
	h := newHarness(t)
	owner := h.seedProfile(t)
	stranger := h.seedProfile(t)

	address, err := h.addressService.Create(context.Background(), owner, validAddress())
	require.NoError(t, err)

	_, err = h.addressService.Get(context.Background(), stranger, address.ID)
	require.ErrorIs(t, err, apperr.ErrAddressNotFound,
		"USER-BR-001: ownership is never disclosed")

	err = h.addressService.Delete(context.Background(), stranger, address.ID)
	require.ErrorIs(t, err, apperr.ErrAddressNotFound)

	_, err = h.addressService.Replace(context.Background(), stranger, address.ID, validAddress())
	require.ErrorIs(t, err, apperr.ErrAddressNotFound)
}

func TestDefaultAddressCannotBeDeleted(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	first, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	second, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)

	err = h.addressService.Delete(context.Background(), userID, first.ID)
	require.ErrorIs(t, err, apperr.ErrDefaultAddressCannotBeDeleted,
		"USER-BR-004: promote a replacement first")

	// After promoting the other address, the old default can be removed.
	_, err = h.addressService.SetDefault(context.Background(), userID, second.ID)
	require.NoError(t, err)
	require.NoError(t, h.addressService.Delete(context.Background(), userID, first.ID))
}

func TestSetDefaultMovesTheFlagAndIsIdempotent(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	first, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	second, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)

	promoted, err := h.addressService.SetDefault(context.Background(), userID, second.ID)
	require.NoError(t, err)
	require.True(t, promoted.IsDefault)

	previous, err := h.addressService.Get(context.Background(), userID, first.ID)
	require.NoError(t, err)
	require.False(t, previous.IsDefault, "the previous default is cleared in the same transaction")

	invalidations := h.cache.invalidated
	again, err := h.addressService.SetDefault(context.Background(), userID, second.ID)
	require.NoError(t, err)
	require.True(t, again.IsDefault)
	require.Equal(t, invalidations, h.cache.invalidated,
		"repeating set-default changes nothing, so the cache is left alone")
}

func TestAddressValidationCollectsEveryProblem(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.addressService.Create(context.Background(), userID, entity.AddressInput{
		RecipientName:  "",
		RecipientPhone: "0901234567", // not E.164
		AddressLine:    "",
		ProvinceCode:   "79",
		DistrictCode:   "invalid code",
		WardCode:       "26734",
	})

	require.ErrorIs(t, err, apperr.ErrInvalidAddress)
	appErr := apperr.From(err)
	fields := make([]string, 0, len(appErr.Fields))
	for _, field := range appErr.Fields {
		fields = append(fields, field.Field)
	}
	require.ElementsMatch(t,
		[]string{"recipient_name", "recipient_phone", "address_line", "district_code"},
		fields, "one 422 lists every invalid field")
}

func TestAddressCreateNormalizesPhone(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	input := validAddress()
	input.RecipientPhone = "+84 901-234.567"

	address, err := h.addressService.Create(context.Background(), userID, input)
	require.NoError(t, err)
	require.Equal(t, "+84901234567", address.RecipientPhone.String())
}

func TestAddressCreateRequiresAProfile(t *testing.T) {
	h := newHarness(t)

	_, err := h.addressService.Create(context.Background(), uuid.New(), validAddress())

	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
	require.Zero(t, h.addresses.count())
}

func TestListServesCacheHitAndFillsOnMiss(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	// The create invalidated the key, so this read is a miss that refills it.
	first, err := h.addressService.List(context.Background(), userID)
	require.NoError(t, err)
	require.Len(t, first, 1)

	cached, ok := h.cache.cached(userID)
	require.True(t, ok, "a miss stores the list for the next reader")
	require.Len(t, cached, 1)

	second, err := h.addressService.List(context.Background(), userID)
	require.NoError(t, err)
	require.Len(t, second, 1)
}

func TestListFallsBackToTheDatabaseWhenRedisIsDown(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)

	_, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)

	h.cache.unavailable = true
	addresses, err := h.addressService.List(context.Background(), userID)

	// ADR-002: a cache outage degrades to PostgreSQL instead of failing.
	require.NoError(t, err)
	require.Len(t, addresses, 1)
}

func TestMutationSucceedsWhenInvalidationFails(t *testing.T) {
	h := newHarness(t)
	userID := h.seedProfile(t)
	h.cache.unavailable = true

	address, err := h.addressService.Create(context.Background(), userID, validAddress())

	// The row is committed before the cache is touched, so a failed DEL is
	// reported for alerting but never rolls the mutation back.
	require.NoError(t, err)
	require.NotNil(t, address)
	require.Equal(t, 1, h.addresses.count())
	require.Equal(t, 1, h.cache.invalidated)
}

func TestListReportsMissingProfileInsteadOfAnEmptyBook(t *testing.T) {
	h := newHarness(t)

	_, err := h.addressService.List(context.Background(), uuid.New())

	require.ErrorIs(t, err, apperr.ErrProfileNotFound)
}

// mustSecondAddress creates one more address so the caller has a non-default one
// to delete, and returns its id.
func mustSecondAddress(t *testing.T, h *harness, userID, defaultID uuid.UUID) uuid.UUID {
	t.Helper()

	address, err := h.addressService.Create(context.Background(), userID, validAddress())
	require.NoError(t, err)
	require.NotEqual(t, defaultID, address.ID)
	return address.ID
}
