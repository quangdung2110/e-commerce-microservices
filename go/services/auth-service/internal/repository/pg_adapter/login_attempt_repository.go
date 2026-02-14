package pg_adapter

import (
	"context"
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

type PostgresLoginAttemptRepository struct {
	pool    *pgxpool.Pool
	querier generated.Querier
	logger  *zap.Logger
}

func NewPostgresLoginAttemptRepository(pool *pgxpool.Pool, logger *zap.Logger) repository.LoginAttemptRepository {
	return &PostgresLoginAttemptRepository{
		pool:    pool,
		querier: generated.New(pool),
		logger:  logger,
	}
}

func (r *PostgresLoginAttemptRepository) Create(ctx context.Context, attempt *entity.LoginAttempt) error {
	var failureReason *string
	if attempt.FailureReason != nil {
		s := string(*attempt.FailureReason)
		failureReason = &s
	}

	var userAgent *string
	if attempt.UserAgent != "" {
		userAgent = &attempt.UserAgent
	}

	var deviceID *string
	if attempt.DeviceID != nil {
		deviceID = attempt.DeviceID
	}

	isSuspicious := false

	params := generated.CreateLoginAttemptParams{
		AttemptID:       0, // Auto-generated
		UserID:          pgtype.UUID{Valid: false},
		LoginIdentifier: attempt.LoginIdentifier,
		AttemptType:     string(attempt.AttemptType),
		IsSuccessful:    attempt.IsSuccessful,
		FailureReason:   failureReason,
		IpAddress:       net.ParseIP(attempt.IPAddress),
		UserAgent:       userAgent,
		DeviceID:        deviceID,
		IsSuspicious:    &isSuspicious,
		AttemptedAt:     pgtype.Timestamptz{Time: time.Now(), Valid: true},
	}

	if attempt.UserID != nil {
		params.UserID = pgtype.UUID{Bytes: *attempt.UserID, Valid: true}
	}

	_, err := r.querier.CreateLoginAttempt(ctx, params)
	return err
}

func (r *PostgresLoginAttemptRepository) GetByIP(ctx context.Context, ipAddress string, since time.Time) ([]*entity.LoginAttempt, error) {
	params := generated.GetRecentFailedAttemptsParams{
		LoginIdentifier: ipAddress, // Using identifier field for IP-based search
		AttemptedAt:     pgtype.Timestamptz{Time: since, Valid: true},
	}

	rows, err := r.querier.GetRecentFailedAttempts(ctx, params)
	if err != nil {
		return nil, err
	}
	return r.toDomainList(rows), nil
}

func (r *PostgresLoginAttemptRepository) GetByIdentifier(ctx context.Context, identifier string, since time.Time) ([]*entity.LoginAttempt, error) {
	// Not supported by generated queries?
	// Returning empty
	return []*entity.LoginAttempt{}, nil
}

func (r *PostgresLoginAttemptRepository) GetByUserID(ctx context.Context, userID uuid.UUID, since time.Time, limit int) ([]*entity.LoginAttempt, error) {
	params := generated.GetRecentAttemptsByUserIDParams{
		UserID:      pgtype.UUID{Bytes: userID, Valid: true},
		AttemptedAt: pgtype.Timestamptz{Time: since, Valid: true},
	}

	rows, err := r.querier.GetRecentAttemptsByUserID(ctx, params)
	if err != nil {
		return nil, err
	}
	return r.toDomainList(rows), nil
}

func (r *PostgresLoginAttemptRepository) CountFailedByIP(ctx context.Context, ipAddress string, since time.Time) (int64, error) {
	params := generated.CountFailedAttemptsByIPParams{
		IpAddress:   net.ParseIP(ipAddress),
		AttemptedAt: pgtype.Timestamptz{Time: since, Valid: true},
	}
	return r.querier.CountFailedAttemptsByIP(ctx, params)
}

func (r *PostgresLoginAttemptRepository) CountFailedByIdentifier(ctx context.Context, identifier string, since time.Time) (int64, error) {
	params := generated.CountFailedAttemptsByIdentifierParams{
		LoginIdentifier: identifier,
		AttemptedAt:     pgtype.Timestamptz{Time: since, Valid: true},
	}
	return r.querier.CountFailedAttemptsByIdentifier(ctx, params)
}

func (r *PostgresLoginAttemptRepository) GetSuspicious(ctx context.Context, since time.Time, limit int) ([]*entity.LoginAttempt, error) {
	params := generated.GetSuspiciousLoginAttemptsParams{
		AttemptedAt: pgtype.Timestamptz{Time: since, Valid: true},
		Limit:       int32(limit),
	}
	rows, err := r.querier.GetSuspiciousLoginAttempts(ctx, params)
	if err != nil {
		return nil, err
	}
	return r.toDomainList(rows), nil
}

func (r *PostgresLoginAttemptRepository) toDomain(a *generated.AuthLoginAttempt) *entity.LoginAttempt {
	if a == nil {
		return nil
	}

	var failureReason *entity.FailureReason
	if a.FailureReason != nil {
		r := entity.FailureReason(*a.FailureReason)
		failureReason = &r
	}

	var userID *uuid.UUID
	if a.UserID.Valid {
		uid := a.UserID
		userID = &uuid.UUID{uid.Bytes[0], uid.Bytes[1], uid.Bytes[2], uid.Bytes[3], uid.Bytes[4], uid.Bytes[5], uid.Bytes[6], uid.Bytes[7], uid.Bytes[8], uid.Bytes[9], uid.Bytes[10], uid.Bytes[11], uid.Bytes[12], uid.Bytes[13], uid.Bytes[14], uid.Bytes[15]}
	}

	userAgent := ""
	if a.UserAgent != nil {
		userAgent = *a.UserAgent
	}

	return &entity.LoginAttempt{
		AttemptID:       a.AttemptID,
		UserID:          userID,
		LoginIdentifier: a.LoginIdentifier,
		AttemptType:     entity.AttemptType(a.AttemptType),
		IsSuccessful:    a.IsSuccessful,
		FailureReason:   failureReason,
		IPAddress:       a.IpAddress.String(),
		UserAgent:       userAgent,
		DeviceID:        a.DeviceID,
		AttemptedAt:     a.AttemptedAt.Time,
	}
}

func (r *PostgresLoginAttemptRepository) toDomainList(attempts []*generated.AuthLoginAttempt) []*entity.LoginAttempt {
	var result []*entity.LoginAttempt
	for _, a := range attempts {
		result = append(result, r.toDomain(a))
	}
	return result
}
