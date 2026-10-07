//go:build integration

package integration

import (
	"context"
	stderrors "errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// gatedSender wraps the real SMTP sender so ordering scenarios can hold a
// provider call open, fail it retryably, slow it down and observe how many
// calls to one recipient overlap (15-verification.md §2.4). Every rule is
// keyed by recipient address, which is unique per test user.
type gatedSender struct {
	inner port.EmailSender

	mu          sync.Mutex
	holds       map[string]*sendHold
	fails       map[string]int
	delays      map[string]time.Duration
	inflight    map[string]int
	maxInflight map[string]int
	starts      []sendStart
}

type sendStart struct {
	To         string
	DeliveryID uuid.UUID
	At         time.Time
}

// sendHold blocks the next call to an address until released.
type sendHold struct {
	started chan struct{}
	release chan struct{}
	used    atomic.Bool
}

func newGatedSender(inner port.EmailSender) *gatedSender {
	return &gatedSender{inner: inner, holds: map[string]*sendHold{}, fails: map[string]int{},
		delays: map[string]time.Duration{}, inflight: map[string]int{}, maxInflight: map[string]int{}}
}

func (g *gatedSender) Provider() entity.Provider { return g.inner.Provider() }

// holdNext makes the next Send to `to` signal started and wait for release.
func (g *gatedSender) holdNext(to string) (started <-chan struct{}, release func()) {
	h := &sendHold{started: make(chan struct{}), release: make(chan struct{})}
	g.mu.Lock()
	g.holds[to] = h
	g.mu.Unlock()
	var once sync.Once
	return h.started, func() { once.Do(func() { close(h.release) }) }
}

// failNext makes the next n calls to `to` fail with a retryable error.
func (g *gatedSender) failNext(to string, n int) {
	g.mu.Lock()
	g.fails[to] = n
	g.mu.Unlock()
}

func (g *gatedSender) delay(to string, d time.Duration) {
	g.mu.Lock()
	g.delays[to] = d
	g.mu.Unlock()
}

func (g *gatedSender) startsTo(to string) []sendStart {
	g.mu.Lock()
	defer g.mu.Unlock()
	var out []sendStart
	for _, s := range g.starts {
		if s.To == to {
			out = append(out, s)
		}
	}
	return out
}

func (g *gatedSender) maxConcurrent(to string) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.maxInflight[to]
}

var errProviderBusy = stderrors.New("421 4.3.2 service not available, try later")

func (g *gatedSender) Send(ctx context.Context, msg *entity.EmailMessage) (*entity.SendResult, error) {
	to := msg.To.Raw()
	g.mu.Lock()
	g.starts = append(g.starts, sendStart{To: to, DeliveryID: msg.DeliveryID, At: time.Now()})
	g.inflight[to]++
	if g.inflight[to] > g.maxInflight[to] {
		g.maxInflight[to] = g.inflight[to]
	}
	hold := g.holds[to]
	if hold != nil && hold.used.CompareAndSwap(false, true) {
		delete(g.holds, to)
	} else {
		hold = nil
	}
	fail := g.fails[to] > 0
	if fail {
		g.fails[to]--
	}
	delay := g.delays[to]
	g.mu.Unlock()
	defer func() {
		g.mu.Lock()
		g.inflight[to]--
		g.mu.Unlock()
	}()

	if hold != nil {
		close(hold.started)
		select {
		case <-hold.release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if fail {
		return nil, errProviderBusy
	}
	if delay > 0 {
		time.Sleep(delay)
	}
	return g.inner.Send(ctx, msg)
}
