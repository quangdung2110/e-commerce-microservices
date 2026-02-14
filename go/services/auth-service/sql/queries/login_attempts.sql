-- name: CreateLoginAttempt :one
INSERT INTO auth_login_attempts (
    attempt_id,
    user_id,
    login_identifier,
    attempt_type,
    is_successful,
    failure_reason,
    ip_address,
    user_agent,
    device_id,
    is_suspicious,
    attempted_at
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11
) RETURNING *;

-- name: GetRecentFailedAttempts :many
SELECT * FROM auth_login_attempts
WHERE login_identifier = $1
    AND is_successful = FALSE
    AND attempted_at >= $2
ORDER BY attempted_at DESC;

-- name: CountFailedAttemptsByIdentifier :one
SELECT COUNT(*) FROM auth_login_attempts
WHERE login_identifier = $1
    AND is_successful = FALSE
    AND attempted_at >= $2;

-- name: CountFailedAttemptsByIP :one
SELECT COUNT(*) FROM auth_login_attempts
WHERE ip_address = $1
    AND is_successful = FALSE
    AND attempted_at >= $2;

-- name: GetRecentAttemptsByUserID :many
SELECT * FROM auth_login_attempts
WHERE user_id = $1
    AND attempted_at >= $2
ORDER BY attempted_at DESC
LIMIT 50;

-- name: GetSuspiciousLoginAttempts :many
SELECT * FROM auth_login_attempts
WHERE is_suspicious = TRUE
  AND attempted_at > $1
ORDER BY attempted_at DESC
LIMIT $2;
