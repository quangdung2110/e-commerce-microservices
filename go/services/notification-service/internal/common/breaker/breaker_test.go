package breaker

import (
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
)

func newTestBreaker(t *testing.T) (*Breaker, *clock.Fake, *[]int) {
	t.Helper()
	clk := clock.NewFake(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC))
	var states []int
	b := New(config.BreakerConfig{
		WindowSize:        4,
		FailureRatio:      0.5,
		OpenDuration:      30 * time.Second,
		HalfOpenSuccesses: 2,
	}, clk, WithStateChange(func(s int) { states = append(states, s) }))
	return b, clk, &states
}

func TestClosedAllowsAndStaysClosedBelowThreshold(t *testing.T) {
	b, _, states := newTestBreaker(t)
	for i := 0; i < 10; i++ {
		require.True(t, b.Allow())
		if i%2 == 0 {
			b.Failure()
		} else {
			b.Success()
		}
	}
	// 2 failures / 4 = 0.5, not > 0.5.
	assert.Equal(t, StateClosed, b.State())
	assert.Empty(t, *states)
}

func TestOpensOnlyWithFullWindow(t *testing.T) {
	b, _, states := newTestBreaker(t)
	b.Failure()
	b.Failure()
	b.Failure()
	assert.Equal(t, StateClosed, b.State(), "fewer than window_size samples must not open")
	b.Failure()
	assert.Equal(t, StateOpen, b.State())
	assert.False(t, b.Allow())
	assert.Equal(t, []int{StateOpen}, *states)
}

func TestSlidingWindowEvictsOldResults(t *testing.T) {
	b, _, _ := newTestBreaker(t)
	b.Failure()
	b.Failure()
	b.Failure() // 3 failures, count 3 (not full)
	b.Success() // window full: 3/4 > 0.5 would open on a Failure, not on Success
	assert.Equal(t, StateClosed, b.State())
	b.Success()
	b.Success()
	b.Success() // window now [S,S,S,S]
	b.Failure() // [S,S,S,F] → 1/4
	b.Failure() // [S,S,F,F] → 2/4 = 0.5 not > 0.5
	assert.Equal(t, StateClosed, b.State())
	b.Failure() // 3/4 → open
	assert.Equal(t, StateOpen, b.State())
}

func TestOpenToHalfOpenToClosed(t *testing.T) {
	b, clk, states := newTestBreaker(t)
	for i := 0; i < 4; i++ {
		b.Failure()
	}
	require.Equal(t, StateOpen, b.State())
	assert.False(t, b.Allow())

	clk.Advance(29 * time.Second)
	assert.False(t, b.Allow())
	assert.Equal(t, StateOpen, b.State())

	clk.Advance(1 * time.Second)
	assert.Equal(t, StateHalfOpen, b.State())
	assert.True(t, b.Allow(), "first probe")
	assert.True(t, b.Allow(), "second probe")
	assert.False(t, b.Allow(), "only half_open_successes probes in flight")

	b.Success()
	assert.Equal(t, StateHalfOpen, b.State())
	assert.True(t, b.Allow(), "a finished probe frees a slot")
	b.Success()
	assert.Equal(t, StateClosed, b.State())
	assert.True(t, b.Allow())
	assert.Equal(t, []int{StateOpen, StateHalfOpen, StateClosed}, *states)
}

func TestHalfOpenProbeFailureReopens(t *testing.T) {
	b, clk, states := newTestBreaker(t)
	for i := 0; i < 4; i++ {
		b.Failure()
	}
	clk.Advance(30 * time.Second)
	require.True(t, b.Allow())
	b.Failure()
	assert.Equal(t, StateOpen, b.State())
	assert.False(t, b.Allow())
	clk.Advance(30 * time.Second)
	assert.Equal(t, StateHalfOpen, b.State())
	assert.Equal(t, []int{StateOpen, StateHalfOpen, StateOpen, StateHalfOpen}, *states)
}

func TestHalfOpenUnreportedProbesAreReleasedAfterOpenDuration(t *testing.T) {
	b, clk, _ := newTestBreaker(t)
	for i := 0; i < 4; i++ {
		b.Failure()
	}
	clk.Advance(30 * time.Second)
	require.True(t, b.Allow())
	require.True(t, b.Allow())
	require.False(t, b.Allow())
	// Probes never report (rows were released, worker died...).
	clk.Advance(30 * time.Second)
	assert.True(t, b.Allow(), "stale probes must not wedge the breaker")
}

func TestClosedAfterRecoveryStartsWithEmptyWindow(t *testing.T) {
	b, clk, _ := newTestBreaker(t)
	for i := 0; i < 4; i++ {
		b.Failure()
	}
	clk.Advance(30 * time.Second)
	b.Allow()
	b.Success()
	b.Allow()
	b.Success()
	require.Equal(t, StateClosed, b.State())
	b.Failure()
	b.Failure()
	b.Failure()
	assert.Equal(t, StateClosed, b.State(), "window was reset on close")
	b.Failure()
	assert.Equal(t, StateOpen, b.State())
}

func TestDefaultsWhenConfigIsZero(t *testing.T) {
	b := New(config.BreakerConfig{}, nil)
	assert.Equal(t, 20, b.windowSize)
	assert.Equal(t, 0.5, b.failureRatio)
	assert.Equal(t, 30*time.Second, b.openDuration)
	assert.Equal(t, 3, b.halfOpenProbe)
	assert.True(t, b.Allow())
}

func TestConcurrentUse(t *testing.T) {
	b, _, _ := newTestBreaker(t)
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				if b.Allow() {
					if (i+j)%3 == 0 {
						b.Failure()
					} else {
						b.Success()
					}
				}
				_ = b.State()
			}
		}(i)
	}
	wg.Wait()
	s := b.State()
	assert.Contains(t, []int{StateClosed, StateHalfOpen, StateOpen}, s)
}
