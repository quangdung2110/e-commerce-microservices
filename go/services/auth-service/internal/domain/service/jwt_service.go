package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/entity"
)

var (
	ErrInvalidToken = errors.New("invalid token")
	ErrExpiredToken = errors.New("token has expired")
)

type JWTClaims struct {
	UserID string   `json:"sub"`
	Email  string   `json:"email"`
	Roles  []string `json:"roles"`
	JTI    string   `json:"jti"`
	IAT    int64    `json:"iat"`
	EXP    int64    `json:"exp"`
}

type JWTService struct {
	privateKey []byte
	publicKey  []byte
	logger     *zap.Logger
}

func NewJWTService(logger *zap.Logger) *JWTService {
	return &JWTService{
		logger: logger.With(zap.String("service", "JWTService")),
	}
}

// TODO: Load keys from JWKS or key management system (Module 9)
// For now, using a placeholder key for development
func (s *JWTService) LoadKeys(privateKey, publicKey []byte) {
	s.privateKey = privateKey
	s.publicKey = publicKey
}

// GenerateAccessToken creates a new JWT access token for the user
func (s *JWTService) GenerateAccessToken(ctx context.Context, user *entity.User) (string, error) {
	now := time.Now()
	jti := uuid.New().String()

	claims := jwt.MapClaims{
		"sub":   user.ID,
		"email": user.Email.String(),
		"roles": user.Roles,
		"jti":   jti,
		"iat":   now.Unix(),
		"exp":   now.Add(constants.AccessTokenTTLDefault).Unix(),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)

	if len(s.privateKey) > 0 {
		return token.SignedString(s.privateKey)
	}

	// Fallback for development - use a simple secret
	secret := []byte("development-secret-key-change-in-production")
	return token.SignedString(secret)
}

// ValidateAccessToken validates a JWT access token and returns the claims
func (s *JWTService) ValidateAccessToken(ctx context.Context, tokenString string) (*JWTClaims, error) {
	var claims jwt.MapClaims
	var token *jwt.Token
	var err error

	if len(s.publicKey) > 0 {
		token, err = jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, ErrInvalidToken
			}
			return s.publicKey, nil
		})
	} else {
		secret := []byte("development-secret-key-change-in-production")
		token, err = jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			return secret, nil
		})
	}

	if err != nil {
		s.logger.Debug("Token validation failed", zap.Error(err))
		return nil, ErrInvalidToken
	}

	if !token.Valid {
		return nil, ErrInvalidToken
	}

	mapClaims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return nil, ErrInvalidToken
	}
	claims = mapClaims

	jwtClaims := &JWTClaims{}

	if sub, ok := claims["sub"].(string); ok {
		jwtClaims.UserID = sub
	}
	if email, ok := claims["email"].(string); ok {
		jwtClaims.Email = email
	}
	if roles, ok := claims["roles"].([]interface{}); ok {
		jwtClaims.Roles = make([]string, len(roles))
		for i, r := range roles {
			if role, ok := r.(string); ok {
				jwtClaims.Roles[i] = role
			}
		}
	}
	if jti, ok := claims["jti"].(string); ok {
		jwtClaims.JTI = jti
	}
	if iat, ok := claims["iat"].(float64); ok {
		jwtClaims.IAT = int64(iat)
	}
	if exp, ok := claims["exp"].(float64); ok {
		jwtClaims.EXP = int64(exp)
	}

	return jwtClaims, nil
}

// HashToken creates SHA-256 hash of a token
func HashToken(token string) string {
	hash := sha256.Sum256([]byte(token))
	return hex.EncodeToString(hash[:])
}

// GetTokenExpiry returns the expiry time from a token string
func (s *JWTService) GetTokenExpiry(tokenString string) (time.Time, error) {
	claims, err := s.ValidateAccessToken(context.Background(), tokenString)
	if err != nil {
		return time.Time{}, err
	}
	return time.Unix(claims.EXP, 0), nil
}

// IsTokenExpired checks if a token is expired
func (s *JWTService) IsTokenExpired(tokenString string) bool {
	expiry, err := s.GetTokenExpiry(tokenString)
	if err != nil {
		return true
	}
	return time.Now().After(expiry)
}
