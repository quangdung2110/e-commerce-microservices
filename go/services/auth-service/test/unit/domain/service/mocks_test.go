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

type mockRefreshTokenRepository struct {
	mock.Mock
}

func (m *mockRefreshTokenRepository) Create(ctx context.Context, token *entity.RefreshToken) error {
	args := m.Called(ctx, token)
	return args.Error(0)
}

func (m *mockRefreshTokenRepository) GetByID(ctx context.Context, id string) (*entity.RefreshToken, error) {
	args := m.Called(ctx, id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*entity.RefreshToken), args.Error(1)
}

func (m *mockRefreshTokenRepository) GetByTokenHash(ctx context.Context, hash string) (*entity.RefreshToken, error) {
	args := m.Called(ctx, hash)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*entity.RefreshToken), args.Error(1)
}

func (m *mockRefreshTokenRepository) GetActiveByUserID(ctx context.Context, userID string) ([]*entity.RefreshToken, error) {
	args := m.Called(ctx, userID)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).([]*entity.RefreshToken), args.Error(1)
}

func (m *mockRefreshTokenRepository) Revoke(ctx context.Context, id string) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}

func (m *mockRefreshTokenRepository) RevokeByHash(ctx context.Context, hash string) error {
	args := m.Called(ctx, hash)
	return args.Error(0)
}

func (m *mockRefreshTokenRepository) RevokeAllForUser(ctx context.Context, userID string) error {
	args := m.Called(ctx, userID)
	return args.Error(0)
}

func (m *mockRefreshTokenRepository) RevokeFamily(ctx context.Context, familyID string) error {
	args := m.Called(ctx, familyID)
	return args.Error(0)
}

func (m *mockRefreshTokenRepository) UpdateReplacedBy(ctx context.Context, oldID, newID string) error {
	args := m.Called(ctx, oldID, newID)
	return args.Error(0)
}

func (m *mockRefreshTokenRepository) DeleteExpired(ctx context.Context, olderThan time.Time) (int64, error) {
	args := m.Called(ctx, olderThan)
	return args.Get(0).(int64), args.Error(1)
}

type mockTokenCache struct {
	mock.Mock
}

func (m *mockTokenCache) AddToDenylist(ctx context.Context, jti string, remainingTTL time.Duration) error {
	args := m.Called(ctx, jti, remainingTTL)
	return args.Error(0)
}

func (m *mockTokenCache) IsDenylisted(ctx context.Context, jti string) (bool, error) {
	args := m.Called(ctx, jti)
	return args.Bool(0), args.Error(1)
}

func (m *mockTokenCache) SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error {
	args := m.Called(ctx, userID, timestamp)
	return args.Error(0)
}

func (m *mockTokenCache) GetRevokedBefore(ctx context.Context, userID string) (time.Time, error) {
	args := m.Called(ctx, userID)
	return args.Get(0).(time.Time), args.Error(1)
}

type mockFailedLoginCache struct {
	mock.Mock
}

func (m *mockFailedLoginCache) Increment(ctx context.Context, identifier string) (int, error) {
	args := m.Called(ctx, identifier)
	return args.Int(0), args.Error(1)
}

func (m *mockFailedLoginCache) GetCount(ctx context.Context, identifier string) (int, error) {
	args := m.Called(ctx, identifier)
	return args.Int(0), args.Error(1)
}

func (m *mockFailedLoginCache) Reset(ctx context.Context, identifier string) error {
	args := m.Called(ctx, identifier)
	return args.Error(0)
}

func (m *mockFailedLoginCache) IsLocked(ctx context.Context, identifier string) (bool, error) {
	args := m.Called(ctx, identifier)
	return args.Bool(0), args.Error(1)
}

func (m *mockFailedLoginCache) IncrementAndCheck(ctx context.Context, identifier string) (int, bool, error) {
	args := m.Called(ctx, identifier)
	return args.Int(0), args.Bool(1), args.Error(2)
}

func (m *mockFailedLoginCache) GetRemainingTTL(ctx context.Context, identifier string) (time.Duration, error) {
	args := m.Called(ctx, identifier)
	return args.Get(0).(time.Duration), args.Error(1)
}

var _ port.RefreshTokenRepository = (*mockRefreshTokenRepository)(nil)
var _ port.TokenCache = (*mockTokenCache)(nil)
var _ port.FailedLoginCache = (*mockFailedLoginCache)(nil)
