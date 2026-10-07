package product

import (
	"errors"
	"sync"
	"time"
)

// ErrBreakerOpen is returned instead of dialling Product while the breaker is
// open. On a read this degrades the response; on a write it rejects it.
var ErrBreakerOpen = errors.New("product circuit breaker is open")

type breakerState int

const (
	stateClosed breakerState = iota
	stateOpen
	stateHalfOpen
)

// BreakerConfig tunes the circuit breaker guarding Product.
type BreakerConfig struct {
	// FailureThreshold is how many consecutive failures trip the breaker.
	FailureThreshold int
	// HalfOpenMaxCalls caps the probes allowed while half-open.
	HalfOpenMaxCalls int
	// OpenTimeout is how long the breaker stays open before probing again.
	OpenTimeout time.Duration
}

// breaker is a small consecutive-failure circuit breaker.
//
// Product is a hard dependency for writes and a soft one for reads, so the
// breaker exists to fail fast rather than to make policy: it never decides
// what a caller does with an open circuit.
type breaker struct {
	cfg BreakerConfig
	now func() time.Time

	mu           sync.Mutex
	state        breakerState
	failures     int
	halfOpenCall int
	openedAt     time.Time
}

func newBreaker(cfg BreakerConfig) *breaker {
	if cfg.FailureThreshold <= 0 {
		cfg.FailureThreshold = 5
	}
	if cfg.HalfOpenMaxCalls <= 0 {
		cfg.HalfOpenMaxCalls = 1
	}
	if cfg.OpenTimeout <= 0 {
		cfg.OpenTimeout = 30 * time.Second
	}
	return &breaker{cfg: cfg, now: time.Now, state: stateClosed}
}

// allow reports whether a call may proceed.
func (b *breaker) allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	switch b.state {
	case stateClosed:
		return true
	case stateOpen:
		if b.now().Sub(b.openedAt) < b.cfg.OpenTimeout {
			return false
		}
		b.state = stateHalfOpen
		b.halfOpenCall = 1
		return true
	default:
		if b.halfOpenCall >= b.cfg.HalfOpenMaxCalls {
			return false
		}
		b.halfOpenCall++
		return true
	}
}

// success records a healthy call and closes the breaker.
func (b *breaker) success() {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.failures = 0
	b.halfOpenCall = 0
	b.state = stateClosed
}

// failure records a failed call and may open the breaker.
func (b *breaker) failure() {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.failures++
	if b.state == stateHalfOpen || b.failures >= b.cfg.FailureThreshold {
		b.state = stateOpen
		b.openedAt = b.now()
		b.halfOpenCall = 0
	}
}

// isOpen reports whether the breaker is currently rejecting calls. It is used
// by the readiness probe, never to gate a call: use allow for that.
func (b *breaker) isOpen() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.state == stateOpen && b.now().Sub(b.openedAt) < b.cfg.OpenTimeout
}
