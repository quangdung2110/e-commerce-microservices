package pg_adapter

import (
	"context"
	"errors"
	"net"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
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

// ========================= MODULE 3: TOKEN ROTATION =========================

// FindByTokenHashForUpdate retrieves a session by token hash with row lock (FOR UPDATE)
// Used during refresh token rotation to prevent race conditions
func (r *PostgresSessionRepository) FindByTokenHashForUpdate(ctx context.Context, tokenHash string) (*entity.Session, error) {
	// First, try to find the session using raw SQL with FOR UPDATE
	query := `
		SELECT session_id, user_id, device_id, device_name, device_type, ip_address, user_agent,
		       geo_country, geo_city, is_active, refresh_token_hash, created_at, expires_at,
		       revoked_at, last_activity,
		       COALESCE(family_id, '00000000-0000-0000-0000-000000000000'::uuid) as family_id,
		       rotated_from, grace_expires_at,
		       COALESCE(token_status, 'ACTIVE') as token_status
		FROM auth_sessions
		WHERE refresh_token_hash = $1 AND token_status = 'ACTIVE'
		FOR UPDATE
	`

	var s struct {
		SessionID        uuid.UUID
		UserID           uuid.UUID
		DeviceID         *string
		DeviceName       *string
		DeviceType       string
		IpAddress        net.IP
		UserAgent        *string
		GeoCountry       *string
		GeoCity          *string
		IsActive         bool
		RefreshTokenHash string
		CreatedAt        pgtype.Timestamptz
		ExpiresAt        pgtype.Timestamptz
		RevokedAt        pgtype.Timestamptz
		LastActivity     pgtype.Timestamptz
		FamilyID         uuid.UUID
		RotatedFrom      *uuid.UUID
		GraceExpiresAt   *pgtype.Timestamptz
		TokenStatus      string
	}

	err := r.pool.QueryRow(ctx, query, tokenHash).Scan(
		&s.SessionID, &s.UserID, &s.DeviceID, &s.DeviceName, &s.DeviceType, &s.IpAddress, &s.UserAgent,
		&s.GeoCountry, &s.GeoCity, &s.IsActive, &s.RefreshTokenHash, &s.CreatedAt, &s.ExpiresAt,
		&s.RevokedAt, &s.LastActivity, &s.FamilyID, &s.RotatedFrom, &s.GraceExpiresAt, &s.TokenStatus,
	)

	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errors.New("session not found")
		}
		return nil, err
	}

	var revokedAt *time.Time
	if s.RevokedAt.Valid {
		t := s.RevokedAt.Time
		revokedAt = &t
	}

	var graceExpiresAt *time.Time
	if s.GraceExpiresAt != nil && s.GraceExpiresAt.Valid {
		t := s.GraceExpiresAt.Time
		graceExpiresAt = &t
	}

	var familyID *uuid.UUID
	if s.FamilyID != uuid.Nil {
		familyID = &s.FamilyID
	}

	return &entity.Session{
		SessionID:        s.SessionID,
		UserID:           s.UserID,
		DeviceID:         s.DeviceID,
		DeviceName:       s.DeviceName,
		DeviceType:       entity.DeviceType(s.DeviceType),
		IPAddress:        s.IpAddress.String(),
		UserAgent:        derefString(s.UserAgent),
		GeoCountry:       s.GeoCountry,
		GeoCity:          s.GeoCity,
		IsActive:         s.IsActive,
		RefreshTokenHash: s.RefreshTokenHash,
		CreatedAt:        s.CreatedAt.Time,
		ExpiresAt:        s.ExpiresAt.Time,
		RevokedAt:        revokedAt,
		LastActivity:     s.LastActivity.Time,
		FamilyID:         familyID,
		RotatedFrom:      s.RotatedFrom,
		GraceExpiresAt:   graceExpiresAt,
		TokenStatus:      types.TokenStatus(s.TokenStatus),
	}, nil
}

// CreateWithFamily creates a new session with family tracking
func (r *PostgresSessionRepository) CreateWithFamily(ctx context.Context, session *entity.Session, familyID uuid.UUID) error {
	query := `
		INSERT INTO auth_sessions (
			session_id, user_id, device_id, device_name, device_type, ip_address, user_agent,
			geo_country, geo_city, is_active, refresh_token_hash, created_at, expires_at,
			last_activity, family_id, token_status
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $12, $14, 'ACTIVE')
		RETURNING session_id
	`

	isActive := true
	_, err := r.pool.Exec(ctx, query,
		session.SessionID,
		session.UserID,
		session.DeviceID,
		session.DeviceName,
		generated.DeviceType(session.DeviceType),
		net.ParseIP(session.IPAddress),
		&session.UserAgent,
		session.GeoCountry,
		session.GeoCity,
		&isActive,
		session.RefreshTokenHash,
		pgtype.Timestamptz{Time: session.CreatedAt, Valid: true},
		pgtype.Timestamptz{Time: session.ExpiresAt, Valid: true},
		familyID,
	)

	return err
}

