package session

import (
	"context"
	"fmt"
	"math"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
)

const (
	// Impossible travel constants
	impossibleTravelSpeedKmH = 800.0  // 800 km/h (commercial jet speed)
	earthRadiusKm            = 6371.0 // Earth's radius in kilometers
)

// ManagerUsecase handles session management
type ManagerUsecase struct {
	sessionRepo      repository.SessionRepository
	refreshTokenRepo repository.RefreshTokenRepository
	eventPublisher   common.EventPublisher
	logger           *zap.Logger
}

// NewManagerUsecase creates new session manager usecase
func NewManagerUsecase(
	sessionRepo repository.SessionRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	eventPublisher common.EventPublisher,
	logger *zap.Logger,
) *ManagerUsecase {
	return &ManagerUsecase{
		sessionRepo:      sessionRepo,
		refreshTokenRepo: refreshTokenRepo,
		eventPublisher:   eventPublisher,
		logger:           logger,
	}
}

// ListSessions lists all active sessions for user
func (uc *ManagerUsecase) ListSessions(ctx context.Context, userID uuid.UUID, currentSessionID *uuid.UUID) (*dto.ListSessionsResponse, error) {
	sessions, err := uc.sessionRepo.GetActiveByUserID(ctx, userID)
	if err != nil {
		return nil, fmt.Errorf("failed to find sessions: %w", err)
	}

	sessionInfos := make([]dto.SessionInfo, 0, len(sessions))
	for _, session := range sessions {
		isCurrent := false
		if currentSessionID != nil && session.SessionID == *currentSessionID {
			isCurrent = true
		}

		sessionInfo := dto.SessionInfo{
			SessionID:  session.SessionID,
			DeviceID:   session.DeviceID,
			DeviceName: session.DeviceName,
			DeviceType: session.DeviceType,
			IPAddress:  session.IPAddress,
			GeoCountry: session.GeoCountry,
			GeoCity:    session.GeoCity,
			CreatedAt:  session.CreatedAt,
			ExpiresAt:  session.ExpiresAt,
			IsCurrent:  isCurrent,
		}

		sessionInfos = append(sessionInfos, sessionInfo)
	}

	return &dto.ListSessionsResponse{
		Sessions: sessionInfos,
		Total:    len(sessionInfos),
	}, nil
}

// GetSessionInfo gets detailed session information
func (uc *ManagerUsecase) GetSessionInfo(ctx context.Context, userID uuid.UUID, sessionID uuid.UUID) (*dto.SessionInfo, error) {
	session, err := uc.sessionRepo.GetByID(ctx, sessionID)
	if err != nil {
		return nil, serr.ErrSessionNotFound
	}

	// Verify ownership
	if session.UserID != userID {
		return nil, serr.ErrSessionNotFound
	}

	sessionInfo := dto.SessionInfo{
		SessionID:  session.SessionID,
		DeviceID:   session.DeviceID,
		DeviceName: session.DeviceName,
		DeviceType: session.DeviceType,
		IPAddress:  session.IPAddress,
		GeoCountry: session.GeoCountry,
		GeoCity:    session.GeoCity,
		CreatedAt:  session.CreatedAt,
		ExpiresAt:  session.ExpiresAt,
		IsCurrent:  false,
	}

	return &sessionInfo, nil
}

// RevokeSession revokes specific session
func (uc *ManagerUsecase) RevokeSession(ctx context.Context, userID uuid.UUID, sessionID uuid.UUID) error {
	// Get session
	session, err := uc.sessionRepo.GetByID(ctx, sessionID)
	if err != nil {
		return serr.ErrSessionNotFound
	}

	// Verify ownership
	if session.UserID != userID {
		return serr.ErrSessionNotFound
	}

	// Delete refresh token
	deviceID := ""
	if session.DeviceID != nil {
		deviceID = *session.DeviceID
	}
	if err := uc.refreshTokenRepo.Delete(ctx, userID, deviceID); err != nil {
		uc.logger.Error("Failed to delete refresh token", zap.Error(err))
	}

	// Revoke session
	if err := uc.sessionRepo.RevokeByID(ctx, sessionID); err != nil {
		return fmt.Errorf("failed to revoke session: %w", err)
	}

	// Publish event
	// if err := uc.eventPublisher.PublishSessionRevoked(ctx, userID, sessionID); err != nil {
	// 	uc.logger.Error("Failed to publish session revoked event", zap.Error(err))
	// }

	uc.logger.Info("Session revoked",
		zap.String("user_id", userID.String()),
		zap.String("session_id", sessionID.String()))

	return nil
}

