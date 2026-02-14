package v1_middleware

import (
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"go.uber.org/zap"
)

// AuthMiddleware validates JWT tokens
func AuthMiddleware(logger *zap.Logger, tokenGenerator common.TokenGenerator, blacklistRepo repository.BlacklistRepository) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			logger.Error("Authorization header is missing")
			v1.ErrorResponse(c, serr.ErrUnauthorized)
			c.Abort()
			return
		}

		// Extract token from Bearer scheme
		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			logger.Error("Invalid authorization header format")
			v1.ErrorResponse(c, serr.ErrTokenInvalid)
			c.Abort()
			return
		}

		token := parts[1]
		if token == "" {
			logger.Error("Empty token provided")
			v1.ErrorResponse(c, serr.ErrUnauthorized)
			c.Abort()
			return
		}
		// Validate token
		claim, err := tokenGenerator.ValidateAccessToken(token)
		if err != nil {
			logger.Error("Failed to validate access token", zap.Error(err))
			v1.ErrorResponse(c, serr.ErrUnauthorized)
			c.Abort()
			return
		}
		// Check if token is blacklisted
		exists, err := blacklistRepo.IsBlacklisted(c, types.TokenBlacklist, claim.JTI)
		if err != nil {
			logger.Error("Failed to check token blacklist", zap.Error(err))
			v1.ErrorResponse(c, serr.ErrInternal)
			c.Abort()
			return
		}
		if exists {
			logger.Info("Token is blacklisted", zap.String("jti", claim.JTI))
			v1.ErrorResponse(c, serr.ErrUnauthorized)
			c.Abort()
			return
		}
		logoutTimestampStr, err := blacklistRepo.Get(c, types.UserBlacklist, claim.Subject.String())
		if err != nil {
			logger.Error("Failed to check user blacklist", zap.Error(err))
			v1.ErrorResponse(c, serr.ErrInternal)
			c.Abort()
			return
		}
		if logoutTimestampStr != "" {
			logoutTimestamp, err := time.Parse(time.RFC3339, logoutTimestampStr)
			if err != nil {
				logger.Error("Failed to parse logout timestamp", zap.Error(err))
				v1.ErrorResponse(c, serr.ErrInternal)
				c.Abort()
				return
			}
			if claim.IssuedAt < logoutTimestamp.Unix() {
				logger.Info("Token issued before user logout", zap.String("user_id", claim.Subject.String()))
				v1.ErrorResponse(c, serr.ErrUnauthorized)
				c.Abort()
				return
			}
		}

		c.Set("user_id", claim.Subject)
		c.Set("user_role", claim.Role)
		c.Set("jti", claim.JTI)
		c.Set("token_exp", claim.ExpiresAt)
		c.Set("session_id", claim.SessionID)

		c.Next()
	}
}
