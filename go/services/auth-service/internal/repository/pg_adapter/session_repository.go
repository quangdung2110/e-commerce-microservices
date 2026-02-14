package pg_adapter

import (
	"context"
	"errors"
	"net"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/repository/pg_adapter/generated"
)

type PostgresSessionRepository struct {
	pool    *pgxpool.Pool
	querier generated.Querier
	logger  *zap.Logger
}

func NewPostgresSessionRepository(pool *pgxpool.Pool, logger *zap.Logger) repository.SessionRepository {
	return &PostgresSessionRepository{
		pool:    pool,
		querier: generated.New(pool),
		logger:  logger,
	}
}

func (r *PostgresSessionRepository) Create(ctx context.Context, session *entity.Session) error {
	params := generated.CreateSessionParams{
		SessionID:        session.SessionID,
		UserID:           session.UserID,
		DeviceID:         session.DeviceID,
		DeviceName:       session.DeviceName,
		DeviceType:       generated.DeviceType(session.DeviceType),
		IpAddress:        net.ParseIP(session.IPAddress),
		UserAgent:        &session.UserAgent,
		GeoCountry:       session.GeoCountry,
		GeoCity:          session.GeoCity,
		IsActive:         &session.IsActive,
		RefreshTokenHash: session.RefreshTokenHash,
		CreatedAt:        pgtype.Timestamptz{Time: session.CreatedAt, Valid: true},
		ExpiresAt:        pgtype.Timestamptz{Time: session.ExpiresAt, Valid: true},
	}

	_, err := r.querier.CreateSession(ctx, params)
	return err
}

func (r *PostgresSessionRepository) GetByID(ctx context.Context, sessionID uuid.UUID) (*entity.Session, error) {
	s, err := r.querier.GetSessionByID(ctx, sessionID)
	if err != nil {
		return nil, err
	}
	return r.toDomain(s), nil
}

func (r *PostgresSessionRepository) GetActiveByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Session, error) {
	sessions, err := r.querier.GetActiveSessionsByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	return r.toDomainList(sessions), nil
}

func (r *PostgresSessionRepository) GetByUserIDAndDeviceID(ctx context.Context, userID uuid.UUID, deviceID string) (*entity.Session, error) {
	// Not implemented in generated queries yet, using simpler logic or assuming filter
	// If query doesn't exist, we might need to add it to sqlc or filter in code
	// For now let's implement checking active sessions
	sessions, err := r.GetActiveByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	for _, s := range sessions {
		if s.DeviceID != nil && *s.DeviceID == deviceID {
			return s, nil
		}
	}
	return nil, nil // Or return Not Found error
}

func (r *PostgresSessionRepository) GetRecentByUser(ctx context.Context, userID uuid.UUID, since time.Duration) ([]*entity.SessionWithLocation, error) {
	arg := generated.GetRecentSessionsWithLocationParams{
		UserID: userID,
		LastActivity: pgtype.Timestamptz{
			Time:  time.Now().Add(-since),
			Valid: true,
		},
	}
	rows, err := r.querier.GetRecentSessionsWithLocation(ctx, arg)
	if err != nil {
		return nil, err
	}

	var result []*entity.SessionWithLocation
	for _, row := range rows {
		result = append(result, &entity.SessionWithLocation{
			SessionID:    row.SessionID,
			CreatedAt:    row.CreatedAt.Time,
			LastActivity: row.LastActivity.Time,
			Location: &entity.GeoLocation{
				Country: row.GeoCountry,
				City:    row.GeoCity,
			},
		})
	}
	return result, nil
}

func (r *PostgresSessionRepository) Update(ctx context.Context, session *entity.Session) error {
	// Not directly supported by simple update in generated code?
	// We might only support Revoke or UpdateLastActivity
	// Assuming logic handles specific updates
	// For general update, we might rely on specific methods
	return errors.New("method Update not implemented fully, use specific update methods")
}

func (r *PostgresSessionRepository) UpdateLastActivity(ctx context.Context, sessionID uuid.UUID) error {
	return r.querier.UpdateLastActivity(ctx, sessionID)
}

func (r *PostgresSessionRepository) RevokeByID(ctx context.Context, sessionID uuid.UUID) error {
	return r.querier.RevokeSession(ctx, sessionID)
}

func (r *PostgresSessionRepository) RevokeAllByUserID(ctx context.Context, userID uuid.UUID) error {
	return r.querier.RevokeAllSessionsByUserID(ctx, userID)
}

func (r *PostgresSessionRepository) CountActiveByUserID(ctx context.Context, userID uuid.UUID) (int64, error) {
	return r.querier.CountActiveSessionsByUserID(ctx, userID)
}

func (r *PostgresSessionRepository) DeleteExpired(ctx context.Context) error {
	return r.querier.DeleteExpiredSessions(ctx)
}

func (r *PostgresSessionRepository) toDomain(s *generated.AuthSession) *entity.Session {
	if s == nil {
		return nil
	}

	isActive := false
	if s.IsActive != nil {
		isActive = *s.IsActive
	}

	var revokedAt *time.Time
	if s.RevokedAt.Valid {
		t := s.RevokedAt.Time
		revokedAt = &t
	}

	// Helper for UserAgent string
	userAgent := ""
	if s.UserAgent != nil {
		userAgent = *s.UserAgent
	}

	return &entity.Session{
		SessionID:        s.SessionID,
		UserID:           s.UserID,
		DeviceID:         s.DeviceID,
		DeviceName:       s.DeviceName,
		DeviceType:       entity.DeviceType(s.DeviceType),
		IPAddress:        s.IpAddress.String(),
		UserAgent:        userAgent,
		GeoCountry:       s.GeoCountry,
		GeoCity:          s.GeoCity,
		IsActive:         isActive,
		RefreshTokenHash: s.RefreshTokenHash,
		CreatedAt:        s.CreatedAt.Time,
		ExpiresAt:        s.ExpiresAt.Time,
		RevokedAt:        revokedAt,
		LastActivity:     s.LastActivity.Time,
	}
}

func (r *PostgresSessionRepository) toDomainList(sessions []*generated.AuthSession) []*entity.Session {
	var result []*entity.Session
	for _, s := range sessions {
		result = append(result, r.toDomain(s))
	}
	return result
}
