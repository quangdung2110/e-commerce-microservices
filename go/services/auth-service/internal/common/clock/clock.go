// Package clock abstracts the current time so lockout windows, token expiry and
// retention rules can be tested deterministically.
package clock

import (
	"sync"
	"time"
)

// Clock reports the current time in UTC.
type Clock interface {
	Now() time.Time
}

// System is the production clock.
type System struct{}

func (System) Now() time.Time { return time.Now().UTC() }

// Fixed is a controllable clock for tests.
type Fixed struct {
	mu  sync.Mutex
	now time.Time
}

// NewFixed returns a clock pinned at the given instant.
func NewFixed(now time.Time) *Fixed {
	return &Fixed{now: now.UTC()}
}

func (f *Fixed) Now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now
}

// Advance moves the clock forward.
func (f *Fixed) Advance(d time.Duration) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.now = f.now.Add(d)
}

// Set moves the clock to an absolute instant.
func (f *Fixed) Set(t time.Time) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.now = t.UTC()
}
