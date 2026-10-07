package service_test

import (
	"context"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// The doubles below implement the outbound ports in memory. They keep the
// behaviour the real adapters promise — idempotent inserts, ownership-scoped
// lookups, the single-default invariant — so a use-case test exercises the same
// rules the SQL enforces.

// fakeTx runs the unit of work directly: there is nothing to commit in memory.
type fakeTx struct {
	// failCommit simulates a failure at the end of the transaction.
	failCommit error
}

func (f *fakeTx) WithinTx(ctx context.Context, fn func(ctx context.Context) error) error {
	if err := fn(ctx); err != nil {
		return err
	}
	return f.failCommit
}

type fakeProfileRepo struct {
	mu       sync.Mutex
	profiles map[uuid.UUID]entity.Profile
	// updates counts writes, so a test can prove a no-op patch wrote nothing.
	updates int
	failWith error
}

func newProfileRepo() *fakeProfileRepo {
	return &fakeProfileRepo{profiles: map[uuid.UUID]entity.Profile{}}
}

func (f *fakeProfileRepo) Insert(_ context.Context, profile *entity.Profile) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failWith != nil {
		return false, f.failWith
	}
	if _, exists := f.profiles[profile.ID]; exists {
		return false, nil
	}
	f.profiles[profile.ID] = *profile
	return true, nil
}

func (f *fakeProfileRepo) GetByID(_ context.Context, userID uuid.UUID) (*entity.Profile, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failWith != nil {
		return nil, f.failWith
	}
	profile, ok := f.profiles[userID]
	if !ok {
		return nil, apperr.ErrProfileNotFound
	}
	copied := profile
	return &copied, nil
}

func (f *fakeProfileRepo) GetForUpdate(ctx context.Context, userID uuid.UUID) (*entity.Profile, error) {
	return f.GetByID(ctx, userID)
}

func (f *fakeProfileRepo) Update(_ context.Context, profile *entity.Profile) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failWith != nil {
		return f.failWith
	}
	if _, ok := f.profiles[profile.ID]; !ok {
		return apperr.ErrProfileNotFound
	}
	f.updates++
	f.profiles[profile.ID] = *profile
	return nil
}

func (f *fakeProfileRepo) HardDelete(_ context.Context, userID uuid.UUID) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.profiles[userID]; !ok {
		return false, nil
	}
	delete(f.profiles, userID)
	return true, nil
}

func (f *fakeProfileRepo) get(userID uuid.UUID) (entity.Profile, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	profile, ok := f.profiles[userID]
	return profile, ok
}

type fakeAddressRepo struct {
	mu        sync.Mutex
	addresses map[uuid.UUID]entity.Address
	// locks counts LockUser calls, so a test can prove mutations serialize.
	locks int
}

func newAddressRepo() *fakeAddressRepo {
	return &fakeAddressRepo{addresses: map[uuid.UUID]entity.Address{}}
}

func (f *fakeAddressRepo) LockUser(_ context.Context, _ uuid.UUID) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.locks++
	return nil
}

func (f *fakeAddressRepo) ListByUser(_ context.Context, userID uuid.UUID) ([]*entity.Address, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	owned := make([]*entity.Address, 0, len(f.addresses))
	for _, address := range f.addresses {
		if address.UserID != userID {
			continue
		}
		copied := address
		owned = append(owned, &copied)
	}
	sort.Slice(owned, func(i, j int) bool {
		if owned[i].IsDefault != owned[j].IsDefault {
			return owned[i].IsDefault
		}
		return owned[i].CreatedAt.After(owned[j].CreatedAt)
	})
	return owned, nil
}

func (f *fakeAddressRepo) GetOwned(_ context.Context, userID, addressID uuid.UUID) (*entity.Address, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	address, ok := f.addresses[addressID]
	if !ok || address.UserID != userID {
		return nil, apperr.ErrAddressNotFound
	}
	copied := address
	return &copied, nil
}

func (f *fakeAddressRepo) CountByUser(_ context.Context, userID uuid.UUID) (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	count := 0
	for _, address := range f.addresses {
		if address.UserID == userID {
			count++
		}
	}
	return count, nil
}

func (f *fakeAddressRepo) Insert(_ context.Context, address *entity.Address) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	if address.IsDefault {
		for _, existing := range f.addresses {
			if existing.UserID == address.UserID && existing.IsDefault {
				return apperr.ErrDefaultAddressConflict
			}
		}
	}
	f.addresses[address.ID] = *address
	return nil
}

