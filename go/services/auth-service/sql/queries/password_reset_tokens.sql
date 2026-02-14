-- name: CreatePasswordResetToken :one
INSERT INTO auth_password_reset_tokens (
    token_id,
    user_id,
    token_hash,
    is_used,
    requested_ip,
    requested_user_agent,
    created_at,
    expires_at
) VALUES (
    $1, $2, $3, FALSE, $4, $5, $6, $7
) RETURNING *;

-- name: GetPasswordResetToken :one
SELECT * FROM auth_password_reset_tokens
WHERE token_hash = $1
  AND is_used = FALSE
  AND expires_at > NOW();

-- name: MarkTokenAsUsed :exec
UPDATE auth_password_reset_tokens
SET is_used = TRUE,
    used_at = NOW()
WHERE token_id = $1;

-- name: InvalidateAllTokensByUserID :exec
UPDATE auth_password_reset_tokens
SET is_used = TRUE,
    used_at = NOW()
WHERE user_id = $1 AND is_used = FALSE;

-- name: DeleteExpiredTokens :exec
DELETE FROM auth_password_reset_tokens
WHERE expires_at < NOW() OR (is_used = TRUE AND used_at < $1);
