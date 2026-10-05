package deliverworker

import (
	"context"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

type fakeDeliveries struct {
	port.DeliveryRepository
	mu       sync.Mutex
	queue    []entity.Delivery
	claims   int
	claimed  []string
	extends  [][]uuid.UUID
	extendBy []string
}

func (f *fakeDeliveries) Claim(_ context.Context, lane entity.Lane, ch entity.Channel, workerID string, batch int, lease time.Duration) ([]entity.Delivery, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.claims++
	var out []entity.Delivery
	var rest []entity.Delivery
	for _, d := range f.queue {
		if d.Lane == lane && d.Channel == ch && len(out) < batch {
			d.Attempt++
			out = append(out, d)
			f.claimed = append(f.claimed, workerID)
			continue
		}
		rest = append(rest, d)
	}
	f.queue = rest
	return out, nil
}

func (f *fakeDeliveries) ExtendLease(_ context.Context, ids []uuid.UUID, workerID string, _ time.Duration) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.extends = append(f.extends, ids)
	f.extendBy = append(f.extendBy, workerID)
	return nil
}

type fakeStore struct {
	port.Store
	deliv *fakeDeliveries
}

func (s *fakeStore) Deliveries() port.DeliveryRepository { return s.deliv }

type fakeProcessor struct {
	mu        sync.Mutex
	delay     time.Duration
	processed []uuid.UUID
	workers   map[string]bool
	inflight  int32
	maxInFlt  int32
}

func (p *fakeProcessor) Process(ctx context.Context, d entity.Delivery, workerID string) error {
	cur := atomic.AddInt32(&p.inflight, 1)
	for {
		old := atomic.LoadInt32(&p.maxInFlt)
		if cur <= old || atomic.CompareAndSwapInt32(&p.maxInFlt, old, cur) {
			break
		}
	}
	defer atomic.AddInt32(&p.inflight, -1)
	if p.delay > 0 {
		select {
		case <-time.After(p.delay):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.processed = append(p.processed, d.ID)
	if p.workers == nil {
		p.workers = map[string]bool{}
	}
	p.workers[workerID] = true
	return nil
}

type fakeBreaker struct{ state int }

func (f *fakeBreaker) Allow() bool { return true }
func (f *fakeBreaker) Success()    {}
func (f *fakeBreaker) Failure()    {}
func (f *fakeBreaker) State() int  { return f.state }

func rows(lane entity.Lane, ch entity.Channel, n int) []entity.Delivery {
	out := make([]entity.Delivery, n)
	for i := range out {
		out[i] = entity.Delivery{ID: uuid.New(), Lane: lane, Channel: ch}
	}
	return out
}

func testConfig() Config {
	return Config{
		Lane: config.LaneConfig{EmailWorkers: 2, InAppWorkers: 1, BatchSize: 3},
		Deliver: config.DeliverConfig{
			Lease:             200 * time.Millisecond,
			HeartbeatInterval: 20 * time.Millisecond,
			IdleMinSleep:      5 * time.Millisecond,
			IdleMaxSleep:      20 * time.Millisecond,
		},
		InstanceID: "host",
	}
}

func TestPoolProcessesBatchesConcurrentlyWithHeartbeat(t *testing.T) {
	deliv := &fakeDeliveries{}
	deliv.queue = append(rows(entity.LaneTransactional, entity.ChannelEmail, 6), rows(entity.LaneTransactional, entity.ChannelInApp, 2)...)
	proc := &fakeProcessor{delay: 60 * time.Millisecond}
	pool := NewPool(entity.LaneTransactional, testConfig(), Deps{
		Store:     &fakeStore{deliv: deliv},
		Processor: proc,
		Breaker:   &fakeBreaker{},
	})

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		pool.Run(ctx)
		close(done)
	}()

	require.Eventually(t, func() bool {
		proc.mu.Lock()
		defer proc.mu.Unlock()
		return len(proc.processed) == 8
	}, 2*time.Second, 5*time.Millisecond)
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("pool did not stop")
	}

	assert.GreaterOrEqual(t, atomic.LoadInt32(&proc.maxInFlt), int32(3), "rows of a batch are processed concurrently")
	for id := range proc.workers {
		assert.True(t, strings.HasPrefix(id, "transactional-email-host-") || strings.HasPrefix(id, "transactional-in_app-host-"), id)
	}
	deliv.mu.Lock()
	defer deliv.mu.Unlock()
	require.NotEmpty(t, deliv.extends, "heartbeat extended leases while rows were in flight")
	for i, ids := range deliv.extends {
		assert.NotEmpty(t, ids)
		assert.Contains(t, deliv.claimed, deliv.extendBy[i])
	}
}

