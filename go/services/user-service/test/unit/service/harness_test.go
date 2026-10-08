// Package service_test exercises the User use cases against in-memory doubles.
//
// It covers the rules of docs/user/domain.md and the workflows of
// docs/user/workflows.md: address ownership, the single-default invariant, the
// refusal to delete the default, idempotent bootstrap, anonymization and the
// retention purge. The integration suite proves the SQL that backs them.
package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/clock"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/service"
)

// testNow is a fixed instant so every deadline in a test is deterministic.
var testNow = time.Date(2026, 8, 28, 12, 0, 0, 0, time.UTC)

// harness wires the use cases against in-memory doubles.
type harness struct {
	clock       *clock.Fixed
	profiles    *fakeProfileRepo
	addresses   *fakeAddressRepo
	preferences *fakePreferencesRepo
	deletions   *fakeDeletionRepo
	projections *fakeProjectionRepo
	cache       *fakeAddressCache
	publisher   *fakePublisher
	tx          *fakeTx

	profileService    *service.ProfileService
	addressService    *service.AddressService
	preferenceService *service.PreferencesService
	lifecycleService  *service.LifecycleService
	reconcileService  *service.ReconciliationService
}

func newHarness(t *testing.T) *harness {
	t.Helper()

	logger := zap.NewNop()
	deletions := newDeletionRepo()
	h := &harness{
		clock:       clock.NewFixed(testNow),
		profiles:    newProfileRepo(deletions),
		addresses:   newAddressRepo(),
		preferences: newPreferencesRepo(),
		deletions:   deletions,
		projections: &fakeProjectionRepo{},
		cache:       newAddressCache(),
		publisher:   newPublisher(),
		tx:          &fakeTx{},
	}
	emitter := service.NewEventEmitter(h.publisher, logger)
	h.profileService = service.NewProfileService(h.profiles, h.tx, emitter, h.clock)
	h.preferenceService = service.NewPreferencesService(h.preferences, h.tx, emitter, h.clock)
	h.addressService = service.NewAddressService(h.addresses, h.profiles, h.cache, h.tx, h.clock, logger)
	h.lifecycleService = service.NewLifecycleService(
		h.profiles, h.addresses, h.preferences, h.deletions, h.cache, h.tx, h.clock, logger)
	h.reconcileService = service.NewReconciliationService(h.projections, emitter, logger)

	return h
}

// seedProfile creates a bootstrapped user, which is the state every HTTP
// workflow starts from.
func (h *harness) seedProfile(t *testing.T) uuid.UUID {
	t.Helper()

	userID := uuid.New()
	if _, err := h.profiles.Insert(context.Background(), entity.NewProfile(userID, testNow)); err != nil {
		t.Fatalf("seed profile: %v", err)
	}
	if _, err := h.preferences.Insert(context.Background(), entity.NewDefaultPreferences(userID, testNow)); err != nil {
		t.Fatalf("seed preferences: %v", err)
	}
	return userID
}

// validAddress is a well-formed write payload.
func validAddress() entity.AddressInput {
	return entity.AddressInput{
		RecipientName:  "Nguyen Van A",
		RecipientPhone: "+84901234567",
		AddressLine:    "12 Nguyen Hue, toa nha ABC, tang 4",
		ProvinceCode:   "79",
		DistrictCode:   "760",
		WardCode:       "26734",
	}
}

func stringPtr(value string) *string { return &value }

// mustNameChanges fills in a name, which is what makes anonymization visible.
func mustNameChanges() entity.ProfileChanges {
	return entity.ProfileChanges{
		SetFirstName: true,
		FirstName:    stringPtr("Nguyen"),
		SetLastName:  true,
		LastName:     stringPtr("Van A"),
	}
}