func (f *fakeAddressRepo) Update(_ context.Context, address *entity.Address) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	existing, ok := f.addresses[address.ID]
	if !ok || existing.UserID != address.UserID {
		return apperr.ErrAddressNotFound
	}
	f.addresses[address.ID] = *address
	return nil
}

func (f *fakeAddressRepo) Delete(_ context.Context, userID, addressID uuid.UUID) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	address, ok := f.addresses[addressID]
	if !ok || address.UserID != userID {
		return apperr.ErrAddressNotFound
	}
	delete(f.addresses, addressID)
	return nil
}

func (f *fakeAddressRepo) ClearDefault(_ context.Context, userID, exceptAddressID uuid.UUID) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	for id, address := range f.addresses {
		if address.UserID == userID && id != exceptAddressID && address.IsDefault {
			address.IsDefault = false
			f.addresses[id] = address
		}
	}
	return nil
}

func (f *fakeAddressRepo) SetDefault(_ context.Context, userID, addressID uuid.UUID) (time.Time, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	address, ok := f.addresses[addressID]
	if !ok || address.UserID != userID {
		return time.Time{}, apperr.ErrAddressNotFound
	}
	for id, other := range f.addresses {
		if other.UserID == userID && other.IsDefault && id != addressID {
			return time.Time{}, apperr.ErrDefaultAddressConflict
		}
	}

	address.IsDefault = true
	address.UpdatedAt = address.UpdatedAt.Add(time.Second)
	f.addresses[addressID] = address
	return address.UpdatedAt, nil
}

func (f *fakeAddressRepo) DeleteAllForUser(_ context.Context, userID uuid.UUID) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	var deleted int64
	for id, address := range f.addresses {
		if address.UserID == userID {
			delete(f.addresses, id)
			deleted++
		}
	}
	return deleted, nil
}

func (f *fakeAddressRepo) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.addresses)
}

type fakePreferencesRepo struct {
	mu          sync.Mutex
	preferences map[uuid.UUID]entity.Preferences
	updates     int
}

func newPreferencesRepo() *fakePreferencesRepo {
	return &fakePreferencesRepo{preferences: map[uuid.UUID]entity.Preferences{}}
}

func (f *fakePreferencesRepo) Insert(_ context.Context, preferences *entity.Preferences) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if _, exists := f.preferences[preferences.UserID]; exists {
		return false, nil
	}
	f.preferences[preferences.UserID] = *preferences
	return true, nil
}

func (f *fakePreferencesRepo) GetByUser(_ context.Context, userID uuid.UUID) (*entity.Preferences, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	preferences, ok := f.preferences[userID]
	if !ok {
		return nil, apperr.ErrProfileNotFound
	}
	copied := preferences
	return &copied, nil
}

func (f *fakePreferencesRepo) Update(_ context.Context, preferences *entity.Preferences) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	if _, ok := f.preferences[preferences.UserID]; !ok {
		return apperr.ErrProfileNotFound
	}
	f.updates++
	f.preferences[preferences.UserID] = *preferences
	return nil
}

func (f *fakePreferencesRepo) DeleteForUser(_ context.Context, userID uuid.UUID) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if _, ok := f.preferences[userID]; !ok {
		return false, nil
	}
	delete(f.preferences, userID)
	return true, nil
}

type fakeDeletionRepo struct {
	mu        sync.Mutex
	deletions map[uuid.UUID]entity.Deletion
	// claimed simulates a record another replica holds: ClaimDue returns nil.
	claimed map[uuid.UUID]bool
}

func newDeletionRepo() *fakeDeletionRepo {
	return &fakeDeletionRepo{
		deletions: map[uuid.UUID]entity.Deletion{},
		claimed:   map[uuid.UUID]bool{},
	}
}

func (f *fakeDeletionRepo) Insert(_ context.Context, deletion *entity.Deletion) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	for _, existing := range f.deletions {
		if existing.UserID == deletion.UserID {
			return false, nil
		}
	}
	f.deletions[deletion.ID] = *deletion
	return true, nil
}

func (f *fakeDeletionRepo) ExistsForUser(_ context.Context, userID uuid.UUID) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	for _, existing := range f.deletions {
		if existing.UserID == userID {
			return true, nil
		}
	}
	return false, nil
}

