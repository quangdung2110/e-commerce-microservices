package redis

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/keybuilder"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/port"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

var _ port.AddressCache = (*AddressCache)(nil)

// cacheVersion prefixes the stored document. Bumping it retires every existing
// value at once, which is how a serialization change stays safe while old
// readers are still deployed (ADR-002).
const cacheVersion = 1

// cachedAddress is the wire format of one address inside the cached list. It is
// explicit rather than a reuse of the entity, so a domain refactor cannot
// silently change what is already stored in Redis.
type cachedAddress struct {
	ID             string `json:"id"`
	IsDefault      bool   `json:"is_default"`
	RecipientName  string `json:"recipient_name"`
	RecipientPhone string `json:"recipient_phone"`
	AddressLine    string `json:"address_line"`
	ProvinceCode   string `json:"province_code"`
	DistrictCode   string `json:"district_code"`
	WardCode       string `json:"ward_code"`
	CreatedAt      string `json:"created_at"`
	UpdatedAt      string `json:"updated_at"`
}

type cachedAddressList struct {
	Version   int             `json:"version"`
	Addresses []cachedAddress `json:"addresses"`
}

// AddressCache is the cache-aside copy of an address book.
//
// The value carries recipient names, phone numbers and street addresses, so it
// is stored under a per-user key with a bounded TTL and is dropped as soon as
// the account is anonymized.
type AddressCache struct {
	store
	ttl      time.Duration
	emptyTTL time.Duration
	logger   *zap.Logger
}

// NewAddressCache wires the address cache. ttl is the normal lifetime of a
// list; emptyTTL is the shorter lifetime of an empty one, which keeps a user
// without addresses from becoming a cache-penetration path.
func NewAddressCache(client *redis.Client, keys *keybuilder.Builder, ttl, emptyTTL time.Duration, logger *zap.Logger) *AddressCache {
	return &AddressCache{
		store:    store{client: client, keys: keys},
		ttl:      ttl,
		emptyTTL: emptyTTL,
		logger:   logger,
	}
}

// Get returns the cached list. It reports a miss for an absent key, for content
// it cannot decode and for a Redis outage: every one of those cases is served
// correctly by falling back to PostgreSQL.
func (c *AddressCache) Get(ctx context.Context, userID uuid.UUID) ([]*entity.Address, bool) {
	raw, err := c.client.Get(ctx, c.keys.Addresses(userID.String())).Bytes()
	if err != nil {
		if !errors.Is(err, redis.Nil) {
			c.logger.Warn("address cache read failed, falling back to the database",
				zap.String("user_id", userID.String()),
				zap.Error(err),
			)
		}
		return nil, false
	}

	var document cachedAddressList
	if err := json.Unmarshal(raw, &document); err != nil || document.Version != cacheVersion {
		// Corrupt or superseded content is never returned to a caller.
		c.logger.Warn("address cache content is unusable, falling back to the database",
			zap.String("user_id", userID.String()))
		return nil, false
	}

	addresses := make([]*entity.Address, 0, len(document.Addresses))
	for _, item := range document.Addresses {
		address, err := item.toEntity(userID)
		if err != nil {
			c.logger.Warn("address cache entry could not be decoded, falling back to the database",
				zap.String("user_id", userID.String()))
			return nil, false
		}
		addresses = append(addresses, address)
	}
	return addresses, true
}

// epochMissing is the epoch of a user whose counter does not exist yet (never
// invalidated, or expired). Put compares against the same default, so a user
// without a counter can still be filled.
const epochMissing = "0"

// fillScript stores the list only while the epoch the reader saw is still the
// current one. Reading the counter and writing the value in one script is what
// closes the window between the check and the SET.
//
//	KEYS[1] epoch key   KEYS[2] list key
//	ARGV[1] epoch seen  ARGV[2] payload  ARGV[3] ttl in milliseconds
var fillScript = redis.NewScript(`
local current = redis.call('GET', KEYS[1])
if not current then current = '0' end
if current ~= ARGV[1] then return 0 end
redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
return 1
`)

// invalidateScript advances the epoch and drops the list together, so no fill
// that started before the mutation can land after it.
//
//	KEYS[1] epoch key   KEYS[2] list key   ARGV[1] epoch ttl in milliseconds
var invalidateScript = redis.NewScript(`
redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
redis.call('DEL', KEYS[2])
return 1
`)