func TestPoolFinishesInFlightRowsOnStop(t *testing.T) {
	deliv := &fakeDeliveries{queue: rows(entity.LaneBulk, entity.ChannelEmail, 1)}
	proc := &fakeProcessor{delay: 80 * time.Millisecond}
	cfg := testConfig()
	cfg.Lane.EmailWorkers, cfg.Lane.InAppWorkers = 1, 0
	pool := NewPool(entity.LaneBulk, cfg, Deps{Store: &fakeStore{deliv: deliv}, Processor: proc})

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { pool.Run(ctx); close(done) }()
	require.Eventually(t, func() bool { return atomic.LoadInt32(&proc.inflight) == 1 }, time.Second, time.Millisecond)
	cancel()
	<-done
	proc.mu.Lock()
	defer proc.mu.Unlock()
	assert.Len(t, proc.processed, 1, "the row in flight was completed after cancel")
}

func TestCriticalEmailSkipsClaimWhileHalfOpen(t *testing.T) {
	deliv := &fakeDeliveries{queue: rows(entity.LaneCritical, entity.ChannelEmail, 1)}
	proc := &fakeProcessor{}
	br := &fakeBreaker{state: breaker.StateHalfOpen}
	cfg := testConfig()
	cfg.Lane.EmailWorkers, cfg.Lane.InAppWorkers = 1, 0
	cfg.OtherLanesInProcess = true
	pool := NewPool(entity.LaneCritical, cfg, Deps{Store: &fakeStore{deliv: deliv}, Processor: proc, Breaker: br})

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { pool.Run(ctx); close(done) }()
	time.Sleep(80 * time.Millisecond)
	deliv.mu.Lock()
	assert.Equal(t, 0, deliv.claims, "critical email does not probe when a lower lane can")
	deliv.mu.Unlock()

	// Breaker closes → claiming resumes.
	br.state = breaker.StateClosed
	require.Eventually(t, func() bool {
		proc.mu.Lock()
		defer proc.mu.Unlock()
		return len(proc.processed) == 1
	}, time.Second, 5*time.Millisecond)
	cancel()
	<-done
}

func TestCriticalProbesWhenAlone(t *testing.T) {
	deliv := &fakeDeliveries{queue: rows(entity.LaneCritical, entity.ChannelEmail, 1)}
	proc := &fakeProcessor{}
	cfg := testConfig()
	cfg.Lane.EmailWorkers, cfg.Lane.InAppWorkers = 1, 0
	cfg.OtherLanesInProcess = false
	pool := NewPool(entity.LaneCritical, cfg, Deps{Store: &fakeStore{deliv: deliv}, Processor: proc, Breaker: &fakeBreaker{state: breaker.StateHalfOpen}})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { pool.Run(ctx); close(done) }()
	require.Eventually(t, func() bool {
		proc.mu.Lock()
		defer proc.mu.Unlock()
		return len(proc.processed) == 1
	}, time.Second, 5*time.Millisecond)
	cancel()
	<-done
}

func TestNextSleepDoublesToMax(t *testing.T) {
	s := 50 * time.Millisecond
	seq := []time.Duration{}
	for i := 0; i < 6; i++ {
		s = nextSleep(s, time.Second)
		seq = append(seq, s)
	}
	assert.Equal(t, []time.Duration{100 * time.Millisecond, 200 * time.Millisecond, 400 * time.Millisecond, 800 * time.Millisecond, time.Second, time.Second}, seq)
}
