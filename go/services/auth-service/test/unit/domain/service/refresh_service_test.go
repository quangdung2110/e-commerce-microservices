package service_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

func TestRefreshService_RefreshTokens_Success(t *testing.T) {
	ctx := context.Background()
	logger := getTestLogger(t)

	mockUserRepo := new(mockUserRepository)
	mockRefreshTokenRepo := new(mockRefreshTokenRepository)
	mockTokenCache := new(mockTokenCache)
	mockGracePeriodCache := new(mockGracePeriodCache)
	mockJWTService := new(mockJWTService)
	mockEventPublisher := new(mockEventPublisher)
	mockAuditService := new(mockAuditService)

	refreshService := service.NewRefreshService(
		mockUserRepo,
		mockRefreshTokenRepo,
		mockTokenCache,
		mockGracePeriodCache,
		mockJWTService,
		mockEventPublisher,
		mockAuditService,
		logger,
	)

	userID := uuid.New().String()
	oldRefreshToken := "old-refresh-token"
	oldTokenHash := service.HashToken(oldRefreshToken)
	now := time.Now()

	storedToken := &entity.RefreshToken{
		ID:         uuid.New().String(),
		UserID:     userID,
		TokenHash:  oldTokenHash,
		FamilyID:   "family-123",
		DeviceID:   "device-123",
		DeviceInfo: "Mozilla/5.0",
		IPAddress:  "127.0.0.1",
		ExpiresAt:  now.Add(24 * time.Hour),
		RevokedAt:  nil, // Not revoked - normal case
		CreatedAt:  now.Add(-1 * time.Hour),
	}

	user := &entity.User{
		ID:     userID,
		Email:  mustParseEmail(t, "test@example.com"),
		Roles:  []string{"USER"},
		Status: entity.StatusActive,
	}

	// Setup expectations
	mockRefreshTokenRepo.On("GetByTokenHash", ctx, oldTokenHash).Return(storedToken, nil)
	mockUserRepo.On("GetByID", ctx, userID).Return(user, nil)
	mockJWTService.On("GenerateAccessToken", mock.Anything, user).Return("new-access-token", nil)
	mockRefreshTokenRepo.On("RevokeByHash", ctx, oldTokenHash).Return(nil)
	mockRefreshTokenRepo.On("Create", mock.Anything, mock.AnythingOfType("*entity.RefreshToken")).Return(nil)
	mockRefreshTokenRepo.On("UpdateReplacedBy", mock.Anything, mock.Anything, mock.Anything).Return(nil)
	mockTokenCache.On("AddToDenylist", mock.Anything, mock.Anything, mock.Anything).Return(nil)
	mockGracePeriodCache.On("Set", mock.Anything, oldTokenHash, "new-access-token", mock.Anything).Return(nil)
	mockRefreshTokenRepo.On("GetByTokenHash", mock.Anything, oldTokenHash).Return(storedToken, nil).Maybe()

	resp, err := refreshService.RefreshTokens(
		ctx,
		oldRefreshToken,
		"old-jti",
		time.Now().Add(15*time.Minute),
	)

	require.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, "new-access-token", resp.AccessToken)
	assert.NotEmpty(t, resp.NewRefreshToken)
	assert.Equal(t, int64(900), resp.ExpiresIn) // 15 minutes
	assert.Equal(t, "Bearer", resp.TokenType)
}

