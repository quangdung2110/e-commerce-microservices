package preference

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

type prefRepo struct {
	port.PreferenceRepository
	got     *entity.Preference
	applied bool
	err     error
}

func (r *prefRepo) Upsert(_ context.Context, p *entity.Preference) (bool, error) {
	r.got = p
	return r.applied, r.err
}

type tombs struct {
	port.TombstoneRepository
	dead   bool
	locked *bool
}

func (t tombs) Lock(context.Context, []pii.KeyedHash) error {
	*t.locked = true
	return nil
}

func (t tombs) Exists(_ context.Context, keys []pii.KeyedHash) (bool, error) {
	if !*t.locked {
		return false, errors.New("tombstone checked without the per-user lock")
	}
	return t.dead, nil
}

type store struct {
	port.Store
	repo *prefRepo
	dead bool
}

func (s store) Preferences() port.PreferenceRepository { return s.repo }
func (s store) Tombstones() port.TombstoneRepository {
	return tombs{dead: s.dead, locked: new(bool)}
}

func (s store) WithinTx(ctx context.Context, fn func(context.Context, port.Tx) error) error {
	locked := false
	return fn(ctx, txStore{s: s, locked: &locked})
}

type txStore struct {
	port.Tx
	s      store
	locked *bool
}

func (t txStore) Preferences() port.PreferenceRepository { return t.s.repo }
func (t txStore) Tombstones() port.TombstoneRepository {
	return tombs{dead: t.s.dead, locked: t.locked}
}

func testKeyring() *pii.Keyring {
	k, err := pii.NewKeyring(1, []byte("preference-test-key-preference-test"), nil)
	if err != nil {
		panic(err)
	}
	return k
}

func TestApply(t *testing.T) {
	now := time.Date(2026, 8, 28, 8, 5, 0, 0, time.UTC)
	uid := uuid.New()
	updatedAt := time.Date(2026, 8, 28, 8, 5, 0, 123_000_000, time.UTC)

	t.Run("applied", func(t *testing.T) {
		repo := &prefRepo{applied: true}
		svc := New(store{repo: repo}, testKeyring(), clock.NewFake(now), nil)
		ok, err := svc.Apply(context.Background(), Update{UserID: uid, EmailEnabled: true, InAppEnabled: false, MarketingEnabled: true, UpdatedAt: updatedAt})
		if err != nil || !ok {
			t.Fatalf("ok=%v err=%v", ok, err)
		}
		p := repo.got
		if p.UserID != uid || !p.EmailEnabled || p.InAppEnabled || !p.MarketingEnabled || p.Version != updatedAt.UnixMilli() || !p.SyncedAt.Equal(now) {
			t.Fatalf("preference %+v", p)
		}
	})
	t.Run("stale is not an error", func(t *testing.T) {
		repo := &prefRepo{applied: false}
		svc := New(store{repo: repo}, testKeyring(), clock.NewFake(now), nil)
		ok, err := svc.Apply(context.Background(), Update{UserID: uid, UpdatedAt: updatedAt})
		if err != nil || ok {
			t.Fatalf("ok=%v err=%v", ok, err)
		}
	})
	t.Run("repository error propagates", func(t *testing.T) {
		repo := &prefRepo{err: errors.New("pg down")}
		svc := New(store{repo: repo}, testKeyring(), clock.NewFake(now), nil)
		if _, err := svc.Apply(context.Background(), Update{UserID: uid, UpdatedAt: updatedAt}); err == nil {
			t.Fatal("expected error")
		}
	})
	t.Run("deleted user is never re-created", func(t *testing.T) {
		repo := &prefRepo{applied: true}
		svc := New(store{repo: repo, dead: true}, testKeyring(), clock.NewFake(now), nil)
		ok, err := svc.Apply(context.Background(), Update{UserID: uid, EmailEnabled: true, UpdatedAt: updatedAt})
		if err != nil || ok {
			t.Fatalf("ok=%v err=%v", ok, err)
		}
		if repo.got != nil {
			t.Fatal("upsert must not run for a tombstoned user")
		}
	})
	t.Run("missing user id", func(t *testing.T) {
		svc := New(store{repo: &prefRepo{}}, testKeyring(), clock.NewFake(now), nil)
		if _, err := svc.Apply(context.Background(), Update{UpdatedAt: updatedAt}); err == nil {
			t.Fatal("expected error")
		}
	})
}
