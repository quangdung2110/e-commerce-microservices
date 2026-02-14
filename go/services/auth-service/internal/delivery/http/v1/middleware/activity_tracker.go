package v1_middleware

import (
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

const (
	activityFlagTTL = 5 * time.Minute // Update DB at most once per 5 minutes
)

// ActivityTrackerMiddleware tracks user session activity with DB write throttling
// Uses Redis flag to prevent excessive DB updates (max 1 update per 5 minutes)
func ActivityTrackerMiddleware(logger *zap.Logger, sessionRepo repository.SessionRepository, rateLimitRepo repository.RateLimitRepository) gin.HandlerFunc {
	return func(c *gin.Context) {
		// Get session_id from context (should be set by auth middleware)
		sessionIDStr, exists := c.Get("session_id")
		if !exists {
			// No session_id means not authenticated or session not tracked
			c.Next()
			return
		}

		sessionID, err := uuid.Parse(sessionIDStr.(string))
		if err != nil {
			logger.Error("Invalid session_id format", zap.Error(err))
			c.Next()
			return
		}

		// Check Redis activity flag
		exists, err = rateLimitRepo.CheckActivityFlag(c.Request.Context(), sessionID)
		if err != nil {
			logger.Error("Failed to check activity flag", zap.Error(err), zap.String("session_id", sessionID.String()))
			// On error, skip update to avoid blocking request
			c.Next()
			return
		}

		if exists {
			// Flag exists, skip DB update (already updated in last 5 minutes)
			c.Next()
			return
		}

		// Flag doesn't exist or expired, update DB
		// Do this asynchronously to not block the request
		go func() {
			ctx := c.Request.Context()

			// Update last_activity in PostgreSQL
			if err := sessionRepo.UpdateLastActivity(ctx, sessionID); err != nil {
				logger.Error("Failed to update session activity",
					zap.Error(err),
					zap.String("session_id", sessionID.String()))
				return
			}

			// Set Redis flag with 5-minute TTL
			if err := rateLimitRepo.SetActivityFlag(ctx, sessionID, activityFlagTTL); err != nil {
				logger.Error("Failed to set activity flag",
					zap.Error(err),
					zap.String("session_id", sessionID.String()))
			}

			logger.Debug("Session activity updated",
				zap.String("session_id", sessionID.String()))
		}()

		c.Next()
	}
}
