package middleware

import (
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

type AuthMiddleware struct {
	jwtService *service.JWTService
	tokenCache port.TokenCache
	logger     *zap.Logger
}

func NewAuthMiddleware(jwtService *service.JWTService, tokenCache port.TokenCache, logger *zap.Logger) *AuthMiddleware {
	return &AuthMiddleware{
		jwtService: jwtService,
		tokenCache: tokenCache,
		logger:     logger.With(zap.String("middleware", "Auth")),
	}
}

// Auth returns a middleware that validates JWT tokens
func (m *AuthMiddleware) Auth() gin.HandlerFunc {
	return func(c *gin.Context) {
		logger := m.logger.With(
			zap.String("path", c.Request.URL.Path),
			zap.String("method", c.Request.Method),
			zap.String("client_ip", c.ClientIP()),
		)

		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			logger.Debug("Missing authorization header")
			abortWithError(c, http.StatusUnauthorized, "UNAUTHORIZED", "Authorization header required")
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || strings.ToLower(parts[0]) != "bearer" {
			logger.Debug("Invalid authorization header format")
			abortWithError(c, http.StatusUnauthorized, "INVALID_TOKEN", "Invalid authorization header format")
			return
		}
		tokenString := parts[1]

		claims, err := m.jwtService.ValidateAccessToken(c.Request.Context(), tokenString)
		if err != nil {
			logger.Info("Token validation failed", zap.Error(err))
			abortWithError(c, http.StatusUnauthorized, "INVALID_TOKEN", "Invalid or expired token")
			return
		}

		if m.tokenCache != nil {
			isDenylisted, err := m.tokenCache.IsDenylisted(c.Request.Context(), claims.JTI)
			if err != nil {
				logger.Error("Failed to check token denylist", zap.String("jti", claims.JTI), zap.Error(err))
			} else if isDenylisted {
				logger.Info("Token is denylisted (revoked)",
					zap.String("jti", claims.JTI),
					zap.String("user_id", claims.UserID),
				)
				abortWithError(c, http.StatusUnauthorized, "TOKEN_REVOKED", "Token has been revoked")
				return
			}

			revokedBefore, err := m.tokenCache.GetRevokedBefore(c.Request.Context(), claims.UserID)
			if err == nil && !revokedBefore.IsZero() {
				issuedAt := time.Unix(claims.IAT, 0)
				if issuedAt.Before(revokedBefore) || issuedAt.Equal(revokedBefore) {
					logger.Info("Token issued before revocation timestamp",
						zap.String("user_id", claims.UserID),
						zap.Time("issued_at", issuedAt),
						zap.Time("revoked_before", revokedBefore),
					)
					abortWithError(c, http.StatusUnauthorized, "TOKEN_REVOKED", "Token has been revoked")
					return
				}
			}
		}

		c.Set("user_id", claims.UserID)
		c.Set("roles", claims.Roles)
		c.Set("jti", claims.JTI)
		c.Set("exp", time.Unix(claims.EXP, 0))

		logger.Debug("Token validated successfully",
			zap.String("user_id", claims.UserID),
			zap.Strings("roles", claims.Roles),
		)

		c.Next()
	}
}

// RequireRole returns a middleware that checks for required roles
func (m *AuthMiddleware) RequireRole(roles ...string) gin.HandlerFunc {
	return func(c *gin.Context) {
		userRoles, exists := c.Get("roles")
		if !exists {
			abortWithError(c, http.StatusForbidden, "FORBIDDEN", "No roles found")
			return
		}

		roleList, ok := userRoles.([]string)
		if !ok {
			abortWithError(c, http.StatusInternalServerError, "INTERNAL_ERROR", "Invalid roles format")
			return
		}

		for _, requiredRole := range roles {
			for _, userRole := range roleList {
				if userRole == requiredRole {
					c.Next()
					return
				}
			}
		}

		abortWithError(c, http.StatusForbidden, "FORBIDDEN", "Insufficient permissions")
	}
}

func abortWithError(c *gin.Context, status int, code, message string) {
	c.AbortWithStatusJSON(status, gin.H{
		"error": gin.H{
			"code":    code,
			"message": message,
		},
	})
}