func TestRefreshService_RefreshTokens_GracePeriodHit(t *testing.T) {
	ctx := context.Background()
	logger := getTestLogger(t)

	mockUserRepo := new(mockUserRepository)
	mockRefreshTokenRepo := new(mockRefreshTokenRepository)
	mockTokenCache := new(mockTokenCache)
	mockGracePeriodCache := new(mockGracePeriodCache)
	mockJWTService := new(mockJWTService)
	mockEventPublisher := new(mockEventPublisher)
	mockAuditService := new(mockAuditService)

	refreshService := service.NewRefreshService(
		mockUserRepo,
		mockRefreshTokenRepo,
		mockTokenCache,
		mockGracePeriodCache,
		mockJWTService,
		mockEventPublisher,
		mockAuditService,
		logger,
	)

	userID := uuid.New().String()
	oldRefreshToken := "old-refresh-token"
	oldTokenHash := service.HashToken(oldRefreshToken)
	now := time.Now()

	storedToken := &entity.RefreshToken{
		ID:        uuid.New().String(),
		UserID:    userID,
		TokenHash: oldTokenHash,
		ExpiresAt: now.Add(-time.Hour),
		RevokedAt: &now, // Already revoked - concurrency case
	}

	// Setup expectations
	mockRefreshTokenRepo.On("GetByTokenHash", ctx, oldTokenHash).Return(storedToken, nil)
	mockGracePeriodCache.On("Get", ctx, oldTokenHash).Return(
		"cached-access-token",
		"cached-refresh-token",
		true, // Found in cache
		nil,
	)

	resp, err := refreshService.RefreshTokens(
		ctx,
		oldRefreshToken,
		"old-jti",
		time.Now().Add(15*time.Minute),
	)

	require.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, "cached-access-token", resp.AccessToken)
	assert.Equal(t, "cached-refresh-token", resp.NewRefreshToken)
}

func TestRefreshService_RefreshTokens_TokenTheft(t *testing.T) {
	ctx := context.Background()
	logger := getTestLogger(t)

	mockUserRepo := new(mockUserRepository)
	mockRefreshTokenRepo := new(mockRefreshTokenRepository)
	mockTokenCache := new(mockTokenCache)
	mockGracePeriodCache := new(mockGracePeriodCache)
	mockJWTService := new(mockJWTService)
	mockEventPublisher := new(mockEventPublisher)
	mockAuditService := new(mockAuditService)

	refreshService := service.NewRefreshService(
		mockUserRepo,
		mockRefreshTokenRepo,
		mockTokenCache,
		mockGracePeriodCache,
		mockJWTService,
		mockEventPublisher,
		mockAuditService,
		logger,
	)

	userID := uuid.New().String()
	oldRefreshToken := "old-refresh-token"
	oldTokenHash := service.HashToken(oldRefreshToken)
	now := time.Now()

	storedToken := &entity.RefreshToken{
		ID:        uuid.New().String(),
		UserID:    userID,
		TokenHash: oldTokenHash,
		ExpiresAt: now.Add(-time.Hour),
		RevokedAt: &now, // Revoked
	}

	// Setup expectations
	mockRefreshTokenRepo.On("GetByTokenHash", ctx, oldTokenHash).Return(storedToken, nil)
	mockGracePeriodCache.On("Get", ctx, oldTokenHash).Return("", "", false, nil) // Not in cache
	mockRefreshTokenRepo.On("RevokeAllForUser", ctx, userID).Return(nil)
	mockTokenCache.On("SetRevokedBefore", ctx, userID, mock.Anything).Return(nil)
	mockAuditService.On("LogTokenTheft", mock.Anything, userID, mock.Anything).Return(nil)
	mockEventPublisher.On("Publish", mock.Anything, mock.Anything).Return(nil)

	_, err := refreshService.RefreshTokens(
		ctx,
		oldRefreshToken,
		"old-jti",
		time.Now().Add(15*time.Minute),
	)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "security") // Token theft detected
}

func TestRefreshService_RefreshTokens_InvalidToken(t *testing.T) {
	ctx := context.Background()
	logger := getTestLogger(t)

	mockUserRepo := new(mockUserRepository)
	mockRefreshTokenRepo := new(mockRefreshTokenRepository)
	mockTokenCache := new(mockTokenCache)
	mockGracePeriodCache := new(mockGracePeriodCache)
	mockJWTService := new(mockJWTService)
	mockEventPublisher := new(mockEventPublisher)
	mockAuditService := new(mockAuditService)

	refreshService := service.NewRefreshService(
		mockUserRepo,
		mockRefreshTokenRepo,
		mockTokenCache,
		mockGracePeriodCache,
		mockJWTService,
		mockEventPublisher,
		mockAuditService,
		logger,
	)

	// Setup - token not found
	mockRefreshTokenRepo.On("GetByTokenHash", ctx, mock.Anything).Return(nil, nil)

	_, err := refreshService.RefreshTokens(
		ctx,
		"nonexistent-token",
		"old-jti",
		time.Now().Add(15*time.Minute),
	)

	assert.Error(t, err)
}

