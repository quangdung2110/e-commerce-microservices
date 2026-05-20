package service

import (
	"context"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var (
	ErrInvalidToken = errors.New("invalid token")
	ErrExpiredToken = errors.New("token has expired")
)

// JWTClaims represents the claims in an access token
type JWTClaims struct {
	jwt.RegisteredClaims
	UserID uuid.UUID `json:"user_id"`
	Email  string    `json:"email"`
	Roles  []string  `json:"roles"`
	JTI    string    `json:"jti"`
}

type JWTService struct {
	privateKey    *rsa.PrivateKey
	publicKey     *rsa.PublicKey
	jwtKeyRepo    port.JWTKeyRepository
	graceCache    port.GracePeriodCache
	denylist      port.TokenCache
	eventPublisher port.EventPublisher
	auditService  *AuditService
	logger        *zap.Logger
}

func NewJWTService(jwtKeyRepo port.JWTKeyRepository, graceCache port.GracePeriodCache, denylist port.TokenCache, logger *zap.Logger) *JWTService {
	return &JWTService{
		jwtKeyRepo:   jwtKeyRepo,
		graceCache:   graceCache,
		denylist:     denylist,
		logger:       logger.With(zap.String("service", "JWTService")),
	}
}

// SetEventPublisher sets the event publisher for token-related events
func (s *JWTService) SetEventPublisher(publisher port.EventPublisher) {
	s.eventPublisher = publisher
}

// SetAuditService sets the audit service for logging
func (s *JWTService) SetAuditService(auditService *AuditService) {
	s.auditService = auditService
}

// LoadKeys loads RSA keys directly (for development/testing)
func (s *JWTService) LoadKeys(privateKey, publicKey []byte) error {
	var err error
	s.privateKey, err = jwt.ParseRSAPrivateKeyFromPEM(privateKey)
	if err != nil {
		return err
	}
	s.publicKey, err = jwt.ParseRSAPublicKeyFromPEM(publicKey)
	if err != nil {
		return err
	}
	return nil
}

// GenerateAccessToken creates a new JWT access token for the user
func (s *JWTService) GenerateAccessToken(ctx context.Context, user *entity.User) (string, error) {
	now := time.Now()
	jti := uuid.New().String()

	var privateKey *rsa.PrivateKey
	var kid string

	// Try to get key from JWKS repository first
	if s.jwtKeyRepo != nil {
		jwtKey, err := s.jwtKeyRepo.GetActive(ctx)
		if err == nil && jwtKey != nil {
			kid = jwtKey.Kid
			if parsedKey, err := jwt.ParseRSAPrivateKeyFromPEM([]byte(jwtKey.PrivateKey)); err == nil {
				privateKey = parsedKey
			}
		}
	}

	// Fallback to loaded keys
	if privateKey == nil && s.privateKey != nil {
		privateKey = s.privateKey
		kid = "default-key"
	}

	// Development fallback
	if privateKey == nil {
		return "", errors.New("no private key available")
	}

	claims := jwt.RegisteredClaims{
		Issuer:    "auth-service",
		Subject:   user.ID,
		Audience:  jwt.ClaimStrings{"ecommerce-api"},
		ExpiresAt: jwt.NewNumericDate(now.Add(constants.AccessTokenTTLDefault)),
		IssuedAt:  jwt.NewNumericDate(now),
		NotBefore: jwt.NewNumericDate(now),
		ID:        jti,
	}

	userID, _ := uuid.Parse(user.ID)
	customClaims := JWTClaims{
		RegisteredClaims: claims,
		UserID:           userID,
		Email:           user.Email.String(),
		Roles:           user.Roles,
		JTI:             jti,
	}

	token := jwt.NewWithClaims(jwt.SigningMethodRS256, customClaims)
	if kid != "" {
		token.Header["kid"] = kid
	}

	return token.SignedString(privateKey)
}

// ValidateAccessToken validates a JWT access token and returns the claims
func (s *JWTService) ValidateAccessToken(ctx context.Context, tokenString string) (*JWTClaims, error) {
	var publicKey *rsa.PublicKey
	var kid string

	// Parse token without validation first to get the kid
	parser := jwt.NewParser()
	token, _, err := parser.ParseUnverified(tokenString, &JWTClaims{})
	if err != nil {
		return nil, ErrInvalidToken
	}

	// Get kid from header
	if k, ok := token.Header["kid"].(string); ok {
		kid = k
	}

	// Try to get key from JWKS repository
	if s.jwtKeyRepo != nil && kid != "" {
		jwtKey, err := s.jwtKeyRepo.GetByKid(ctx, kid)
		if err == nil && jwtKey != nil {
			if parsedKey, err := jwt.ParseRSAPublicKeyFromPEM([]byte(jwtKey.PublicKey)); err == nil {
				publicKey = parsedKey
			}
		}
	}

	// Fallback to loaded keys
	if publicKey == nil && s.publicKey != nil {
		publicKey = s.publicKey
	}

	// Development fallback
	if publicKey == nil {
		// Try to parse with HMAC for backward compatibility
		return s.validateWithHMAC(tokenString)
	}

	// Validate with RSA
	validToken, err := jwt.ParseWithClaims(tokenString, &JWTClaims{}, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return publicKey, nil
	})

	if err != nil || !validToken.Valid {
		return nil, ErrInvalidToken
	}

	claims, ok := validToken.Claims.(*JWTClaims)
	if !ok {
		return nil, ErrInvalidToken
	}

	// Check denylist
	if s.denylist != nil {
		isDenylisted, err := s.denylist.IsDenylisted(ctx, claims.JTI)
		if err == nil && isDenylisted {
			return nil, ErrInvalidToken
		}
	}

	// Check revoked before timestamp
	if s.denylist != nil {
		revokedBefore, err := s.denylist.GetRevokedBefore(ctx, claims.UserID.String())
		if err == nil && !revokedBefore.IsZero() {
			iat := claims.IssuedAt.Time
			if iat.Before(revokedBefore) {
				return nil, ErrInvalidToken
			}
		}
	}

	return claims, nil
}

