package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"

	svc "github.com/ecommerce/auth-service/internal/domain/service"
)

func TestConstantTimeCompare(t *testing.T) {
	testCases := []struct {
		name     string
		a        string
		b        string
		expected bool
	}{
		{"equal strings", "123456", "123456", true},
		{"different strings", "123456", "654321", false},
		{"different lengths", "123", "123456", false},
		{"empty strings", "", "", true},
		{"empty and non-empty", "", "123", false},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := svc.ConstantTimeCompare(tc.a, tc.b)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestGenerateSecureOTP(t *testing.T) {
	otp, err := svc.GenerateSecureOTP()

	require.NoError(t, err)
	assert.Len(t, otp, 6)

	for _, c := range otp {
		assert.True(t, c >= '0' && c <= '9', "OTP should contain only digits")
	}
}

func TestOTPService_Generate(t *testing.T) {
	ctx := context.Background()
	mockCache := new(mockOTPCache)
	otpService := svc.NewOTPService(mockCache)

	mockCache.On("Set", ctx, "test@example.com", mock.AnythingOfType("string")).Return(nil)

	otp, err := otpService.GenerateOTP(ctx, "test@example.com")

	require.NoError(t, err)
	assert.Len(t, otp, 6)
	mockCache.AssertExpectations(t)
}

func TestOTPService_Verify_Success(t *testing.T) {
	ctx := context.Background()
	mockCache := new(mockOTPCache)
	otpService := svc.NewOTPService(mockCache)

	otp := "123456"
	mockCache.On("Get", ctx, "test@example.com").Return(otp, nil)

	err := otpService.VerifyOTP(ctx, "test@example.com", otp)

	require.NoError(t, err)
	mockCache.AssertExpectations(t)
}

func TestOTPService_Verify_InvalidOTP(t *testing.T) {
	ctx := context.Background()
	mockCache := new(mockOTPCache)
	otpService := svc.NewOTPService(mockCache)

	mockCache.On("Get", ctx, "test@example.com").Return("123456", nil)

	err := otpService.VerifyOTP(ctx, "test@example.com", "654321")

	assert.Error(t, err)
	mockCache.AssertExpectations(t)
}

func TestOTPService_Verify_ExpiredOTP(t *testing.T) {
	ctx := context.Background()
	mockCache := new(mockOTPCache)
	otpService := svc.NewOTPService(mockCache)

	mockCache.On("Get", ctx, "test@example.com").Return("", nil)

	err := otpService.VerifyOTP(ctx, "test@example.com", "123456")

	assert.Error(t, err)
	mockCache.AssertExpectations(t)
}

func TestOTPService_Delete(t *testing.T) {
	ctx := context.Background()
	mockCache := new(mockOTPCache)
	otpService := svc.NewOTPService(mockCache)

	mockCache.On("Delete", ctx, "test@example.com").Return(nil)

	err := otpService.DeleteOTP(ctx, "test@example.com")

	require.NoError(t, err)
	mockCache.AssertExpectations(t)
}

func TestOTPService_GetOTPExpiry(t *testing.T) {
	ctx := context.Background()
	mockCache := new(mockOTPCache)
	otpService := svc.NewOTPService(mockCache)

	expectedTTL := 5 * time.Minute
	mockCache.On("GetRemainingTTL", ctx, "test@example.com").Return(expectedTTL, nil)

	ttl, err := otpService.GetOTPExpiry(ctx, "test@example.com")

	require.NoError(t, err)
	assert.Equal(t, expectedTTL, ttl)
	mockCache.AssertExpectations(t)
}
