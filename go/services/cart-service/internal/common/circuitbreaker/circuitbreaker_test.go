package circuitbreaker

import (
	"context"
	"errors"
	"testing"
	"time"

	"go.uber.org/zap"
)

func TestCircuitBreaker_ClosedState(t *testing.T) {
	logger := zap.NewNop()
	cb := New(DefaultConfig("test"), logger)

	if cb.State() != StateClosed {
		t.Errorf("Initial state should be closed, got %v", cb.State())
	}

	// Successful calls should keep circuit closed
	for i := 0; i < 3; i++ {
		err := cb.Call(context.Background(), func(ctx context.Context) error {
			return nil
		})
		if err != nil {
			t.Errorf("Successful call should not return error: %v", err)
		}
	}

	if cb.State() != StateClosed {
		t.Errorf("State should still be closed after successful calls, got %v", cb.State())
	}
}

func TestCircuitBreaker_OpenState(t *testing.T) {
	logger := zap.NewNop()
	config := DefaultConfig("test")
	config.MaxFailures = 3
	cb := New(config, logger)

	// Trigger failures to open circuit
	for i := 0; i < 3; i++ {
		cb.Call(context.Background(), func(ctx context.Context) error {
			return errors.New("test error")
		})
	}

	if cb.State() != StateOpen {
		t.Errorf("State should be open after max failures, got %v", cb.State())
	}

	// Call should fail with circuit open error
	err := cb.Call(context.Background(), func(ctx context.Context) error {
		return nil
	})

	if !IsCircuitOpenError(err) {
		t.Errorf("Call should return circuit open error when open, got: %v", err)
	}
}

func TestCircuitBreaker_HalfOpenState(t *testing.T) {
	logger := zap.NewNop()
	config := DefaultConfig("test")
	config.MaxFailures = 2
	config.FailureTimeout = 100 * time.Millisecond
	config.HalfOpenMaxRequests = 2
	cb := New(config, logger)

	// Open the circuit
	for i := 0; i < 2; i++ {
		cb.Call(context.Background(), func(ctx context.Context) error {
			return errors.New("test error")
		})
	}

	if cb.State() != StateOpen {
		t.Errorf("State should be open after max failures, got %v", cb.State())
	}

	// Wait for failure timeout
	time.Sleep(150 * time.Millisecond)

	// First call after timeout should transition to half-open and succeed
	err := cb.Call(context.Background(), func(ctx context.Context) error {
		return nil
	})

	if err != nil {
		t.Errorf("First call after timeout should succeed, got: %v", err)
	}

	// State should be closed after successful call in half-open
	if cb.State() != StateClosed {
		t.Errorf("State should be closed after successful half-open call, got %v", cb.State())
	}
}

func TestCircuitBreaker_FailureAfterHalfOpen(t *testing.T) {
	logger := zap.NewNop()
	config := DefaultConfig("test")
	config.MaxFailures = 1 // Fail immediately with 1 failure
	config.FailureTimeout = 100 * time.Millisecond
	config.HalfOpenMaxRequests = 1
	cb := New(config, logger)

	// Fail once - circuit opens immediately due to MaxFailures=1
	err := cb.Call(context.Background(), func(ctx context.Context) error {
		return errors.New("test error")
	})
	if err == nil {
		t.Error("Failed call should return error")
	}

	if cb.State() != StateOpen {
		t.Errorf("State should be open after failure, got %v", cb.State())
	}

	// Wait for failure timeout
	time.Sleep(150 * time.Millisecond)

	// First call after timeout transitions to half-open and succeeds (HalfOpenMaxRequests=1)
	err = cb.Call(context.Background(), func(ctx context.Context) error {
		return nil
	})
	if err != nil {
		t.Errorf("First call in half-open should succeed, got: %v", err)
	}

	// State should be closed after successful call in half-open
	if cb.State() != StateClosed {
		t.Errorf("State should be closed after successful half-open call, got %v", cb.State())
	}

	// Now fail again - circuit should open
	err = cb.Call(context.Background(), func(ctx context.Context) error {
		return errors.New("test error after recovery")
	})
	if err == nil {
		t.Error("Failed call should return error")
	}

	if cb.State() != StateOpen {
		t.Errorf("State should be open after failure in closed state, got %v", cb.State())
	}
}

func TestCircuitBreaker_Reset(t *testing.T) {
	logger := zap.NewNop()
	config := DefaultConfig("test")
	config.MaxFailures = 2
	cb := New(config, logger)

	// Open the circuit
	for i := 0; i < 2; i++ {
		cb.Call(context.Background(), func(ctx context.Context) error {
			return errors.New("test error")
		})
	}

	if cb.State() != StateOpen {
		t.Errorf("State should be open, got %v", cb.State())
	}

	// Reset
	cb.Reset()

	if cb.State() != StateClosed {
		t.Errorf("State should be closed after reset, got %v", cb.State())
	}
	if cb.Failures() != 0 {
		t.Errorf("Failures should be 0 after reset, got %d", cb.Failures())
	}
}

func TestCircuitBreaker_StateString(t *testing.T) {
	tests := []struct {
		state   State
		want    string
	}{
		{StateClosed, "closed"},
		{StateOpen, "open"},
		{StateHalfOpen, "half-open"},
		{State(99), "unknown"},
	}

	for _, tt := range tests {
		if got := tt.state.String(); got != tt.want {
			t.Errorf("State(%d).String() = %v, want %v", tt.state, got, tt.want)
		}
	}
}

func TestIsCircuitOpenError(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want bool
	}{
		{
			name: "circuit open error",
			err:  ErrCircuitOpen,
			want: true,
		},
		{
			name: "wrapped circuit open error",
			err:  errors.New("wrapped: " + ErrCircuitOpen.Error()),
			want: false,
		},
		{
			name: "other error",
			err:  errors.New("other error"),
			want: false,
		},
		{
			name: "nil error",
			err:  nil,
			want: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := IsCircuitOpenError(tt.err); got != tt.want {
				t.Errorf("IsCircuitOpenError() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestDefaultConfig(t *testing.T) {
	config := DefaultConfig("product-service")

	if config.Name != "product-service" {
		t.Errorf("Name = %v, want product-service", config.Name)
	}
	if config.MaxFailures != 5 {
		t.Errorf("MaxFailures = %v, want 5", config.MaxFailures)
	}
	if config.FailureTimeout != 30*time.Second {
		t.Errorf("FailureTimeout = %v, want 30s", config.FailureTimeout)
	}
	if config.HalfOpenMaxRequests != 3 {
		t.Errorf("HalfOpenMaxRequests = %v, want 3", config.HalfOpenMaxRequests)
	}
}