func (f *fakeDeletionRepo) ListDue(_ context.Context, now time.Time, limit int) ([]*entity.Deletion, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	due := make([]*entity.Deletion, 0, len(f.deletions))
	for _, deletion := range f.deletions {
		if deletion.HardDeleteAfter.After(now) {
			continue
		}
		copied := deletion
		due = append(due, &copied)
	}
	sort.Slice(due, func(i, j int) bool {
		return due[i].HardDeleteAfter.Before(due[j].HardDeleteAfter)
	})
	if len(due) > limit {
		due = due[:limit]
	}
	return due, nil
}

func (f *fakeDeletionRepo) ClaimDue(_ context.Context, deletionID uuid.UUID, now time.Time) (*entity.Deletion, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	deletion, ok := f.deletions[deletionID]
	if !ok || f.claimed[deletionID] || deletion.HardDeleteAfter.After(now) {
		return nil, nil
	}
	copied := deletion
	return &copied, nil
}

func (f *fakeDeletionRepo) Delete(_ context.Context, deletionID uuid.UUID) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	delete(f.deletions, deletionID)
	return nil
}

func (f *fakeDeletionRepo) CountOverdueBefore(_ context.Context, cutoff time.Time) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	var count int64
	for _, deletion := range f.deletions {
		if !deletion.HardDeleteAfter.After(cutoff) {
			count++
		}
	}
	return count, nil
}

func (f *fakeDeletionRepo) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.deletions)
}

type fakeProjectionRepo struct {
	snapshots []port.ProjectionSnapshot
}

func (f *fakeProjectionRepo) ListActive(_ context.Context, afterUserID uuid.UUID, limit int) ([]port.ProjectionSnapshot, error) {
	ordered := append([]port.ProjectionSnapshot(nil), f.snapshots...)
	sort.Slice(ordered, func(i, j int) bool {
		return ordered[i].UserID.String() < ordered[j].UserID.String()
	})

	batch := make([]port.ProjectionSnapshot, 0, limit)
	for _, snapshot := range ordered {
		if snapshot.UserID.String() <= afterUserID.String() && afterUserID != uuid.Nil {
			continue
		}
		batch = append(batch, snapshot)
		if len(batch) == limit {
			break
		}
	}
	return batch, nil
}

type fakeAddressCache struct {
	mu sync.Mutex
	// entries holds what a previous Put stored, keyed by user.
	entries map[uuid.UUID][]*entity.Address
	// unavailable makes every operation behave like a Redis outage.
	unavailable bool
	gets        int
	puts        int
	invalidated int
}

func newAddressCache() *fakeAddressCache {
	return &fakeAddressCache{entries: map[uuid.UUID][]*entity.Address{}}
}

func (f *fakeAddressCache) Get(_ context.Context, userID uuid.UUID) ([]*entity.Address, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.gets++
	if f.unavailable {
		return nil, false
	}
	addresses, ok := f.entries[userID]
	return addresses, ok
}

func (f *fakeAddressCache) Put(_ context.Context, userID uuid.UUID, addresses []*entity.Address) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.puts++
	if f.unavailable {
		return apperr.ErrDependencyUnavailable
	}
	f.entries[userID] = addresses
	return nil
}

func (f *fakeAddressCache) Invalidate(_ context.Context, userID uuid.UUID) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.invalidated++
	delete(f.entries, userID)
	if f.unavailable {
		return apperr.ErrDependencyUnavailable
	}
	return nil
}

func (f *fakeAddressCache) cached(userID uuid.UUID) ([]*entity.Address, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	addresses, ok := f.entries[userID]
	return addresses, ok
}

type fakePublisher struct {
	mu        sync.Mutex
	published []*event.Envelope
	// failWith makes every publish fail, which is the ADR-004 gap the
	// reconciliation sweep is supposed to close.
	failWith error
}

func newPublisher() *fakePublisher { return &fakePublisher{} }

func (f *fakePublisher) Publish(_ context.Context, envelope *event.Envelope) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	if f.failWith != nil {
		return f.failWith
	}
	f.published = append(f.published, envelope)
	return nil
}

func (f *fakePublisher) events() []*event.Envelope {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*event.Envelope(nil), f.published...)
}

func (f *fakePublisher) typesPublished() []string {
	types := make([]string, 0)
	for _, envelope := range f.events() {
		types = append(types, envelope.Type)
	}
	return types
}