func TestRefreshService_RevokeAllUserTokens(t *testing.T) {
	ctx := context.Background()
	logger := getTestLogger(t)

	mockUserRepo := new(mockUserRepository)
	mockRefreshTokenRepo := new(mockRefreshTokenRepository)
	mockTokenCache := new(mockTokenCache)
	mockGracePeriodCache := new(mockGracePeriodCache)
	mockJWTService := new(mockJWTService)
	mockEventPublisher := new(mockEventPublisher)
	mockAuditService := new(mockAuditService)

	refreshService := service.NewRefreshService(
		mockUserRepo,
		mockRefreshTokenRepo,
		mockTokenCache,
		mockGracePeriodCache,
		mockJWTService,
		mockEventPublisher,
		mockAuditService,
		logger,
	)

	userID := uuid.New().String()

	// Setup expectations
	mockRefreshTokenRepo.On("RevokeAllForUser", ctx, userID).Return(nil)
	mockTokenCache.On("SetRevokedBefore", ctx, userID, mock.Anything).Return(nil)
	mockAuditService.On("LogForceLogout", mock.Anything, userID, "User requested", "").Return(nil)
	mockEventPublisher.On("Publish", mock.Anything, mock.Anything).Return(nil)

	err := refreshService.RevokeAllUserTokens(ctx, userID, "User requested")

	require.NoError(t, err)
	mockRefreshTokenRepo.AssertCalled(t, "RevokeAllForUser", ctx, userID)
}

// Mock JWTService for RefreshService tests
type mockJWTService struct {
	mock.Mock
}

func (m *mockJWTService) GenerateAccessToken(ctx context.Context, user *entity.User) (string, error) {
	args := m.Called(ctx, user)
	return args.String(0), args.Error(1)
}

// Compile-time interface check
var _ service.JWTServicePort = (*mockJWTService)(nil)

// Mock AuditService for RefreshService tests
type mockAuditService struct {
	mock.Mock
}

func (m *mockAuditService) LogLoginSuccess(ctx context.Context, userID, email, ipAddress, deviceID, deviceInfo string) error {
	args := m.Called(ctx, userID, email, ipAddress, deviceID, deviceInfo)
	return args.Error(0)
}

func (m *mockAuditService) LogLoginFailed(ctx context.Context, email, ipAddress, deviceID, deviceInfo, reason string) error {
	args := m.Called(ctx, email, ipAddress, deviceID, deviceInfo, reason)
	return args.Error(0)
}

func (m *mockAuditService) LogLogout(ctx context.Context, userID, ipAddress string) error {
	args := m.Called(ctx, userID, ipAddress)
	return args.Error(0)
}

func (m *mockAuditService) LogForceLogout(ctx context.Context, userID, reason, ipAddress string) error {
	args := m.Called(ctx, userID, reason, ipAddress)
	return args.Error(0)
}

func (m *mockAuditService) LogAccountLocked(ctx context.Context, userID, email, reason string) error {
	args := m.Called(ctx, userID, email, reason)
	return args.Error(0)
}

func (m *mockAuditService) LogAccountUnlocked(ctx context.Context, userID, email string) error {
	args := m.Called(ctx, userID, email)
	return args.Error(0)
}

func (m *mockAuditService) LogTokenRefresh(ctx context.Context, userID, ipAddress string) error {
	args := m.Called(ctx, userID, ipAddress)
	return args.Error(0)
}

func (m *mockAuditService) LogTokenTheft(ctx context.Context, userID, tokenID string) error {
	args := m.Called(ctx, userID, tokenID)
	return args.Error(0)
}

// Compile-time interface check
var _ service.AuditServicePort = (*mockAuditService)(nil)