// RevokeAllSessions revokes all sessions except current
func (uc *ManagerUsecase) RevokeAllSessions(ctx context.Context, userID uuid.UUID, exceptSessionID uuid.UUID) (int, error) {
	sessions, err := uc.sessionRepo.GetActiveByUserID(ctx, userID)
	if err != nil {
		return 0, fmt.Errorf("failed to find sessions: %w", err)
	}

	revokedCount := 0
	for _, session := range sessions {
		// Skip current session if specified
		if session.SessionID == exceptSessionID {
			continue
		}

		// Delete refresh token
		deviceID := ""
		if session.DeviceID != nil {
			deviceID = *session.DeviceID
		}
		uc.refreshTokenRepo.Delete(ctx, userID, deviceID)

		// Revoke session
		if err := uc.sessionRepo.RevokeByID(ctx, session.SessionID); err != nil {
			uc.logger.Error("Failed to revoke session", zap.Error(err))
		}

		revokedCount++
	}

	uc.logger.Info("Sessions revoked",
		zap.String("user_id", userID.String()),
		zap.Int("count", revokedCount))

	return revokedCount, nil
}

// DetectImpossibleTravel detects impossible travel between login locations
func (uc *ManagerUsecase) DetectImpossibleTravel(ctx context.Context, userID uuid.UUID, newLocation *entity.GeoLocation) (*dto.ImpossibleTravelAlert, error) {
	if newLocation == nil || newLocation.Latitude == 0 || newLocation.Longitude == 0 {
		return nil, nil
	}

	// Get recent sessions (last 24 hours)
	sessions, err := uc.sessionRepo.GetRecentByUser(ctx, userID, 24*time.Hour)
	if err != nil || len(sessions) == 0 {
		return nil, nil
	}

	// Find most recent session with location
	var lastSession *entity.GeoLocation
	var lastActivityTime time.Time

	for _, session := range sessions {
		if session.Location != nil && session.Location.Latitude != 0 && session.Location.Longitude != 0 {
			lastSession = session.Location
			lastActivityTime = session.LastActivity // Use last_activity instead of created_at
			break
		}
	}

	if lastSession == nil {
		return nil, nil
	}

	// Calculate distance using Haversine formula
	distanceKm := calculateDistance(
		lastSession.Latitude, lastSession.Longitude,
		newLocation.Latitude, newLocation.Longitude,
	)

	// Calculate time difference based on last_activity
	timeDiff := time.Since(lastActivityTime)
	hoursElapsed := timeDiff.Hours()

	// Avoid division by zero
	if hoursElapsed < 0.01 { // Less than ~36 seconds
		return nil, nil
	}

	// Calculate required speed
	requiredSpeedKmH := distanceKm / hoursElapsed

	// Check if travel is impossible
	if requiredSpeedKmH > impossibleTravelSpeedKmH {
		alert := &dto.ImpossibleTravelAlert{
			Detected:            true,
			PreviousLocation:    lastSession,
			CurrentLocation:     newLocation,
			DistanceKm:          distanceKm,
			TimeDifferenceHours: hoursElapsed,
			RequiredSpeedKmH:    requiredSpeedKmH,
			Suspicious:          true,
		}

		// Publish security alert
		uc.publishSuspiciousActivity(ctx, userID, "IMPOSSIBLE_TRAVEL")

		uc.logger.Warn("Impossible travel detected",
			zap.String("user_id", userID.String()),
			zap.Float64("distance_km", distanceKm),
			zap.Float64("time_hours", hoursElapsed),
			zap.Float64("required_speed_kmh", requiredSpeedKmH),
			zap.Time("last_activity_at", lastActivityTime))

		return alert, nil
	}

	return nil, nil
}

// ==================== HELPER METHODS ====================

func (uc *ManagerUsecase) publishSuspiciousActivity(
	ctx context.Context,
	userID uuid.UUID,
	activityType string) error {
	event := &common.SuspiciousActivityEvent{
		UserID:       &userID,
		ActivityType: activityType,
		Timestamp:    time.Now().UTC(),
	}
	return uc.eventPublisher.PublishSuspiciousActivity(ctx, event)
}

func calculateDistance(lat1, lon1, lat2, lon2 float64) float64 {
	// Convert degrees to radians
	lat1Rad := degreesToRadians(lat1)
	lon1Rad := degreesToRadians(lon1)
	lat2Rad := degreesToRadians(lat2)
	lon2Rad := degreesToRadians(lon2)

	// Haversine formula
	dLat := lat2Rad - lat1Rad
	dLon := lon2Rad - lon1Rad

	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1Rad)*math.Cos(lat2Rad)*
			math.Sin(dLon/2)*math.Sin(dLon/2)

	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))

	distance := earthRadiusKm * c
	return distance
}

func degreesToRadians(degrees float64) float64 {
	return degrees * math.Pi / 180
}

func getStringOrDefault(s *string) string {
	if s == nil {
		return "Unknown"
	}
	return *s
}
