package redis

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.OtpStore = (*OtpStore)(nil)

// otpRecord is the stored challenge. Only the digest of the code is kept, so a
// dump of Redis never reveals a usable code.
type otpRecord struct {
	CodeHash    string `json:"code_hash"`
	Subject     string `json:"subject"`
	Attempts    int    `json:"attempts"`
	MaxAttempts int    `json:"max_attempts"`
	ExpiresAt   int64  `json:"expires_at"`
}

// consumeOtpScript verifies and burns a code in one atomic step.
//
// The comparison happens inside Redis so a concurrent pair of requests cannot
// both succeed, and a wrong code costs an attempt whether or not the caller
// retries. Return codes: 1 consumed, 0 wrong code, -1 unknown or expired,
// -2 attempts exhausted. The caller maps all failures to the same domain error
// so a client cannot tell them apart.
var consumeOtpScript = redis.NewScript(`
local raw = redis.call('GET', KEYS[1])
if not raw then
	return -1
end

local record = cjson.decode(raw)
local matches = record.code_hash == ARGV[1]
if matches and record.subject ~= '' and record.subject ~= ARGV[2] then
	matches = false
end

if matches then
	redis.call('DEL', KEYS[1])
	return 1
end

record.attempts = record.attempts + 1
if record.max_attempts > 0 and record.attempts >= record.max_attempts then
	redis.call('DEL', KEYS[1])
	return -2
end

redis.call('SET', KEYS[1], cjson.encode(record), 'KEEPTTL')
return 0
`)

// OtpStore holds one-time verification codes with a TTL.
type OtpStore struct {
	store
}

// NewOtpStore wires the OTP adapter.
func NewOtpStore(client *redis.Client, keys *keybuilder.Builder) *OtpStore {
	return &OtpStore{store{client: client, keys: keys}}
}

// Issue replaces any pending challenge for the same account and purpose, so
// only the newest code can be used.
func (s *OtpStore) Issue(ctx context.Context, challenge port.OtpChallenge, ttl time.Duration) error {
	record := otpRecord{
		CodeHash:    challenge.CodeHash,
		Subject:     challenge.Subject,
		MaxAttempts: challenge.MaxAttempts,
		ExpiresAt:   challenge.ExpiresAt.UTC().Unix(),
	}
	payload, err := json.Marshal(record)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}

	key := s.keys.OTP(challenge.Purpose, challenge.AccountID.String())
	return mapError(s.client.Set(ctx, key, payload, ttl).Err())
}

// Consume verifies and burns the code. Every failure mode returns ErrOtpInvalid
// so a replay, a wrong code and an expired code are indistinguishable.
func (s *OtpStore) Consume(ctx context.Context, purpose string, accountID uuid.UUID, codeHash, expectedSubject string) error {
	key := s.keys.OTP(purpose, accountID.String())

	outcome, err := consumeOtpScript.Run(ctx, s.client, []string{key}, codeHash, expectedSubject).Int64()
	if err != nil {
		return mapError(err)
	}
	if outcome == 1 {
		return nil
	}
	return apperr.ErrOtpInvalid
}

// RegisterResend counts one resend inside the window and returns the new count.
func (s *OtpStore) RegisterResend(ctx context.Context, purpose string, accountID uuid.UUID, window time.Duration) (int, error) {
	if window <= 0 {
		return 0, nil
	}

	key := s.keys.OTPResend(purpose, accountID.String())
	raw, err := fixedWindowScript.Run(ctx, s.client, []string{key}, window.Milliseconds()).Slice()
	if err != nil {
		return 0, mapError(err)
	}
	if len(raw) != 2 {
		return 0, mapError(errUnexpectedScriptResult)
	}
	count, _ := raw[0].(int64)
	return int(count), nil
}
