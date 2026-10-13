package product

import (
	"testing"
	"time"
)

func TestBreakerOpensAfterConsecutiveFailures(t *testing.T) {
	b := newBreaker(BreakerConfig{FailureThreshold: 3, OpenTimeout: time.Minute})

	for i := 0; i < 3; i++ {
		if !b.allow() {
			t.Fatalf("call %d was rejected while the breaker should still be closed", i)
		}
		b.failure()
	}

	if b.allow() {
		t.Fatal("the breaker stayed closed past its failure threshold")
	}
	if !b.isOpen() {
		t.Fatal("isOpen disagrees with allow")
	}
}

// A single success has to reset the count, or unrelated failures spread over
// hours would eventually trip the breaker for no reason.
func TestBreakerResetsOnSuccess(t *testing.T) {
	b := newBreaker(BreakerConfig{FailureThreshold: 3, OpenTimeout: time.Minute})

	b.allow()
	b.failure()
	b.allow()
	b.failure()
	b.allow()
	b.success()

	b.allow()
	b.failure()
	if !b.allow() {
		t.Fatal("the breaker opened even though a success reset the count")
	}
}

func TestBreakerProbesAfterTheOpenTimeout(t *testing.T) {
	now := time.Unix(1780000000, 0)
	b := newBreaker(BreakerConfig{FailureThreshold: 1, HalfOpenMaxCalls: 1, OpenTimeout: 30 * time.Second})
	b.now = func() time.Time { return now }

	b.allow()
	b.failure()
	if b.allow() {
		t.Fatal("the breaker allowed a call while open")
	}

	now = now.Add(31 * time.Second)
	if !b.allow() {
		t.Fatal("the breaker never moved to half-open")
	}
	// Half-open admits one probe at a time.
	if b.allow() {
		t.Fatal("half-open admitted a second concurrent probe")
	}

	b.success()
	if !b.allow() {
		t.Fatal("a successful probe did not close the breaker")
	}
}

// A probe abandoned by its caller must free its slot, or the breaker would sit
// half-open rejecting every call forever.
func TestBreakerReleaseFreesAHalfOpenProbeSlot(t *testing.T) {
	now := time.Unix(1780000000, 0)
	b := newBreaker(BreakerConfig{FailureThreshold: 1, HalfOpenMaxCalls: 1, OpenTimeout: 30 * time.Second})
	b.now = func() time.Time { return now }

	b.allow()
	b.failure()
	now = now.Add(31 * time.Second)

	if !b.allow() {
		t.Fatal("the breaker never moved to half-open")
	}
	b.release()
	if !b.allow() {
		t.Fatal("a released probe slot was not reusable")
	}
}

// A failed probe must reopen immediately rather than admitting another wave.
func TestBreakerReopensOnAFailedProbe(t *testing.T) {
	now := time.Unix(1780000000, 0)
	b := newBreaker(BreakerConfig{FailureThreshold: 1, HalfOpenMaxCalls: 1, OpenTimeout: 30 * time.Second})
	b.now = func() time.Time { return now }

	b.allow()
	b.failure()
	now = now.Add(31 * time.Second)

	b.allow()
	b.failure()
	if b.allow() {
		t.Fatal("the breaker stayed half-open after a failed probe")
	}
}
