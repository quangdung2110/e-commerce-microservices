package circuitbreaker

import (
	"context"
	"errors"
	"sync"
	"time"

	"go.uber.org/zap"
)

// State represents the circuit breaker state
type State int

const (
	StateClosed State = iota
	StateOpen
	StateHalfOpen
)

func (s State) String() string {
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

// Config holds circuit breaker configuration
type Config struct {
	// MaxFailures is the number of consecutive failures before opening the circuit
	MaxFailures int

	// FailureTimeout is the timeout before attempting to close the circuit (seconds)
	FailureTimeout time.Duration

	// HalfOpenMaxRequests is the max requests allowed in half-open state
	HalfOpenMaxRequests int

	// Name is the circuit breaker name (e.g., "product-service")
	Name string
}

// DefaultConfig returns a default configuration
func DefaultConfig(name string) Config {
	return Config{
		MaxFailures:         5,
		FailureTimeout:      30 * time.Second,
		HalfOpenMaxRequests: 3,
		Name:                name,
	}
}

// CircuitBreaker implements the circuit breaker pattern
type CircuitBreaker struct {
	config Config
	state  State

	mu              sync.RWMutex
	failures       int
	lastFailure    time.Time
	halfOpenRequests int

	// CallFunc is the function to call through the circuit breaker
	CallFunc func(ctx context.Context) error

	logger *zap.Logger
}

// New creates a new CircuitBreaker
func New(config Config, logger *zap.Logger) *CircuitBreaker {
	return &CircuitBreaker{
		config: config,
		state:  StateClosed,
		logger: logger,
	}
}

// Call executes the function through the circuit breaker
func (cb *CircuitBreaker) Call(ctx context.Context, callFunc func(ctx context.Context) error) error {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	// Check if we should transition from open to half-open
	if cb.state == StateOpen {
		if time.Since(cb.lastFailure) >= cb.config.FailureTimeout {
			cb.transitionToHalfOpen()
		} else {
			return ErrCircuitOpen
		}
	}

	switch cb.state {
	case StateClosed:
		return cb.callClosed(ctx, callFunc)
	case StateHalfOpen:
		return cb.callHalfOpen(ctx, callFunc)
	}
	return errors.New("unknown circuit breaker state")
}

func (cb *CircuitBreaker) callClosed(ctx context.Context, callFunc func(ctx context.Context) error) error {
	err := callFunc(ctx)
	if err != nil {
		cb.recordFailure()
		return err
	}
	cb.resetFailures()
	return nil
}

func (cb *CircuitBreaker) callHalfOpen(ctx context.Context, callFunc func(ctx context.Context) error) error {
	// Limit requests in half-open state
	if cb.halfOpenRequests >= cb.config.HalfOpenMaxRequests {
		return errors.New("circuit breaker half-open request limit reached")
	}

	cb.halfOpenRequests++
	err := callFunc(ctx)

	if err != nil {
		cb.transitionToOpen()
		return err
	}

	// Success in half-open state - close the circuit
	cb.transitionToClosed()
	return nil
}

func (cb *CircuitBreaker) recordFailure() {
	cb.failures++
	cb.lastFailure = time.Now()

	cb.logger.Warn("circuit breaker failure recorded",
		zap.String("name", cb.config.Name),
		zap.Int("failures", cb.failures),
		zap.Int("max_failures", cb.config.MaxFailures),
	)

	if cb.failures >= cb.config.MaxFailures {
		cb.transitionToOpen()
	}
}

func (cb *CircuitBreaker) resetFailures() {
	if cb.failures > 0 {
		cb.failures = 0
		cb.logger.Info("circuit breaker failures reset",
			zap.String("name", cb.config.Name),
		)
	}
}

func (cb *CircuitBreaker) transitionToOpen() {
	if cb.state != StateOpen {
		cb.state = StateOpen
		cb.logger.Warn("circuit breaker transitioned to OPEN",
			zap.String("name", cb.config.Name),
			zap.Duration("failure_timeout", cb.config.FailureTimeout),
		)
	}
}

func (cb *CircuitBreaker) transitionToHalfOpen() {
	if cb.state != StateHalfOpen {
		cb.state = StateHalfOpen
		cb.halfOpenRequests = 0
		cb.logger.Info("circuit breaker transitioned to HALF-OPEN",
			zap.String("name", cb.config.Name),
		)
	}
}

func (cb *CircuitBreaker) transitionToClosed() {
	if cb.state != StateClosed {
		cb.state = StateClosed
		cb.failures = 0
		cb.halfOpenRequests = 0
		cb.logger.Info("circuit breaker transitioned to CLOSED",
			zap.String("name", cb.config.Name),
		)
	}
}

// State returns the current state of the circuit breaker
func (cb *CircuitBreaker) State() State {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.state
}

// Failures returns the current failure count
func (cb *CircuitBreaker) Failures() int {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.failures
}

// Reset manually resets the circuit breaker to closed state
func (cb *CircuitBreaker) Reset() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.transitionToClosed()
}

// Common errors
var (
	ErrCircuitOpen = errors.New("circuit breaker is open")
)

// IsCircuitOpenError checks if the error is due to circuit being open
func IsCircuitOpenError(err error) bool {
	return errors.Is(err, ErrCircuitOpen)
}
