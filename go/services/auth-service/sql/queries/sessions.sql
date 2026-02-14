-- name: CreateSession :one
INSERT INTO auth_sessions (
    session_id,
    user_id,
    device_id,
    device_name,
    device_type,
    ip_address,
    user_agent,
    geo_country,
    geo_city,
    is_active,
    refresh_token_hash,
    created_at,
    expires_at,
    last_activity
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $12
) RETURNING *;

-- name: GetSessionByID :one
SELECT * FROM auth_sessions
WHERE session_id = $1 AND is_active = TRUE;

-- name: GetActiveSessionsByUserID :many
SELECT * FROM auth_sessions
WHERE user_id = $1
  AND is_active = TRUE
ORDER BY last_activity DESC;

-- name: UpdateLastActivity :exec
UPDATE auth_sessions
SET last_activity = NOW()
WHERE session_id = $1 AND is_active = TRUE;

-- name: RevokeSession :exec
UPDATE auth_sessions
SET is_active = FALSE,
    revoked_at = NOW()
WHERE session_id = $1;

-- name: RevokeAllSessionsByUserID :exec
UPDATE auth_sessions
SET is_active = FALSE,
    revoked_at = NOW()
WHERE user_id = $1
  AND is_active = TRUE;

-- name: RevokeAllByUserIDExceptOne :exec
UPDATE auth_sessions
SET is_active = FALSE,
    revoked_at = NOW()
WHERE user_id = $1
  AND session_id != $2
  AND is_active = TRUE;

-- name: GetRecentSessionsWithLocation :many
SELECT 
    session_id,
    user_id,
    ip_address,
    geo_country,
    geo_city,
    last_activity,
    created_at
FROM auth_sessions
WHERE user_id = $1 
    AND is_active = TRUE
    AND last_activity >= $2
ORDER BY last_activity DESC
LIMIT 10;

-- name: DeleteExpiredSessions :exec
DELETE FROM auth_sessions
WHERE expires_at < NOW()
  AND is_active = FALSE;

-- name: CountActiveSessionsByUserID :one
SELECT COUNT(*) FROM auth_sessions
WHERE user_id = $1
  AND is_active = TRUE;
