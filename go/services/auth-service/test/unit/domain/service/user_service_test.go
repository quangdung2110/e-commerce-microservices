package service_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"

	svc "github.com/ecommerce/auth-service/internal/api/dto/svc"
	svcpkg "github.com/ecommerce/auth-service/internal/domain/service"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

func TestUserService_Register_Success(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	mockUserRepo.On("GetByEmail", ctx, "test@example.com").Return(nil, apperrors.ErrNotFound)
	mockPasswordHasher.On("Hash", "Password123").Return("hashed_password", nil)
	mockUserRepo.On("Create", ctx, mock.AnythingOfType("*entity.User")).Return(nil)
	mockOTPCache.On("Set", ctx, "test@example.com", mock.AnythingOfType("string")).Return(nil)
	mockEventPublisher.On("Publish", ctx, mock.AnythingOfType("*event.UserOTPRequested")).Return(nil)

	user, otp, err := userService.Register(ctx, &svc.RegisterRequest{
		Email:      "test@example.com",
		Password:   "Password123",
		IPAddress:  "127.0.0.1",
		DeviceInfo: "Test Browser",
	})

	require.NoError(t, err)
	assert.NotNil(t, user)
	assert.Equal(t, "test@example.com", user.Email.String())
	assert.Equal(t, vo.StatusPendingVerification, user.Status)
	assert.Len(t, otp, 6)

	mockUserRepo.AssertExpectations(t)
	mockPasswordHasher.AssertExpectations(t)
	mockOTPCache.AssertExpectations(t)
	mockEventPublisher.AssertExpectations(t)
}

func TestUserService_Register_DuplicateEmail(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	existingEmail, _ := vo.NewEmail("existing@example.com")
	existingUser := entity.NewUser(*existingEmail, "existing_hash")

	mockUserRepo.On("GetByEmail", ctx, "existing@example.com").Return(existingUser, nil)

	_, _, err := userService.Register(ctx, &svc.RegisterRequest{
		Email:    "existing@example.com",
		Password: "Password123",
	})

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "conflict")
	mockUserRepo.AssertExpectations(t)
}

func TestUserService_Register_WeakPassword(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	testCases := []struct {
		name     string
		password string
	}{
		{"too short", "Pass1"},
		{"no uppercase", "password123"},
		{"no lowercase", "PASSWORD123"},
		{"no digit", "PasswordABC"},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			user, otp, err := userService.Register(ctx, &svc.RegisterRequest{
				Email:    "test@example.com",
				Password: tc.password,
			})

			assert.Error(t, err)
			assert.Nil(t, user)
			assert.Empty(t, otp)
		})
	}
}

func TestUserService_Register_InvalidEmail(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	user, otp, err := userService.Register(ctx, &svc.RegisterRequest{
		Email:    "invalid-email",
		Password: "Password123",
	})

	assert.Error(t, err)
	assert.Nil(t, user)
	assert.Empty(t, otp)
	assert.Contains(t, err.Error(), "email")
}

func TestUserService_VerifyEmail(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "hashed_password")

	mockOTPCache.On("Get", ctx, user.Email.String()).Return("123456", nil)
	mockUserRepo.On("GetByEmail", ctx, user.Email.String()).Return(user, nil)
	mockUserRepo.On("Update", ctx, mock.AnythingOfType("*entity.User")).Return(nil)
	mockOTPCache.On("Delete", ctx, user.Email.String()).Return(nil)
	mockEventPublisher.On("Publish", ctx, mock.AnythingOfType("*event.UserVerifiedSuccess")).Return(nil)

	err := userService.VerifyEmail(ctx, user.Email.String(), "123456")

	require.NoError(t, err)
	assert.True(t, user.IsEmailVerified())
	assert.Equal(t, vo.StatusActive, user.Status)

	mockOTPCache.AssertExpectations(t)
	mockUserRepo.AssertExpectations(t)
	mockEventPublisher.AssertExpectations(t)
}

func TestUserService_VerifyEmail_AlreadyVerified(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "hashed_password")
	user.SetEmailVerified()

	mockOTPCache.On("Get", ctx, user.Email.String()).Return("123456", nil)
	mockUserRepo.On("GetByEmail", ctx, user.Email.String()).Return(user, nil)

	err := userService.VerifyEmail(ctx, user.Email.String(), "123456")

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "already verified")

	mockOTPCache.AssertExpectations(t)
	mockUserRepo.AssertExpectations(t)
}

func TestUserService_ResendOTP(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "hashed_password")

	mockUserRepo.On("GetByEmail", ctx, user.Email.String()).Return(user, nil)
	mockOTPCache.On("Set", ctx, user.Email.String(), mock.AnythingOfType("string")).Return(nil)
	mockEventPublisher.On("Publish", ctx, mock.AnythingOfType("*event.UserOTPRequested")).Return(nil)

	otp, err := userService.ResendOTP(ctx, user.Email.String())

	require.NoError(t, err)
	assert.Len(t, otp, 6)

	mockUserRepo.AssertExpectations(t)
	mockOTPCache.AssertExpectations(t)
	mockEventPublisher.AssertExpectations(t)
}

func TestUserService_ResendOTP_AlreadyVerified(t *testing.T) {
	ctx := context.Background()
	mockUserRepo := new(mockUserRepository)
	mockOTPCache := new(mockOTPCache)
	mockPasswordHasher := new(mockPasswordHasher)
	mockEventPublisher := new(mockEventPublisher)

	otpService := svcpkg.NewOTPService(mockOTPCache)
	userService := svcpkg.NewUserService(
		mockUserRepo,
		otpService,
		mockPasswordHasher,
		mockEventPublisher,
	)

	email, _ := vo.NewEmail("test@example.com")
	user := entity.NewUser(*email, "hashed_password")
	user.SetEmailVerified()

	mockUserRepo.On("GetByEmail", ctx, user.Email.String()).Return(user, nil)

	otp, err := userService.ResendOTP(ctx, user.Email.String())

	assert.Error(t, err)
	assert.Empty(t, otp)
	assert.Contains(t, err.Error(), "already verified")

	mockUserRepo.AssertExpectations(t)
}
