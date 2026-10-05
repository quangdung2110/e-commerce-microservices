package redis

import (
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// Keys builds the namespaced key layout of 09-data-model.md §2 (layouts in
// internal/common/constants). Every key is prefixed with redis.key_prefix so
// several services can share one Redis without colliding.
type Keys struct {
	prefix string
}

// NewKeys builds a key builder; an empty prefix yields unprefixed keys.
func NewKeys(prefix string) Keys { return Keys{prefix: prefix} }

func (k Keys) join(key string) string {
	if k.prefix == "" {
		return key
	}
	return k.prefix + ":" + key
}

// Contact is contact:{user_id} (JSON contact, TTL 15 min, 11-privacy.md §2).
func (k Keys) Contact(userID uuid.UUID) string {
	return k.join(fmt.Sprintf(constants.RedisKeyContact, userID.String()))
}

// FreqCap is freq:{user_id}:{YYYY-MM-DD local date} (08-decide.md §6).
func (k Keys) FreqCap(userID uuid.UUID, localDate string) string {
	return k.join(fmt.Sprintf(constants.RedisKeyFreqCap, userID.String(), localDate))
}

// FreqCapPattern matches every frequency-cap key of a user (user.deleted).
func (k Keys) FreqCapPattern(userID uuid.UUID) string {
	return k.join(fmt.Sprintf(constants.RedisKeyFreqCap, userID.String(), "*"))
}

// Unread is inbox:unread:{user_id} (09-data-model.md §7).
func (k Keys) Unread(userID uuid.UUID) string {
	return k.join(fmt.Sprintf(constants.RedisKeyUnread, userID.String()))
}

// Bucket is bucket:{lane}:{channel}, the shared token bucket (06 §4.2).
func (k Keys) Bucket(lane entity.Lane, channel entity.Channel) string {
	return k.join(fmt.Sprintf(constants.RedisKeyBucket, string(lane), string(channel)))
}

// Daily is daily:{provider}:{YYYY-MM-DD UTC}, the account-level 24 h counter
// (06 §4.1). SES resets its own 24 h window on UTC days, hence UTC here.
func (k Keys) Daily(provider entity.Provider, day time.Time) string {
	return k.join(fmt.Sprintf(constants.RedisKeyDaily, string(provider), day.UTC().Format("2006-01-02")))
}

// RetryWindow is retrywin:{lane}:{retry|total}:{unix minute} (06 §6.1).
func (k Keys) RetryWindow(lane entity.Lane, kind string, unixMinute int64) string {
	return k.join(fmt.Sprintf(constants.RedisKeyRetryWin, string(lane), kind, unixMinute))
}
