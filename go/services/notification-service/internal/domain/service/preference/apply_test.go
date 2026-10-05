package preference

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/clock"
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

type store struct {
	port.Store
	repo *prefRepo
}

func (s store) Preferences() port.PreferenceRepository { return s.repo }

func TestApply(t *testing.T) {
	now := time.Date(2026, 8, 28, 8, 5, 0, 0, time.UTC)
	uid := uuid.New()
	updatedAt := time.Date(2026, 8, 28, 8, 5, 0, 123_000_000, time.UTC)

	t.Run("applied", func(t *testing.T) {
		repo := &prefRepo{applied: true}
		svc := New(store{repo: repo}, clock.NewFake(now), nil)
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
		svc := New(store{repo: repo}, clock.NewFake(now), nil)
		ok, err := svc.Apply(context.Background(), Update{UserID: uid, UpdatedAt: updatedAt})
		if err != nil || ok {
			t.Fatalf("ok=%v err=%v", ok, err)
		}
	})
	t.Run("repository error propagates", func(t *testing.T) {
		repo := &prefRepo{err: errors.New("pg down")}
		svc := New(store{repo: repo}, clock.NewFake(now), nil)
		if _, err := svc.Apply(context.Background(), Update{UserID: uid, UpdatedAt: updatedAt}); err == nil {
			t.Fatal("expected error")
		}
	})
	t.Run("missing user id", func(t *testing.T) {
		svc := New(store{repo: &prefRepo{}}, clock.NewFake(now), nil)
		if _, err := svc.Apply(context.Background(), Update{UpdatedAt: updatedAt}); err == nil {
			t.Fatal("expected error")
		}
	})
}