// validateWithHMAC validates token with HMAC for backward compatibility
func (s *JWTService) validateWithHMAC(tokenString string) (*JWTClaims, error) {
	secret := []byte("development-secret-key-change-in-production")
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		return secret, nil
	})

	if err != nil || !token.Valid {
		return nil, ErrInvalidToken
	}

	mapClaims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return nil, ErrInvalidToken
	}

	claims := &JWTClaims{}
	if sub, ok := mapClaims["sub"].(string); ok {
		claims.Subject = sub
		claims.UserID, _ = uuid.Parse(sub)
	}
	if email, ok := mapClaims["email"].(string); ok {
		claims.Email = email
	}
	if roles, ok := mapClaims["roles"].([]interface{}); ok {
		claims.Roles = make([]string, len(roles))
		for i, r := range roles {
			if role, ok := r.(string); ok {
				claims.Roles[i] = role
			}
		}
	}
	if jti, ok := mapClaims["jti"].(string); ok {
		claims.JTI = jti
	}

	return claims, nil
}

// ValidateAccessTokenSimple validates a JWT without checking denylist (for refresh endpoint)
func (s *JWTService) ValidateAccessTokenSimple(ctx context.Context, tokenString string) (*JWTClaims, error) {
	var publicKey *rsa.PublicKey
	var kid string

	parser := jwt.NewParser()
	token, _, err := parser.ParseUnverified(tokenString, &JWTClaims{})
	if err != nil {
		return nil, ErrInvalidToken
	}

	if k, ok := token.Header["kid"].(string); ok {
		kid = k
	}

	if s.jwtKeyRepo != nil && kid != "" {
		jwtKey, err := s.jwtKeyRepo.GetByKid(ctx, kid)
		if err == nil && jwtKey != nil {
			if parsedKey, err := jwt.ParseRSAPublicKeyFromPEM([]byte(jwtKey.PublicKey)); err == nil {
				publicKey = parsedKey
			}
		}
	}

	if publicKey == nil && s.publicKey != nil {
		publicKey = s.publicKey
	}

	if publicKey == nil {
		return s.validateWithHMAC(tokenString)
	}

	validToken, err := jwt.ParseWithClaims(tokenString, &JWTClaims{}, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return publicKey, nil
	})

	if err != nil || !validToken.Valid {
		return nil, ErrInvalidToken
	}

	claims, ok := validToken.Claims.(*JWTClaims)
	if !ok {
		return nil, ErrInvalidToken
	}

	return claims, nil
}

// HashToken creates SHA-256 hash of a token
func HashToken(token string) string {
	hash := sha256.Sum256([]byte(token))
	return hex.EncodeToString(hash[:])
}

// GetTokenExpiry returns the expiry time from a token string
func (s *JWTService) GetTokenExpiry(tokenString string) (time.Time, error) {
	claims, err := s.ValidateAccessTokenSimple(context.Background(), tokenString)
	if err != nil {
		return time.Time{}, err
	}
	return claims.ExpiresAt.Time, nil
}

// IsTokenExpired checks if a token is expired
func (s *JWTService) IsTokenExpired(tokenString string) bool {
	expiry, err := s.GetTokenExpiry(tokenString)
	if err != nil {
		return true
	}
	return time.Now().After(expiry)
}

// GetJTIFromToken extracts the JTI from a token without validation
func (s *JWTService) GetJTIFromToken(tokenString string) (string, error) {
	parser := jwt.NewParser()
	token, _, err := parser.ParseUnverified(tokenString, &JWTClaims{})
	if err != nil {
		return "", err
	}

	claims, ok := token.Claims.(*JWTClaims)
	if !ok {
		return "", errors.New("invalid claims")
	}

	return claims.JTI, nil
}

// GetExpiryFromToken extracts the expiry time from a token without validation
func (s *JWTService) GetExpiryFromToken(tokenString string) (time.Time, error) {
	parser := jwt.NewParser()
	token, _, err := parser.ParseUnverified(tokenString, &JWTClaims{})
	if err != nil {
		return time.Time{}, err
	}

	claims, ok := token.Claims.(*JWTClaims)
	if !ok {
		return time.Time{}, errors.New("invalid claims")
	}

	return claims.ExpiresAt.Time, nil
}
