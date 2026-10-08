// Package keybuilder centralises every Redis key User Service writes.
//
// The address-list key is pinned by docs/user/data.md §6 as
// `user:caches:addresses:{user_id}`; the cache prefix is configurable only so a
// deployment can isolate environments inside one Redis logical store.
package keybuilder

import "fmt"

// Builder produces namespaced keys.
type Builder struct {
	cachePrefix string
	statePrefix string
}

// New returns a Builder. cachePrefix defaults to "user:caches" and statePrefix
// to "user".
func New(cachePrefix, statePrefix string) *Builder {
	if cachePrefix == "" {
		cachePrefix = "user:caches"
	}
	if statePrefix == "" {
		statePrefix = "user"
	}
	return &Builder{cachePrefix: cachePrefix, statePrefix: statePrefix}
}

// Addresses keys the cached address book of one user. PostgreSQL stays the
// source of truth; this value is a TTL-bound copy (ADR-002).
func (b *Builder) Addresses(userID string) string {
	return fmt.Sprintf("%s:addresses:%s", b.cachePrefix, userID)
}

// AddressesEpoch keys the invalidation counter of one user's cached address
// book. It lets a fill that raced with a committed mutation be refused instead
// of re-caching a stale list until the TTL expires.
func (b *Builder) AddressesEpoch(userID string) string {
	return fmt.Sprintf("%s:addresses-epoch:%s", b.cachePrefix, userID)
}

// RateLimit keys a fixed-window counter for one scope and subject.
func (b *Builder) RateLimit(scope, subject string) string {
	return fmt.Sprintf("%s:ratelimit:%s:%s", b.statePrefix, scope, subject)
}

// WorkerLock keys the distributed lock that keeps a scheduled run singleton.
func (b *Builder) WorkerLock(name string) string {
	return fmt.Sprintf("%s:lock:%s", b.statePrefix, name)
}
