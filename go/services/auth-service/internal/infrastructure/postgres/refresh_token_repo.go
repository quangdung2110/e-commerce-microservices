package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.RefreshTokenRepository = (*RefreshTokenRepository)(nil)

// RefreshTokenRepository stores session families in auth.refresh_tokens.
type RefreshTokenRepository struct {
	repository
}

// NewRefreshTokenRepository wires the session repository.
func NewRefreshTokenRepository(pool *pgxpool.Pool) *RefreshTokenRepository {
	return &RefreshTokenRepository{repository{pool: pool}}
}

// Create opens a new session family with its first token.
func (r *RefreshTokenRepository) Create(ctx context.Context, token *entity.RefreshToken) error {
	query := `
		INSERT INTO auth.refresh_tokens (
			id, account_id, family_id, token_hash,
			device_id, device_name, user_agent, ip_address,
			expires_at, absolute_expires_at, created_at
		) VALUES (
			$1::uuid, $2::uuid, $3::uuid, $4,
			$5::uuid, $6, $7, $8::inet,
			$9, $10, $11
		)`

	_, err := r.db(ctx).Exec(ctx, query,
		token.ID.String(),
		token.AccountID.String(),
		token.FamilyID.String(),
		token.TokenHash,
		nullableUUID(token.DeviceID),
		nullableString(token.DeviceName),
		nullableString(token.UserAgent),
		nullableString(token.IPAddress),
		token.ExpiresAt,
		token.AbsoluteExpiresAt,
		token.CreatedAt,
	)
	if err != nil {
		return mapError(err)
	}
	return nil
}

const refreshTokenColumns = `
	id::text, account_id::text, family_id::text, token_hash,
	device_id::text, device_name, user_agent, host(ip_address),
	expires_at, absolute_expires_at, revoked_at, replaced_by::text,
	last_used_at, created_at`

// GetByHash loads one token by its digest.
func (r *RefreshTokenRepository) GetByHash(ctx context.Context, tokenHash []byte) (*entity.RefreshToken, error) {
	query := `SELECT ` + refreshTokenColumns + ` FROM auth.refresh_tokens WHERE token_hash = $1`
	return scanRefreshToken(r.db(ctx).QueryRow(ctx, query, tokenHash))
}

// Rotate consumes one token and installs its successor atomically.
//
// The whole exchange is a single statement. Two concurrent refreshes serialize
// on the row lock of the UPDATE; the loser re-evaluates its WHERE clause against
// the committed row, finds revoked_at set, updates nothing and therefore inserts
// nothing. Only one caller can ever receive a new token pair, with no grace
// period and no cached replay response (ADR-002).
//
// The consumed row is revoked and linked to its successor in the same UPDATE:
// a second UPDATE inside the statement would not see the revocation and would
// break chk_refresh_tokens_replacement. The foreign key on replaced_by is
// satisfied because it is checked at the end of the statement, once the INSERT
// has run.
func (r *RefreshTokenRepository) Rotate(
	ctx context.Context,
	currentHash, nextHash []byte,
	sliding time.Duration,
	sessionCtx entity.SessionContext,
	now time.Time,
) (*entity.RefreshToken, error) {
	query := `
		WITH consumed AS (
			UPDATE auth.refresh_tokens
			SET revoked_at = $3, last_used_at = $3, replaced_by = $4::uuid
			WHERE token_hash = $1
			  AND revoked_at IS NULL
			  AND expires_at > $3
			  AND absolute_expires_at > $3
			RETURNING id, account_id, family_id, device_id, device_name,
			          user_agent, ip_address, absolute_expires_at
		),
		inserted AS (
			INSERT INTO auth.refresh_tokens (
				id, account_id, family_id, token_hash,
				device_id, device_name, user_agent, ip_address,
				expires_at, absolute_expires_at, created_at
			)
			SELECT
				$4::uuid, c.account_id, c.family_id, $2,
				c.device_id, c.device_name,
				COALESCE(NULLIF($5::text, ''), c.user_agent),
				COALESCE(NULLIF($6::text, '')::inet, c.ip_address),
				LEAST($3::timestamptz + make_interval(secs => $7::double precision), c.absolute_expires_at),
				c.absolute_expires_at,
				$3
			FROM consumed c
			RETURNING id, account_id, family_id, token_hash, device_id, device_name,
			          user_agent, ip_address, expires_at, absolute_expires_at,
			          revoked_at, replaced_by, last_used_at, created_at
		)
		SELECT
			i.id::text, i.account_id::text, i.family_id::text, i.token_hash,
			i.device_id::text, i.device_name, i.user_agent, host(i.ip_address),
			i.expires_at, i.absolute_expires_at, i.revoked_at, i.replaced_by::text,
			i.last_used_at, i.created_at
		FROM inserted i`

	token, err := scanRefreshToken(r.db(ctx).QueryRow(ctx, query,
		currentHash,
		nextHash,
		now.UTC(),
		uuid.NewString(),
		sessionCtx.UserAgent,
		sessionCtx.IPAddress,
		sliding.Seconds(),
	))
	if err != nil {
		return nil, err
	}
	return token, nil
}

