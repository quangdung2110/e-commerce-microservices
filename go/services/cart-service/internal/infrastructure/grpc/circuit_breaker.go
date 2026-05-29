package grpc

import (
	"context"
	"sync"
	"time"

	"cart-service/internal/infrastructure/config"
)

// CircuitBreakerState represents the state of the circuit breaker.
type CircuitBreakerState int

const (
	StateClosed CircuitBreakerState = iota
	StateOpen
	StateHalfOpen
)

func (s CircuitBreakerState) String() string {
	switch s {
	case StateClosed:
		return "closed"
	case StateOpen:
		return "open"
	case StateHalfOpen:
		return "half-open"
	default:
		return "unknown"
	}
}

// CircuitBreaker implements the circuit breaker pattern for gRPC calls.
type CircuitBreaker struct {
	name         string
	maxRequests  uint32
	interval     time.Duration
	timeout      time.Duration
	state        CircuitBreakerState
	stateMu      sync.RWMutex

	// Counters
	counts struct {
		mu          sync.RWMutex
		requests    uint32
		successes   uint32
		failures    uint32
		lastSuccess time.Time
		lastFailure time.Time
	}

	// Timer for transitioning open -> half-open
	nextAttempt time.Time
	timerMu     sync.Mutex
}

// NewCircuitBreaker creates a new CircuitBreaker from config.
func NewCircuitBreaker(cfg config.CircuitBreakerCfg) (*CircuitBreaker, error) {
	interval, err := time.ParseDuration(cfg.Interval)
	if err != nil {
		return nil, err
	}
	timeout, err := time.ParseDuration(cfg.Timeout)
	if err != nil {
		return nil, err
	}

	return &CircuitBreaker{
		name:        cfg.Name,
		maxRequests: uint32(cfg.MaxRequests),
		interval:    interval,
		timeout:     timeout,
		state:       StateClosed,
		nextAttempt: time.Now().Add(timeout),
	}, nil
}

// Allow checks if a request should be permitted through.
func (cb *CircuitBreaker) Allow() bool {
	cb.stateMu.RLock()
	state := cb.state
	cb.stateMu.RUnlock()

	switch state {
	case StateClosed:
		return true

	case StateOpen:
		cb.timerMu.Lock()
		defer cb.timerMu.Unlock()
		if time.Now().After(cb.nextAttempt) {
			cb.transitionTo(StateHalfOpen)
			return true
		}
		return false

	case StateHalfOpen:
		cb.counts.mu.Lock()
		allowed := cb.counts.requests < cb.maxRequests
		if allowed {
			cb.counts.requests++
		}
		cb.counts.mu.Unlock()
		return allowed

	default:
		return false
	}
}

// RecordSuccess records a successful call.
func (cb *CircuitBreaker) RecordSuccess() {
	cb.counts.mu.Lock()
	cb.counts.successes++
	cb.counts.lastSuccess = time.Now()

	if cb.state == StateHalfOpen {
		cb.counts.requests = 0
		cb.transitionTo(StateClosed)
	}
	cb.counts.mu.Unlock()
}

// RecordFailure records a failed call.
func (cb *CircuitBreaker) RecordFailure() {
	cb.counts.mu.Lock()
	cb.counts.failures++
	cb.counts.lastFailure = time.Now()

	if cb.state == StateHalfOpen {
		cb.transitionTo(StateOpen)
		cb.nextAttempt = time.Now().Add(cb.timeout)
	} else if cb.counts.failures >= cb.maxRequests {
		cb.transitionTo(StateOpen)
		cb.nextAttempt = time.Now().Add(cb.timeout)
	}
	cb.counts.mu.Unlock()
}

// State returns the current circuit state.
func (cb *CircuitBreaker) State() CircuitBreakerState {
	cb.stateMu.RLock()
	defer cb.stateMu.RUnlock()
	return cb.state
}

// Name returns the circuit breaker name.
func (cb *CircuitBreaker) Name() string {
	return cb.name
}

// Metrics returns a snapshot of circuit breaker metrics.
func (cb *CircuitBreaker) Metrics() CircuitBreakerMetrics {
	cb.stateMu.RLock()
	state := cb.state
	cb.stateMu.RUnlock()

	cb.counts.mu.RLock()
	defer cb.counts.mu.RUnlock()
	return CircuitBreakerMetrics{
		Name:         cb.name,
		State:        state.String(),
		Successes:    cb.counts.successes,
		Failures:     cb.counts.failures,
		LastSuccess:  cb.counts.lastSuccess,
		LastFailure:  cb.counts.lastFailure,
		NextAttempt:   cb.nextAttempt,
	}
}

// transitionTo changes the circuit state (must hold stateMu for write).
func (cb *CircuitBreaker) transitionTo(state CircuitBreakerState) {
	cb.stateMu.Lock()
	cb.state = state
	cb.stateMu.Unlock()
}

// CircuitBreakerMetrics holds a snapshot of the circuit breaker state.
type CircuitBreakerMetrics struct {
	Name        string
	State       string
	Successes   uint32
	Failures    uint32
	LastSuccess time.Time
	LastFailure time.Time
	NextAttempt time.Time
}

// HealthCheck returns true when circuit is closed or half-open (allowing traffic).
func (cb *CircuitBreaker) HealthCheck(ctx context.Context) bool {
	return cb.Allow()
}
