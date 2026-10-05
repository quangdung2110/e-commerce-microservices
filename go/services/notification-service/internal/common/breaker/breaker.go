// Package breaker implements the circuit breaker of
// docs/notification/06-backpressure.md §5: a sliding window over the last N
// results opens the circuit when the failure ratio crosses the threshold;
// after open_duration it lets a few real requests probe (half-open) and closes
// again after enough consecutive successes. One breaker guards one dependency
// (provider, auth_contact, auth_content); the reaction per lane is decided by
// the caller.
package breaker

import (
	"sync"
	"time"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
)

// States as exposed by port.CircuitBreaker.State and the
// circuit_breaker_state gauge (14-observability.md §2.3).
const (
	StateClosed   = 0
	StateHalfOpen = 1
	StateOpen     = 2
)

// Breaker is a thread-safe circuit breaker driven by an injected clock so the
// open→half-open transition can be tested without sleeping.
type Breaker struct {
	mu sync.Mutex

	clk           clock.Clock
	windowSize    int
	failureRatio  float64
	openDuration  time.Duration
	halfOpenProbe int
	onStateChange func(state int)

	state int

	// Sliding window of the last windowSize results (true = failure).
	window   []bool
	next     int // ring index
	count    int // results recorded (<= windowSize)
	failures int

	openedAt       time.Time
	halfOpenSince  time.Time
	probesInFlight int
	probeSuccesses int
}

// Option customises a Breaker.
type Option func(*Breaker)

// WithStateChange registers a hook called (outside the lock) whenever the
// state changes; the caller uses it to update the circuit_breaker_state gauge.
func WithStateChange(fn func(state int)) Option {
	return func(b *Breaker) { b.onStateChange = fn }
}

// New builds a breaker from the configured thresholds. Zero or negative values
// fall back to the documented defaults (window 20, ratio 0.5, open 30 s,
// 3 half-open successes).
func New(cfg config.BreakerConfig, clk clock.Clock, opts ...Option) *Breaker {
	if cfg.WindowSize <= 0 {
		cfg.WindowSize = 20
	}
	if cfg.FailureRatio <= 0 {
		cfg.FailureRatio = 0.5
	}
	if cfg.OpenDuration <= 0 {
		cfg.OpenDuration = 30 * time.Second
	}
	if cfg.HalfOpenSuccesses <= 0 {
		cfg.HalfOpenSuccesses = 3
	}
	if clk == nil {
		clk = clock.System{}
	}
	b := &Breaker{
		clk:           clk,
		windowSize:    cfg.WindowSize,
		failureRatio:  cfg.FailureRatio,
		openDuration:  cfg.OpenDuration,
		halfOpenProbe: cfg.HalfOpenSuccesses,
		window:        make([]bool, cfg.WindowSize),
		state:         StateClosed,
	}
	for _, o := range opts {
		o(b)
	}
	return b
}

// Allow reports whether a call may proceed. Closed: always. Open: only after
// open_duration, which flips the breaker to half-open. Half-open: at most
// half_open_successes probes at a time; every other caller is refused so a
// dead provider is not flooded the moment the timer expires.
func (b *Breaker) Allow() bool {
	b.mu.Lock()
	changed, state := b.tick()
	var allowed bool
	switch b.state {
	case StateClosed:
		allowed = true
	case StateHalfOpen:
		if b.probesInFlight < b.halfOpenProbe {
			b.probesInFlight++
			allowed = true
		}
	default:
		allowed = false
	}
	b.mu.Unlock()
	if changed {
		b.notify(state)
	}
	return allowed
}

// Success records a successful call.
func (b *Breaker) Success() {
	b.mu.Lock()
	changed, state := b.tick()
	switch b.state {
	case StateClosed:
		b.record(false)
	case StateHalfOpen:
		if b.probesInFlight > 0 {
			b.probesInFlight--
		}
		b.probeSuccesses++
		if b.probeSuccesses >= b.halfOpenProbe {
			b.resetWindow()
			b.state = StateClosed
			changed, state = true, StateClosed
		}
	case StateOpen:
		// A call that was allowed before the circuit opened; nothing to learn.
	}
	b.mu.Unlock()
	if changed {
		b.notify(state)
	}
}

// Failure records a failed call. In closed state it may open the circuit; in
// half-open state a single failed probe reopens it for another open_duration.
func (b *Breaker) Failure() {
	b.mu.Lock()
	changed, state := b.tick()
	switch b.state {
	case StateClosed:
		b.record(true)
		if b.count >= b.windowSize && float64(b.failures)/float64(b.count) > b.failureRatio {
			b.open()
			changed, state = true, StateOpen
		}
	case StateHalfOpen:
		b.open()
		changed, state = true, StateOpen
	case StateOpen:
	}
	b.mu.Unlock()
	if changed {
		b.notify(state)
	}
}

// State returns 0 closed, 1 half-open, 2 open, applying the time-based
// open→half-open transition first so the gauge never lags behind Allow.
func (b *Breaker) State() int {
	b.mu.Lock()
	changed, state := b.tick()
	s := b.state
	b.mu.Unlock()
	if changed {
		b.notify(state)
	}
	return s
}

// tick applies time-driven transitions; caller holds the lock. It returns
// whether the state changed and the new state.
func (b *Breaker) tick() (bool, int) {
	now := b.clk.Now()
	switch b.state {
	case StateOpen:
		if !now.Before(b.openedAt.Add(b.openDuration)) {
			b.state = StateHalfOpen
			b.halfOpenSince = now
			b.probesInFlight = 0
			b.probeSuccesses = 0
			return true, StateHalfOpen
		}
	case StateHalfOpen:
		// A probe that never reported (caller released the row, crashed, ...)
		// must not wedge the breaker half-open forever: after another
		// open_duration without a verdict, let new probes through.
		if b.probesInFlight > 0 && !now.Before(b.halfOpenSince.Add(b.openDuration)) {
			b.probesInFlight = 0
			b.halfOpenSince = now
		}
	}
	return false, b.state
}

func (b *Breaker) open() {
	b.state = StateOpen
	b.openedAt = b.clk.Now()
	b.probesInFlight = 0
	b.probeSuccesses = 0
	b.resetWindow()
}

func (b *Breaker) record(failure bool) {
	if b.count == b.windowSize {
		// Evict the oldest result.
		if b.window[b.next] {
			b.failures--
		}
	} else {
		b.count++
	}
	b.window[b.next] = failure
	if failure {
		b.failures++
	}
	b.next = (b.next + 1) % b.windowSize
}

func (b *Breaker) resetWindow() {
	for i := range b.window {
		b.window[i] = false
	}
	b.next, b.count, b.failures = 0, 0, 0
}

func (b *Breaker) notify(state int) {
	if b.onStateChange != nil {
		b.onStateChange(state)
	}
}
