-- name: GetSessionByTokenHash :one
SELECT session_id, user_id, device_id, device_name, device_type, ip_address, user_agent, 
       geo_country, geo_city, is_active, refresh_token_hash, created_at, expires_at, revoked_at, 
       last_activity, family_id, rotated_from, grace_expires_at, token_status
FROM auth_sessions
WHERE refresh_token_hash = $1 AND token_status = 'ACTIVE';

-- name: GetSessionByTokenHashForUpdate :one
SELECT session_id, user_id, device_id, device_name, device_type, ip_address, user_agent, 
       geo_country, geo_city, is_active, refresh_token_hash, created_at, expires_at, revoked_at, 
       last_activity, family_id, rotated_from, grace_expires_at, token_status
FROM auth_sessions
WHERE refresh_token_hash = $1 AND token_status = 'ACTIVE'
FOR UPDATE;

-- name: CreateSessionWithFamily :one
INSERT INTO auth_sessions (
    session_id, user_id, device_id, device_name, device_type, ip_address, user_agent, 
    geo_country, geo_city, is_active, refresh_token_hash, created_at, expires_at, 
    last_activity, family_id, token_status
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $12, $14, 'ACTIVE'
) RETURNING session_id;

-- name: RevokeWithGracePeriod :exec
UPDATE auth_sessions
SET is_active = FALSE,
    revoked_at = NOW(),
    token_status = 'REVOKED',
    grace_expires_at = $2
WHERE refresh_token_hash = $1 AND token_status = 'ACTIVE';

-- name: MarkTokenAsUsed :exec
UPDATE auth_sessions
SET token_status = 'USED'
WHERE session_id = $1;

-- name: GetActiveSessionsWithGraceByUserID :many
SELECT session_id, user_id, device_id, device_name, device_type, ip_address, user_agent, 
       geo_country, geo_city, is_active, refresh_token_hash, created_at, expires_at, revoked_at, 
       last_activity, family_id, rotated_from, grace_expires_at, token_status
FROM auth_sessions
WHERE user_id = $1 
  AND (token_status = 'ACTIVE' OR (token_status = 'REVOKED' AND grace_expires_at > NOW()))
ORDER BY last_activity DESC;

-- name: DeleteExpiredGraceTokens :exec
DELETE FROM auth_sessions
WHERE token_status = 'REVOKED' 
  AND grace_expires_at < NOW();

-- name: CountActiveSessionsWithFamily :one
SELECT COUNT(*) FROM auth_sessions
WHERE user_id = $1 
  AND family_id = $2 
  AND token_status = 'ACTIVE';
