package service_test

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// Helper function to generate RSA key pair for testing
func generateTestRSAKeys(t *testing.T) ([]byte, []byte) {
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)

	privateKeyBytes := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(privateKey),
	})

	publicKeyBytes, err := x509.MarshalPKIKeyInfo(&privateKey.PublicKey, false)
	require.NoError(t, err)

	publicKeyBytes = pem.EncodeToMemory(&pem.Block{
		Type:  "PUBLIC KEY",
		Bytes: publicKeyBytes,
	})

	return privateKeyBytes, publicKeyBytes
}

// Helper to generate test JWT key entity
func generateTestJWTKey(t *testing.T, status string) *entity.JWTKey {
	privateKey, publicKey := generateTestRSAKeys(t)
	return &entity.JWTKey{
		Kid:        "test-key-" + uuid.New().String(),
		PublicKey:  string(publicKey),
		PrivateKey: string(privateKey),
		Status:     status,
		CreatedAt:  time.Now(),
	}
}

func TestJWTService_GenerateAndValidate(t *testing.T) {
	ctx := context.Background()
	mockGraceCache := new(mockGracePeriodCache)
	mockDenylist := new(mockTokenCache)
	logger := getTestLogger(t)

	jwtService := service.NewJWTService(nil, mockGraceCache, mockDenylist, logger)

	// Load keys directly for testing
	privateKey, publicKey := generateTestRSAKeys(t)
	err := jwtService.LoadKeys(privateKey, publicKey)
	require.NoError(t, err)

	// Create test user
	user := &entity.User{
		ID:     uuid.New(),
		Email:  mustParseEmail(t, "test@example.com"),
		Roles:  []string{"USER"},
		Status: entity.StatusActive,
	}

	// Generate token
	token, err := jwtService.GenerateAccessToken(ctx, user)
	require.NoError(t, err)
	assert.NotEmpty(t, token)

	// Validate token
	claims, err := jwtService.ValidateAccessToken(ctx, token)
	require.NoError(t, err)
	assert.Equal(t, user.ID.String(), claims.UserID.String())
	assert.Equal(t, user.Email.String(), claims.Email)
	assert.Equal(t, user.Roles, claims.Roles)
	assert.NotEmpty(t, claims.JTI)
}

func TestJWTService_ValidateExpiredToken(t *testing.T) {
	ctx := context.Background()
	mockGraceCache := new(mockGracePeriodCache)
	mockDenylist := new(mockTokenCache)
	logger := getTestLogger(t)

	jwtService := service.NewJWTService(nil, mockGraceCache, mockDenylist, logger)

	privateKey, publicKey := generateTestRSAKeys(t)
	err := jwtService.LoadKeys(privateKey, publicKey)
	require.NoError(t, err)

	// Create an expired token manually
	now := time.Now()
	claims := jwt.MapClaims{
		"sub":   uuid.New().String(),
		"email": "test@example.com",
		"roles": []string{"USER"},
		"jti":   uuid.New().String(),
		"iat":   now.Add(-30 * time.Minute).Unix(),
		"exp":   now.Add(-1 * time.Minute).Unix(), // Expired 1 minute ago
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	expiredToken, err := token.SignedString(privateKey)
	require.NoError(t, err)

	// Try to validate - should fail
	_, err = jwtService.ValidateAccessToken(ctx, expiredToken)
	assert.Error(t, err)
}

func TestJWTService_GetTokenExpiry(t *testing.T) {
	mockGraceCache := new(mockGracePeriodCache)
	mockDenylist := new(mockTokenCache)
	logger := getTestLogger(t)

	jwtService := service.NewJWTService(nil, mockGraceCache, mockDenylist, logger)

	privateKey, publicKey := generateTestRSAKeys(t)
	err := jwtService.LoadKeys(privateKey, publicKey)
	require.NoError(t, err)

	// Create a token
	user := &entity.User{
		ID:     uuid.New(),
		Email:  mustParseEmail(t, "test@example.com"),
		Roles:  []string{"USER"},
		Status: entity.StatusActive,
	}

	token, err := jwtService.GenerateAccessToken(context.Background(), user)
	require.NoError(t, err)

	// Get expiry
	expiry, err := jwtService.GetTokenExpiry(token)
	require.NoError(t, err)

	// Should be approximately 15 minutes from now
	expectedExpiry := time.Now().Add(15 * time.Minute)
	assert.WithinDuration(t, expectedExpiry, expiry, 2*time.Minute)
}

func TestJWTService_GetJTIFromToken(t *testing.T) {
	mockGraceCache := new(mockGracePeriodCache)
	mockDenylist := new(mockTokenCache)
	logger := getTestLogger(t)

	jwtService := service.NewJWTService(nil, mockGraceCache, mockDenylist, logger)

	privateKey, publicKey := generateTestRSAKeys(t)
	err := jwtService.LoadKeys(privateKey, publicKey)
	require.NoError(t, err)

	user := &entity.User{
		ID:     uuid.New(),
		Email:  mustParseEmail(t, "test@example.com"),
		Roles:  []string{"USER"},
		Status: entity.StatusActive,
	}

	token, err := jwtService.GenerateAccessToken(context.Background(), user)
	require.NoError(t, err)

	jti, err := jwtService.GetJTIFromToken(token)
	require.NoError(t, err)
	assert.NotEmpty(t, jti)
}

func TestJWTService_IsTokenExpired(t *testing.T) {
	mockGraceCache := new(mockGracePeriodCache)
	mockDenylist := new(mockTokenCache)
	logger := getTestLogger(t)

	jwtService := service.NewJWTService(nil, mockGraceCache, mockDenylist, logger)

	privateKey, publicKey := generateTestRSAKeys(t)
	err := jwtService.LoadKeys(privateKey, publicKey)
	require.NoError(t, err)

	user := &entity.User{
		ID:     uuid.New(),
		Email:  mustParseEmail(t, "test@example.com"),
		Roles:  []string{"USER"},
		Status: entity.StatusActive,
	}

	token, err := jwtService.GenerateAccessToken(context.Background(), user)
	require.NoError(t, err)

	// Fresh token should not be expired
	assert.False(t, jwtService.IsTokenExpired(token))

	// Invalid token should be considered expired
	assert.True(t, jwtService.IsTokenExpired("invalid-token"))
}

// Helper functions

func getTestLogger(t *testing.T) *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

func mustParseEmail(t *testing.T, emailStr string) entity.Email {
	email, err := entity.NewEmail(emailStr)
	if err != nil {
		t.Fatalf("Failed to parse email: %v", err)
	}
	return email
}