// RevokeFamily revokes the active tokens of one session.
func (r *RefreshTokenRepository) RevokeFamily(ctx context.Context, accountID, familyID uuid.UUID, now time.Time) (int64, error) {
	tag, err := r.db(ctx).Exec(ctx, `
		UPDATE auth.refresh_tokens
		SET revoked_at = $3
		WHERE account_id = $1::uuid AND family_id = $2::uuid AND revoked_at IS NULL`,
		accountID.String(), familyID.String(), now.UTC())
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// RevokeAllForAccount revokes every active session of an account.
func (r *RefreshTokenRepository) RevokeAllForAccount(ctx context.Context, accountID uuid.UUID, now time.Time) (int64, error) {
	tag, err := r.db(ctx).Exec(ctx, `
		UPDATE auth.refresh_tokens
		SET revoked_at = $2
		WHERE account_id = $1::uuid AND revoked_at IS NULL`,
		accountID.String(), now.UTC())
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// FamilyExists reports whether the session belongs to the account, including
// families that are already revoked, so revoking twice stays idempotent.
func (r *RefreshTokenRepository) FamilyExists(ctx context.Context, accountID, familyID uuid.UUID) (bool, error) {
	var exists bool
	err := r.db(ctx).QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM auth.refresh_tokens
			WHERE account_id = $1::uuid AND family_id = $2::uuid
		)`, accountID.String(), familyID.String()).Scan(&exists)
	if err != nil {
		return false, mapError(err)
	}
	return exists, nil
}

// ListSessions returns one row per active session family. The response never
// contains a token or a token hash.
func (r *RefreshTokenRepository) ListSessions(ctx context.Context, accountID uuid.UUID, cursor *port.SessionCursor, limit int) ([]entity.Session, *port.SessionCursor, error) {
	query := `
		WITH current_token AS (
			SELECT DISTINCT ON (t.family_id)
				t.family_id, t.device_name, t.ip_address, t.expires_at
			FROM auth.refresh_tokens t
			WHERE t.account_id = $1::uuid
			  AND t.revoked_at IS NULL
			  AND t.expires_at > $2
			ORDER BY t.family_id, t.expires_at DESC
		),
		family_history AS (
			SELECT
				t.family_id,
				MIN(t.created_at) AS started_at,
				MAX(COALESCE(t.last_used_at, t.created_at)) AS last_used_at
			FROM auth.refresh_tokens t
			WHERE t.account_id = $1::uuid
			GROUP BY t.family_id
		)
		SELECT
			c.family_id::text,
			COALESCE(c.device_name, ''),
			COALESCE(host(c.ip_address), ''),
			h.started_at,
			h.last_used_at,
			c.expires_at
		FROM current_token c
		JOIN family_history h ON h.family_id = c.family_id
		WHERE $3::timestamptz IS NULL
		   OR (c.expires_at, c.family_id) < ($3::timestamptz, $4::uuid)
		ORDER BY c.expires_at DESC, c.family_id DESC
		LIMIT $5`

	var (
		cursorExpiresAt *time.Time
		cursorFamilyID  *string
	)
	if cursor != nil {
		expiresAt := cursor.ExpiresAt
		familyID := cursor.FamilyID.String()
		cursorExpiresAt = &expiresAt
		cursorFamilyID = &familyID
	}

	// One extra row tells us whether another page exists without a second query.
	rows, err := r.db(ctx).Query(ctx, query,
		accountID.String(), time.Now().UTC(), cursorExpiresAt, cursorFamilyID, limit+1)
	if err != nil {
		return nil, nil, mapError(err)
	}
	defer rows.Close()

	sessions := make([]entity.Session, 0, limit)
	for rows.Next() {
		var (
			rawID      string
			deviceName string
			ipAddress  string
			startedAt  time.Time
			lastUsedAt time.Time
			expiresAt  time.Time
		)
		if err := rows.Scan(&rawID, &deviceName, &ipAddress, &startedAt, &lastUsedAt, &expiresAt); err != nil {
			return nil, nil, mapError(err)
		}
		id, err := uuid.Parse(rawID)
		if err != nil {
			return nil, nil, apperr.ErrInternal.WithCause(err)
		}
		sessions = append(sessions, entity.Session{
			ID:         id,
			DeviceName: deviceName,
			IPAddress:  ipAddress,
			CreatedAt:  startedAt,
			LastUsedAt: lastUsedAt,
			ExpiresAt:  expiresAt,
		})
	}
	if err := rows.Err(); err != nil {
		return nil, nil, mapError(err)
	}

	if len(sessions) <= limit {
		return sessions, nil, nil
	}
	sessions = sessions[:limit]
	last := sessions[len(sessions)-1]
	return sessions, &port.SessionCursor{ExpiresAt: last.ExpiresAt, FamilyID: last.ID}, nil
}

// DeleteExpiredBefore removes revoked or expired rows past the retention window.
func (r *RefreshTokenRepository) DeleteExpiredBefore(ctx context.Context, cutoff time.Time, limit int) (int64, error) {
	tag, err := r.db(ctx).Exec(ctx, `
		DELETE FROM auth.refresh_tokens
		WHERE id IN (
			SELECT id FROM auth.refresh_tokens
			WHERE (revoked_at IS NOT NULL AND revoked_at < $1)
			   OR expires_at < $1
			LIMIT $2
		)`, cutoff.UTC(), limit)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

func scanRefreshToken(row rowScanner) (*entity.RefreshToken, error) {
	var (
		rawID             string
		rawAccountID      string
		rawFamilyID       string
		tokenHash         []byte
		rawDeviceID       *string
		deviceName        *string
		userAgent         *string
		ipAddress         *string
		expiresAt         time.Time
		absoluteExpiresAt time.Time
		revokedAt         *time.Time
		rawReplacedBy     *string
		lastUsedAt        *time.Time
		createdAt         time.Time
	)

	err := row.Scan(
		&rawID, &rawAccountID, &rawFamilyID, &tokenHash,
		&rawDeviceID, &deviceName, &userAgent, &ipAddress,
		&expiresAt, &absoluteExpiresAt, &revokedAt, &rawReplacedBy,
		&lastUsedAt, &createdAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// A digest with no usable row is indistinguishable from a revoked or
			// expired one on purpose.
			return nil, apperr.ErrRefreshTokenInvalid
		}
		return nil, mapError(err)
	}

	id, err := uuid.Parse(rawID)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	accountID, err := uuid.Parse(rawAccountID)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	familyID, err := uuid.Parse(rawFamilyID)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}

	token := &entity.RefreshToken{
		ID:                id,
		AccountID:         accountID,
		FamilyID:          familyID,
		TokenHash:         tokenHash,
		DeviceName:        derefString(deviceName),
		UserAgent:         derefString(userAgent),
		IPAddress:         derefString(ipAddress),
		ExpiresAt:         expiresAt,
		AbsoluteExpiresAt: absoluteExpiresAt,
		RevokedAt:         revokedAt,
		LastUsedAt:        lastUsedAt,
		CreatedAt:         createdAt,
	}
	if rawDeviceID != nil {
		deviceID, err := uuid.Parse(*rawDeviceID)
		if err != nil {
			return nil, apperr.ErrInternal.WithCause(err)
		}
		token.DeviceID = &deviceID
	}
	if rawReplacedBy != nil {
		replacedBy, err := uuid.Parse(*rawReplacedBy)
		if err != nil {
			return nil, apperr.ErrInternal.WithCause(err)
		}
		token.ReplacedBy = &replacedBy
	}
	return token, nil
}

func nullableUUID(id *uuid.UUID) *string {
	if id == nil {
		return nil
	}
	raw := id.String()
	return &raw
}
