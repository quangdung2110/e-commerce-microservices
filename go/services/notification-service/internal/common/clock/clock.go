// Package clock abstracts time so Decide (quiet hours, expiry) and the queue
// mechanics (lease, backoff) can be tested with a frozen or stepped clock.
package clock

import (
	"sync"
	"time"
)

// Clock returns the current instant.
type Clock interface {
	Now() time.Time
}

// System is the wall clock.
type System struct{}

func (System) Now() time.Time { return time.Now().UTC() }

// Fake is a controllable clock for tests.
type Fake struct {
	mu  sync.Mutex
	now time.Time
}

func NewFake(now time.Time) *Fake { return &Fake{now: now.UTC()} }

func (f *Fake) Now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now
}

func (f *Fake) Set(now time.Time) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.now = now.UTC()
}

func (f *Fake) Advance(d time.Duration) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.now = f.now.Add(d)
}
