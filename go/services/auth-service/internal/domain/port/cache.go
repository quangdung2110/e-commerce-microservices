package port

import (
	"context"
	"time"
)

type Cache interface {
	Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error
	Get(ctx context.Context, key string) (string, error)
	Delete(ctx context.Context, key string) error
	Exists(ctx context.Context, key string) (bool, error)
}

type TokenCache interface {
	AddToDenylist(ctx context.Context, jti string, remainingTTL time.Duration) error
	IsDenylisted(ctx context.Context, jti string) (bool, error)
	SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error
	GetRevokedBefore(ctx context.Context, userID string) (time.Time, error)
}

type GracePeriodCache interface {
	Set(ctx context.Context, oldTokenHash string, accessToken, refreshToken string) error
	Get(ctx context.Context, oldTokenHash string) (string, string, bool, error)
	Delete(ctx context.Context, oldTokenHash string) error
}

type RateLimiter interface {
	Allow(ctx context.Context, action, identifier string, limit int, window time.Duration) (bool, error)
	GetCount(ctx context.Context, action, identifier string) (int, error)
}

type OTPCache interface {
	Set(ctx context.Context, email string, otp string) error
	Get(ctx context.Context, email string) (string, error)
	Delete(ctx context.Context, email string) error
	Verify(ctx context.Context, email string, otp string) (bool, error)
	GetRemainingTTL(ctx context.Context, email string) (time.Duration, error)
}

type ResetTokenCache interface {
	Set(ctx context.Context, tokenHash, userID string) error
	Get(ctx context.Context, tokenHash string) (string, error)
	Delete(ctx context.Context, tokenHash string) error
	Verify(ctx context.Context, tokenHash string) (string, error)
}

type FailedLoginCache interface {
	Increment(ctx context.Context, identifier string) (int, error)
	GetCount(ctx context.Context, identifier string) (int, error)
	Reset(ctx context.Context, identifier string) error
	IsLocked(ctx context.Context, identifier string) (bool, error)
	IncrementAndCheck(ctx context.Context, identifier string) (int, bool, error)
}
