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
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

var _ port.JwtKeyRepository = (*JwtKeyRepository)(nil)

// JwtKeyRepository stores signing keys in auth.jwt_keys. The private half is
// only ever written and read as ciphertext.
type JwtKeyRepository struct {
	repository
}

// NewJwtKeyRepository wires the key repository.
func NewJwtKeyRepository(pool *pgxpool.Pool) *JwtKeyRepository {
	return &JwtKeyRepository{repository{pool: pool}}
}

const jwtKeyColumns = `
	kid::text, algorithm, public_key_pem,
	private_key_ciphertext, private_key_nonce, encryption_key_version,
	status::text, activated_at, phase_out_at, retired_at, created_at`

const insertJwtKey = `
	INSERT INTO auth.jwt_keys (
		kid, algorithm, public_key_pem,
		private_key_ciphertext, private_key_nonce, encryption_key_version,
		status, activated_at, phase_out_at, retired_at, created_at
	) VALUES (
		$1::uuid, $2, $3,
		$4, $5, $6,
		$7::auth.jwt_key_status, $8, $9, $10, $11
	)`

// Create inserts a key. It is used for the very first ACTIVE key.
func (r *JwtKeyRepository) Create(ctx context.Context, key *entity.JwtKey) error {
	if _, err := r.db(ctx).Exec(ctx, insertJwtKey, insertArgs(key)...); err != nil {
		return mapError(err)
	}
	return nil
}

// GetActive returns the single ACTIVE key. When no key exists yet, it reports
// ErrSigningKeyMissing so the lifecycle worker can create one while token
// issuance keeps failing closed.
func (r *JwtKeyRepository) GetActive(ctx context.Context) (*entity.JwtKey, error) {
	query := `SELECT ` + jwtKeyColumns + ` FROM auth.jwt_keys WHERE status = 'ACTIVE'`
	return scanJwtKey(r.db(ctx).QueryRow(ctx, query))
}

// ListPublishable returns the keys that belong in the JWKS document.
func (r *JwtKeyRepository) ListPublishable(ctx context.Context) ([]*entity.JwtKey, error) {
	query := `
		SELECT ` + jwtKeyColumns + `
		FROM auth.jwt_keys
		WHERE status IN ('ACTIVE', 'PHASE_OUT')
		ORDER BY status, created_at DESC`

	rows, err := r.db(ctx).Query(ctx, query)
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	var keys []*entity.JwtKey
	for rows.Next() {
		key, err := scanJwtKey(rows)
		if err != nil {
			return nil, err
		}
		keys = append(keys, key)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return keys, nil
}

// Rotate phases the current ACTIVE key out and installs the next one in one
// transaction, so the unique "one active key" index is never violated and the
// service is never left without a signer.
func (r *JwtKeyRepository) Rotate(ctx context.Context, next *entity.JwtKey, now time.Time) error {
	return withinTx(ctx, r.pool, func(txCtx context.Context) error {
		db := r.db(txCtx)
		if _, err := db.Exec(txCtx, `
			UPDATE auth.jwt_keys
			SET status = 'PHASE_OUT', phase_out_at = $1
			WHERE status = 'ACTIVE'`, now.UTC()); err != nil {
			return mapError(err)
		}
		if _, err := db.Exec(txCtx, insertJwtKey, insertArgs(next)...); err != nil {
			return mapError(err)
		}
		return nil
	})
}

// ListRetirable returns PHASE_OUT keys whose overlap window has elapsed.
func (r *JwtKeyRepository) ListRetirable(ctx context.Context, phasedOutBefore time.Time) ([]*entity.JwtKey, error) {
	query := `
		SELECT ` + jwtKeyColumns + `
		FROM auth.jwt_keys
		WHERE status = 'PHASE_OUT' AND phase_out_at IS NOT NULL AND phase_out_at <= $1
		ORDER BY phase_out_at`

	rows, err := r.db(ctx).Query(ctx, query, phasedOutBefore.UTC())
	if err != nil {
		return nil, mapError(err)
	}
	defer rows.Close()

	var keys []*entity.JwtKey
	for rows.Next() {
		key, err := scanJwtKey(rows)
		if err != nil {
			return nil, err
		}
		keys = append(keys, key)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return keys, nil
}

// Retire removes a key from JWKS. Only a PHASE_OUT key can be retired.
func (r *JwtKeyRepository) Retire(ctx context.Context, kid uuid.UUID, now time.Time) error {
	tag, err := r.db(ctx).Exec(ctx, `
		UPDATE auth.jwt_keys
		SET status = 'RETIRED', retired_at = $2
		WHERE kid = $1::uuid AND status = 'PHASE_OUT'`,
		kid.String(), now.UTC())
	if err != nil {
		return mapError(err)
	}
	if tag.RowsAffected() == 0 {
		// Another replica retired it first; that is not an error.
		return nil
	}
	return nil
}

func insertArgs(key *entity.JwtKey) []any {
	return []any{
		key.KID.String(),
		key.Algorithm,
		key.PublicKeyPEM,
		key.PrivateKeyCiphertext,
		key.PrivateKeyNonce,
		key.EncryptionKeyVersion,
		key.Status.String(),
		key.ActivatedAt,
		key.PhaseOutAt,
		key.RetiredAt,
		key.CreatedAt,
	}
}

func scanJwtKey(row rowScanner) (*entity.JwtKey, error) {
	var (
		rawKID               string
		algorithm            string
		publicKeyPEM         string
		privateKeyCiphertext []byte
		privateKeyNonce      []byte
		encryptionKeyVersion int16
		rawStatus            string
		activatedAt          *time.Time
		phaseOutAt           *time.Time
		retiredAt            *time.Time
		createdAt            time.Time
	)

	err := row.Scan(
		&rawKID, &algorithm, &publicKeyPEM,
		&privateKeyCiphertext, &privateKeyNonce, &encryptionKeyVersion,
		&rawStatus, &activatedAt, &phaseOutAt, &retiredAt, &createdAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperr.ErrSigningKeyMissing
		}
		return nil, mapError(err)
	}

	kid, err := uuid.Parse(rawKID)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	status, err := vo.ParseJwtKeyStatus(rawStatus)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}

	return &entity.JwtKey{
		KID:                  kid,
		Algorithm:            algorithm,
		PublicKeyPEM:         publicKeyPEM,
		PrivateKeyCiphertext: privateKeyCiphertext,
		PrivateKeyNonce:      privateKeyNonce,
		EncryptionKeyVersion: encryptionKeyVersion,
		Status:               status,
		ActivatedAt:          activatedAt,
		PhaseOutAt:           phaseOutAt,
		RetiredAt:            retiredAt,
		CreatedAt:            createdAt,
	}, nil
}