// RevokeWithGracePeriod revokes a session and sets grace period
func (r *PostgresSessionRepository) RevokeWithGracePeriod(ctx context.Context, tokenHash string, graceExpiresAt time.Time) error {
	query := `
		UPDATE auth_sessions
		SET is_active = FALSE,
		    revoked_at = NOW(),
		    token_status = 'REVOKED',
		    grace_expires_at = $2
		WHERE refresh_token_hash = $1 AND token_status = 'ACTIVE'
	`

	_, err := r.pool.Exec(ctx, query, tokenHash, pgtype.Timestamptz{Time: graceExpiresAt, Valid: true})
	return err
}

// MarkTokenAsUsed marks a token as used
func (r *PostgresSessionRepository) MarkTokenAsUsed(ctx context.Context, sessionID uuid.UUID) error {
	query := `UPDATE auth_sessions SET token_status = 'USED' WHERE session_id = $1`
	_, err := r.pool.Exec(ctx, query, sessionID)
	return err
}

// GetActiveSessionsWithGraceByUserID retrieves all active sessions and those in grace period
func (r *PostgresSessionRepository) GetActiveSessionsWithGraceByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Session, error) {
	query := `
		SELECT session_id, user_id, device_id, device_name, device_type, ip_address, user_agent,
		       geo_country, geo_city, is_active, refresh_token_hash, created_at, expires_at,
		       revoked_at, last_activity,
		       COALESCE(family_id, '00000000-0000-0000-0000-000000000000'::uuid) as family_id,
		       rotated_from, grace_expires_at,
		       COALESCE(token_status, 'ACTIVE') as token_status
		FROM auth_sessions
		WHERE user_id = $1
		  AND (token_status = 'ACTIVE' OR (token_status = 'REVOKED' AND grace_expires_at > NOW()))
		ORDER BY last_activity DESC
	`

	rows, err := r.pool.Query(ctx, query, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var sessions []*entity.Session
	for rows.Next() {
		var s struct {
			SessionID        uuid.UUID
			UserID           uuid.UUID
			DeviceID         *string
			DeviceName       *string
			DeviceType       string
			IpAddress        net.IP
			UserAgent        *string
			GeoCountry       *string
			GeoCity          *string
			IsActive         bool
			RefreshTokenHash string
			CreatedAt        pgtype.Timestamptz
			ExpiresAt        pgtype.Timestamptz
			RevokedAt        pgtype.Timestamptz
			LastActivity     pgtype.Timestamptz
			FamilyID         uuid.UUID
			RotatedFrom      *uuid.UUID
			GraceExpiresAt   *pgtype.Timestamptz
			TokenStatus      string
		}

		err := rows.Scan(
			&s.SessionID, &s.UserID, &s.DeviceID, &s.DeviceName, &s.DeviceType, &s.IpAddress, &s.UserAgent,
			&s.GeoCountry, &s.GeoCity, &s.IsActive, &s.RefreshTokenHash, &s.CreatedAt, &s.ExpiresAt,
			&s.RevokedAt, &s.LastActivity, &s.FamilyID, &s.RotatedFrom, &s.GraceExpiresAt, &s.TokenStatus,
		)
		if err != nil {
			return nil, err
		}

		var revokedAt *time.Time
		if s.RevokedAt.Valid {
			t := s.RevokedAt.Time
			revokedAt = &t
		}

		var graceExpiresAt *time.Time
		if s.GraceExpiresAt != nil && s.GraceExpiresAt.Valid {
			t := s.GraceExpiresAt.Time
			graceExpiresAt = &t
		}

		var familyID *uuid.UUID
		if s.FamilyID != uuid.Nil {
			familyID = &s.FamilyID
		}

		sessions = append(sessions, &entity.Session{
			SessionID:        s.SessionID,
			UserID:           s.UserID,
			DeviceID:         s.DeviceID,
			DeviceName:       s.DeviceName,
			DeviceType:       entity.DeviceType(s.DeviceType),
			IPAddress:        s.IpAddress.String(),
			UserAgent:        derefString(s.UserAgent),
			GeoCountry:       s.GeoCountry,
			GeoCity:          s.GeoCity,
			IsActive:         s.IsActive,
			RefreshTokenHash: s.RefreshTokenHash,
			CreatedAt:        s.CreatedAt.Time,
			ExpiresAt:        s.ExpiresAt.Time,
			RevokedAt:        revokedAt,
			LastActivity:     s.LastActivity.Time,
			FamilyID:         familyID,
			RotatedFrom:      s.RotatedFrom,
			GraceExpiresAt:   graceExpiresAt,
			TokenStatus:      types.TokenStatus(s.TokenStatus),
		})
	}

	return sessions, rows.Err()
}

// DeleteExpiredGraceTokens deletes tokens past their grace period
func (r *PostgresSessionRepository) DeleteExpiredGraceTokens(ctx context.Context) error {
	query := `DELETE FROM auth_sessions WHERE token_status = 'REVOKED' AND grace_expires_at < NOW()`
	_, err := r.pool.Exec(ctx, query)
	return err
}

// Helper function to safely dereference string pointer
func derefString(s *string) string {
	if s == nil {
		return ""
	}
	return *s
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
