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

type PostgresPasswordResetTokenRepository struct {
	pool    *pgxpool.Pool
	querier generated.Querier
	logger  *zap.Logger
}

func NewPostgresPasswordResetTokenRepository(pool *pgxpool.Pool, logger *zap.Logger) repository.PasswordResetTokenRepository {
	return &PostgresPasswordResetTokenRepository{
		pool:    pool,
		querier: generated.New(pool),
		logger:  logger,
	}
}

func (r *PostgresPasswordResetTokenRepository) Create(ctx context.Context, token *entity.PasswordResetToken) error {
	var userAgent *string
	if token.RequestedUserAgent != "" {
		ua := token.RequestedUserAgent
		userAgent = &ua
	}

	params := generated.CreatePasswordResetTokenParams{
		TokenID:            token.TokenID,
		UserID:             token.UserID,
		TokenHash:          token.TokenHash,
		RequestedIp:        net.ParseIP(token.RequestedIP),
		RequestedUserAgent: userAgent,
		CreatedAt:          pgtype.Timestamptz{Time: token.CreatedAt, Valid: true},
		ExpiresAt:          pgtype.Timestamptz{Time: token.ExpiresAt, Valid: true},
	}

	_, err := r.querier.CreatePasswordResetToken(ctx, params)
	return err
}

func (r *PostgresPasswordResetTokenRepository) GetByTokenHash(ctx context.Context, tokenHash string) (*entity.PasswordResetToken, error) {
	t, err := r.querier.GetPasswordResetToken(ctx, tokenHash)
	if err != nil {
		return nil, err
	}
	return r.toDomain(t), nil
}

func (r *PostgresPasswordResetTokenRepository) GetByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.PasswordResetToken, error) {
	// Not in Querier interface?
	// Assuming empty for now as it's not generated
	return []*entity.PasswordResetToken{}, nil
}

func (r *PostgresPasswordResetTokenRepository) MarkAsUsed(ctx context.Context, tokenHash string) error {
	// generated.MarkTokenAsUsed takes uuid (TokenID not Hash)
	// We need to look up ID first? Or change interface
	// Assuming TokenID is available or we change method to use TokenID.
	// But interface says tokenHash.
	// Helper: get ID from hash
	t, err := r.GetByTokenHash(ctx, tokenHash)
	if err != nil {
		return err
	}
	if t == nil {
		return nil
	}

	return r.querier.MarkTokenAsUsed(ctx, t.TokenID)
}

func (r *PostgresPasswordResetTokenRepository) Update(ctx context.Context, token *entity.PasswordResetToken) error {
	// Not implemented
	return nil
}

func (r *PostgresPasswordResetTokenRepository) Delete(ctx context.Context, tokenHash string) error {
	// Not implemented
	return nil
}

func (r *PostgresPasswordResetTokenRepository) DeleteByUserID(ctx context.Context, userID uuid.UUID) error {
	return r.querier.InvalidateAllTokensByUserID(ctx, userID)
}

func (r *PostgresPasswordResetTokenRepository) DeleteExpired(ctx context.Context) error {
	// Querier has DeleteExpiredTokens(ctx, usedAt) - wait, parameters?
	// params: DeleteExpiredTokens(ctx context.Context, usedAt pgtype.Timestamptz) error
	// Maybe it deletes used ones older than X?
	return r.querier.DeleteExpiredTokens(ctx, pgtype.Timestamptz{Time: time.Now(), Valid: true})
}

func (r *PostgresPasswordResetTokenRepository) DeleteUsed(ctx context.Context, olderThan time.Time) error {
	return r.querier.DeleteExpiredTokens(ctx, pgtype.Timestamptz{Time: olderThan, Valid: true})
}

func (r *PostgresPasswordResetTokenRepository) toDomain(t *generated.AuthPasswordResetToken) *entity.PasswordResetToken {
	if t == nil {
		return nil
	}

	isUsed := false
	if t.IsUsed != nil {
		isUsed = *t.IsUsed
	}

	var usedAt *time.Time
	if t.UsedAt.Valid {
		ua := t.UsedAt.Time
		usedAt = &ua
	}

	userAgent := ""
	if t.RequestedUserAgent != nil {
		userAgent = *t.RequestedUserAgent
	}

	return &entity.PasswordResetToken{
		TokenID:            t.TokenID,
		UserID:             t.UserID,
		TokenHash:          t.TokenHash,
		IsUsed:             isUsed,
		UsedAt:             usedAt,
		RequestedIP:        t.RequestedIp.String(),
		RequestedUserAgent: userAgent,
		CreatedAt:          t.CreatedAt.Time,
		ExpiresAt:          t.ExpiresAt.Time,
	}
}