// Epoch reads the invalidation counter. "" means Redis could not answer, which
// makes the following Put a no-op: filling blind could cache a stale list.
func (c *AddressCache) Epoch(ctx context.Context, userID uuid.UUID) string {
	value, err := c.client.Get(ctx, c.keys.AddressesEpoch(userID.String())).Result()
	switch {
	case err == nil:
		return value
	case errors.Is(err, redis.Nil):
		return epochMissing
	default:
		c.logger.Warn("address cache epoch could not be read, the fill is skipped",
			zap.String("user_id", userID.String()),
			zap.Error(err),
		)
		return ""
	}
}

// Put stores the list with the TTL that matches its size, unless a mutation
// invalidated the cache after the reader took the epoch.
func (c *AddressCache) Put(ctx context.Context, userID uuid.UUID, addresses []*entity.Address, epoch string) error {
	if epoch == "" {
		return nil
	}

	document := cachedAddressList{Version: cacheVersion, Addresses: make([]cachedAddress, 0, len(addresses))}
	for _, address := range addresses {
		document.Addresses = append(document.Addresses, fromEntity(address))
	}

	payload, err := json.Marshal(document)
	if err != nil {
		return mapError(err)
	}

	ttl := c.ttl
	if len(addresses) == 0 {
		ttl = c.emptyTTL
	}
	filled, err := fillScript.Run(ctx, c.client,
		[]string{c.keys.AddressesEpoch(userID.String()), c.keys.Addresses(userID.String())},
		epoch, payload, ttl.Milliseconds(),
	).Int()
	if err != nil {
		return mapError(err)
	}
	if filled == 0 {
		c.logger.Debug("address cache fill refused: the list changed while it was being read",
			zap.String("user_id", userID.String()))
	}
	return nil
}

// Invalidate drops the key and advances the epoch after a committed mutation.
// The caller has already committed, so a failure here is reported for alerting
// but never rolled back; the TTL bounds how long a stale list can survive.
func (c *AddressCache) Invalidate(ctx context.Context, userID uuid.UUID) error {
	// The counter has to outlive any fill that could still be in flight, which is
	// bounded by the request timeout; the list TTL is a generous upper bound.
	err := invalidateScript.Run(ctx, c.client,
		[]string{c.keys.AddressesEpoch(userID.String()), c.keys.Addresses(userID.String())},
		c.ttl.Milliseconds(),
	).Err()
	if err != nil {
		return mapError(err)
	}
	return nil
}

func fromEntity(address *entity.Address) cachedAddress {
	return cachedAddress{
		ID:             address.ID.String(),
		IsDefault:      address.IsDefault,
		RecipientName:  address.RecipientName,
		RecipientPhone: address.RecipientPhone.String(),
		AddressLine:    address.AddressLine,
		ProvinceCode:   address.ProvinceCode,
		DistrictCode:   address.DistrictCode,
		WardCode:       address.WardCode,
		CreatedAt:      address.CreatedAt.UTC().Format(time.RFC3339Nano),
		UpdatedAt:      address.UpdatedAt.UTC().Format(time.RFC3339Nano),
	}
}

func (a cachedAddress) toEntity(userID uuid.UUID) (*entity.Address, error) {
	id, err := uuid.Parse(a.ID)
	if err != nil {
		return nil, err
	}
	createdAt, err := time.Parse(time.RFC3339Nano, a.CreatedAt)
	if err != nil {
		return nil, err
	}
	updatedAt, err := time.Parse(time.RFC3339Nano, a.UpdatedAt)
	if err != nil {
		return nil, err
	}

	return &entity.Address{
		ID:             id,
		UserID:         userID,
		IsDefault:      a.IsDefault,
		RecipientName:  a.RecipientName,
		RecipientPhone: vo.MustPhone(a.RecipientPhone),
		AddressLine:    a.AddressLine,
		ProvinceCode:   a.ProvinceCode,
		DistrictCode:   a.DistrictCode,
		WardCode:       a.WardCode,
		CreatedAt:      createdAt.UTC(),
		UpdatedAt:      updatedAt.UTC(),
	}, nil
}
