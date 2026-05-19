package service_test

import (
	"context"
	"time"

	"github.com/stretchr/testify/mock"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

type mockUserRepository struct {
	mock.Mock
}

func (m *mockUserRepository) Create(ctx context.Context, user *entity.User) error {
	args := m.Called(ctx, user)
	return args.Error(0)
}

func (m *mockUserRepository) GetByID(ctx context.Context, id string) (*entity.User, error) {
	args := m.Called(ctx, id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*entity.User), args.Error(1)
}

func (m *mockUserRepository) GetByEmail(ctx context.Context, email string) (*entity.User, error) {
	args := m.Called(ctx, email)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*entity.User), args.Error(1)
}

func (m *mockUserRepository) Update(ctx context.Context, user *entity.User) error {
	args := m.Called(ctx, user)
	return args.Error(0)
}

func (m *mockUserRepository) UpdateStatus(ctx context.Context, id string, status string) error {
	args := m.Called(ctx, id, status)
	return args.Error(0)
}

func (m *mockUserRepository) LockAccount(ctx context.Context, id string) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}

func (m *mockUserRepository) UnlockAccount(ctx context.Context, id string) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}

func (m *mockUserRepository) BanAccount(ctx context.Context, id string) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}

func (m *mockUserRepository) GetLockedUsers(ctx context.Context, lockDuration time.Duration, limit int) ([]*entity.User, error) {
	args := m.Called(ctx, lockDuration, limit)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).([]*entity.User), args.Error(1)
}

func (m *mockUserRepository) Delete(ctx context.Context, id string) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}

type mockOTPCache struct {
	mock.Mock
}

func (m *mockOTPCache) Set(ctx context.Context, email string, otp string) error {
	args := m.Called(ctx, email, otp)
	return args.Error(0)
}

func (m *mockOTPCache) Get(ctx context.Context, email string) (string, error) {
	args := m.Called(ctx, email)
	return args.String(0), args.Error(1)
}

func (m *mockOTPCache) Delete(ctx context.Context, email string) error {
	args := m.Called(ctx, email)
	return args.Error(0)
}

func (m *mockOTPCache) Verify(ctx context.Context, email string, otp string) (bool, error) {
	args := m.Called(ctx, email, otp)
	return args.Bool(0), args.Error(1)
}

func (m *mockOTPCache) GetRemainingTTL(ctx context.Context, email string) (time.Duration, error) {
	args := m.Called(ctx, email)
	return args.Get(0).(time.Duration), args.Error(1)
}

type mockPasswordHasher struct {
	mock.Mock
}

func (m *mockPasswordHasher) Hash(password string) (string, error) {
	args := m.Called(password)
	return args.String(0), args.Error(1)
}

func (m *mockPasswordHasher) Verify(hash, password string) bool {
	args := m.Called(hash, password)
	return args.Bool(0)
}

type mockEventPublisher struct {
	mock.Mock
}

func (m *mockEventPublisher) Publish(ctx context.Context, event interface{}) error {
	args := m.Called(ctx, event)
	return args.Error(0)
}

var _ port.UserRepository = (*mockUserRepository)(nil)
var _ port.OTPCache = (*mockOTPCache)(nil)
var _ port.PasswordHasher = (*mockPasswordHasher)(nil)
var _ port.EventPublisher = (*mockEventPublisher)(nil)
