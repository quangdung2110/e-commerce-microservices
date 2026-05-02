package security

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/jwks"
)

// JWKSProvider defines the interface for JWKS operations needed by TokenGenerator
type JWKSProvider interface {
	GetActivePrivateKey(ctx context.Context) (*rsa.PrivateKey, string, error)
	GetKeyByKid(ctx context.Context, kid string) (*entity.JwtKey, error)
}

// RS256TokenGenerator implements TokenGenerator using RS256 JWT signing
type RS256TokenGenerator struct {
	jwksService JWKSProvider
	issuer      string
	accessTTL   time.Duration
	logger      *zap.Logger
}

// NewRS256TokenGenerator creates a new RS256 token generator
func NewRS256TokenGenerator(
	jwksService jwks.ServiceInterface,
	issuer string,
	accessTTL time.Duration,
	logger *zap.Logger,
) common.TokenGenerator {
	return &RS256TokenGenerator{
		jwksService: jwksService,
		issuer:      issuer,
		accessTTL:   accessTTL,
		logger:      logger,
	}
}

// RS256AccessTokenClaims represents JWT RS256 claims
type RS256AccessTokenClaims struct {
	jwt.RegisteredClaims
	UserID          string   `json:"user_id"`
	Roles           []string `json:"roles"`
	SessionID       string   `json:"sid"` // Session ID
	AccountType     string   `json:"account_type,omitempty"`
	RestrictionStatus string `json:"restriction_status,omitempty"`
	ParentID        string   `json:"parent_id,omitempty"`
}

// GenerateAccessToken generates JWT access token using RS256
func (g *RS256TokenGenerator) GenerateAccessToken(claims *entity.TokenClaims) (string, error) {
	// Get active private key from JWKS service
	privateKey, kid, err := g.jwksService.GetActivePrivateKey(context.Background())
	if err != nil {
		return "", fmt.Errorf("failed to get signing key: %w", err)
	}

	now := time.Now().UTC()

	// Convert roles to string slice
	roles := make([]string, 0)
	if claims.Role != "" {
		roles = append(roles, string(claims.Role))
	}

	// Build custom claims
	customClaims := RS256AccessTokenClaims{
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    g.issuer,
			Subject:   claims.Subject.String(),
			ID:        claims.JTI,
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(g.accessTTL)),
		},
		UserID:            claims.Subject.String(),
		Roles:             roles,
		SessionID:         claims.SessionID.String(),
		AccountType:       string(claims.AccountType),
		RestrictionStatus: string(claims.RestrictionStatus),
	}

	if claims.ParentID != nil {
		customClaims.ParentID = claims.ParentID.String()
	}

	// Create token with RS256
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, customClaims)
	token.Header["kid"] = kid // Include key ID in header for Gateway verification

	signedToken, err := token.SignedString(privateKey)
	if err != nil {
		return "", fmt.Errorf("failed to sign token: %w", err)
	}

	return signedToken, nil
}

// GenerateRefreshToken generates opaque refresh token
func (g *RS256TokenGenerator) GenerateRefreshToken() (string, error) {
	b := make([]byte, 32)
	_, err := rand.Read(b)
	if err != nil {
		return "", fmt.Errorf("failed to generate random bytes: %w", err)
	}
	return base64.URLEncoding.EncodeToString(b), nil
}

// ValidateAccessToken validates and parses JWT access token
func (g *RS256TokenGenerator) ValidateAccessToken(tokenString string) (*entity.TokenClaims, error) {
	// Parse without verification first to get the key ID
	parser := jwt.NewParser(jwt.WithoutClaimsValidation())
	token, _, err := parser.ParseUnverified(tokenString, jwt.MapClaims{})
	if err != nil {
		return nil, fmt.Errorf("failed to parse token: %w", err)
	}

	// Get key ID from header
	kid, ok := token.Header["kid"].(string)
	if !ok {
		return nil, fmt.Errorf("missing kid in token header")
	}

	// Get the key from JWKS service
	jwtKey, err := g.jwksService.GetKeyByKid(context.Background(), kid)
	if err != nil {
		return nil, fmt.Errorf("failed to get key: %w", err)
	}

	// Parse public key for verification
	publicKey, err := jwtKey.GetPublicKey()
	if err != nil {
		return nil, fmt.Errorf("failed to get public key: %w", err)
	}

	// Parse and verify token
	parsedToken, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return publicKey, nil
	})

	if err != nil {
		return nil, fmt.Errorf("failed to verify token: %w", err)
	}

	if !parsedToken.Valid {
		return nil, fmt.Errorf("invalid token")
	}

	claims, ok := parsedToken.Claims.(jwt.MapClaims)
	if !ok {
		return nil, fmt.Errorf("invalid claims type")
	}

	// Parse claims
	sub, _ := claims["sub"].(string)
	userID, err := uuid.Parse(sub)
	if err != nil {
		return nil, fmt.Errorf("invalid subject: %w", err)
	}

	jti, _ := claims["jti"].(string)
	sid, _ := claims["sid"].(string)
	sessionID, _ := uuid.Parse(sid)

	role, _ := claims["roles"].([]interface{})
	roles := make([]uuid.UUID, 0, len(role))
	for _, r := range role {
		if rStr, ok := r.(string); ok {
			if id, err := uuid.Parse(rStr); err == nil {
				roles = append(roles, id)
			}
		}
	}

	exp, _ := claims["exp"].(float64)
	iat, _ := claims["iat"].(float64)

	tokenClaims := &entity.TokenClaims{
		JTI:       jti,
		SessionID: sessionID,
		Subject:   userID,
		Role:      entity.UserRole(""),
		AccountType: entity.AccountType(claims["account_type"].(string)),
		IssuedAt:  int64(iat),
		ExpiresAt: int64(exp),
	}

	return tokenClaims, nil
}
