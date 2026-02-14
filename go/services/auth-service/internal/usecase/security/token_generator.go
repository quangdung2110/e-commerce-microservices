package security

import (
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

// JWTTokenGenerator implements TokenGenerator using JWT
type JWTTokenGenerator struct {
	accessSecret  string
	refreshSecret string
	issuer        string
}

// NewJWTTokenGenerator creates new JWT token generator
func NewJWTTokenGenerator(accessSecret, refreshSecret, issuer string) common.TokenGenerator {
	return &JWTTokenGenerator{
		accessSecret:  accessSecret,
		refreshSecret: refreshSecret,
		issuer:        issuer,
	}
}

// GenerateAccessToken generates JWT access token
func (g *JWTTokenGenerator) GenerateAccessToken(claims *entity.TokenClaims) (string, error) {
	now := time.Now().UTC()

	jwtClaims := jwt.MapClaims{
		"jti":                claims.JTI,
		"sub":                claims.Subject.String(),
		"type":               string(claims.Type),
		"role":               string(claims.Role),
		"account_type":       string(claims.AccountType),
		"restriction_status": string(claims.RestrictionStatus),
		"iss":                g.issuer,
		"iat":                now.Unix(),
		"exp":                claims.ExpiresAt,
	}

	if claims.ParentID != nil {
		jwtClaims["parent_id"] = claims.ParentID.String()
	}

	if len(claims.SellerIDs) > 0 {
		sellerIDs := make([]string, len(claims.SellerIDs))
		for i, id := range claims.SellerIDs {
			sellerIDs[i] = id.String()
		}
		jwtClaims["seller_ids"] = sellerIDs
	}

	if claims.ActiveSellerID != nil {
		jwtClaims["active_seller_id"] = claims.ActiveSellerID.String()
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwtClaims)

	tokenString, err := token.SignedString([]byte(g.accessSecret))
	if err != nil {
		return "", fmt.Errorf("failed to sign token: %w", err)
	}

	return tokenString, nil
}

// GenerateRefreshToken generates opaque refresh token
func (g *JWTTokenGenerator) GenerateRefreshToken() (string, error) {
	return utils.GenerateRandomToken(32)
}

// VerifyAccessToken verifies and parses JWT token
func (g *JWTTokenGenerator) ValidateAccessToken(tokenString string) (*entity.TokenClaims, error) {
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return []byte(g.accessSecret), nil
	})

	if err != nil {
		return nil, fmt.Errorf("failed to parse token: %w", err)
	}

	if !token.Valid {
		return nil, fmt.Errorf("invalid token")
	}

	claims, ok := token.Claims.(jwt.MapClaims)
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
	tokenType, _ := claims["type"].(string)
	role, _ := claims["role"].(string)
	accountType, _ := claims["account_type"].(string)
	restrictionStatus, _ := claims["restriction_status"].(string)

	exp, _ := claims["exp"].(float64)
	iat, _ := claims["iat"].(float64)

	tokenClaims := &entity.TokenClaims{
		JTI:               jti,
		Subject:           userID,
		Type:              entity.TokenType(tokenType),
		Role:              entity.UserRole(role),
		AccountType:       entity.AccountType(accountType),
		RestrictionStatus: entity.RestrictionStatus(restrictionStatus),
		IssuedAt:          int64(iat),
		ExpiresAt:         int64(exp),
	}

	// Optional fields
	if parentIDStr, ok := claims["parent_id"].(string); ok {
		parentID, _ := uuid.Parse(parentIDStr)
		tokenClaims.ParentID = &parentID
	}

	if sellerIDsInterface, ok := claims["seller_ids"].([]interface{}); ok {
		sellerIDs := make([]uuid.UUID, 0, len(sellerIDsInterface))
		for _, idInterface := range sellerIDsInterface {
			if idStr, ok := idInterface.(string); ok {
				if id, err := uuid.Parse(idStr); err == nil {
					sellerIDs = append(sellerIDs, id)
				}
			}
		}
		tokenClaims.SellerIDs = sellerIDs
	}

	if activeSellerIDStr, ok := claims["active_seller_id"].(string); ok {
		activeSellerID, _ := uuid.Parse(activeSellerIDStr)
		tokenClaims.ActiveSellerID = &activeSellerID
	}

	return tokenClaims, nil
}
