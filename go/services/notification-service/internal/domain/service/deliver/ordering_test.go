package deliver

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// ---- fake ordering repository ---------------------------------------------

type fakeOrdering struct {
	port.OrderingRepository
	mu       sync.Mutex
	busy     bool // AcquireLease refuses (another delivery holds the key)
	holds    bool // HoldsLease answer inside the in_app transaction
	extendOK bool
	ledger   []entity.SubjectSequence
	acquired []string
	released []string
}

func (f *fakeOrdering) AcquireLease(_ context.Context, key string, _ uuid.UUID, _ string, _ time.Duration) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.busy {
		return false, nil
	}
	f.acquired = append(f.acquired, key)
	return true, nil
}

func (f *fakeOrdering) ExtendLease(context.Context, string, uuid.UUID, string, time.Duration) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.extendOK, nil
}

func (f *fakeOrdering) HoldsLease(context.Context, string, uuid.UUID, string) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.holds, nil
}

func (f *fakeOrdering) ReleaseLease(_ context.Context, key string, _ uuid.UUID, _ string) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.released = append(f.released, key)
	return true, nil
}

func (f *fakeOrdering) SubjectSequences(_ context.Context, source string, keys []pii.KeyedHash) ([]entity.SubjectSequence, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []entity.SubjectSequence
	for _, row := range f.ledger {
		for _, k := range keys {
			if row.Source == source && row.KeyVersion == k.Version && row.KeyHash == k.Hash {
				out = append(out, row)
			}
		}
	}
	return out, nil
}

// ---- helpers ----------------------------------------------------------------

func counterValue(t *testing.T, c prometheus.Counter) float64 {
	t.Helper()
	var m dto.Metric
	require.NoError(t, c.Write(&m))
	return m.Counter.GetValue()
}

// orderHarness has the ordering config and a two-version keyring.
func orderHarness(t *testing.T) (*harness, *pii.Keyring) {
	t.Helper()
	h := newHarness(t)
	k, err := pii.NewKeyring(2, []byte("deliver-test-key-v2-deliver-test-v2"), map[int16][]byte{1: []byte("deliver-test-key-v1-deliver-test-v1")})
	require.NoError(t, err)
	h.svc.deps.Keyring = k
	h.svc.cfg.Deliver.OrderingLease = 45 * time.Second
	h.svc.cfg.Deliver.OrderingHeartbeatInterval = 10 * time.Second
	h.svc.cfg.Deliver.OrderingBusyRetry = 2 * time.Second
	h.svc.cfg.Email.ConnectTimeout = 2 * time.Second
	h.svc.cfg.Email.SendTimeout = 10 * time.Second
	return h, k
}

const orderSubject = "orders/1000234"

func orderDelivery(h *harness, k *pii.Keyring, ch entity.Channel, seq int64) entity.Delivery {
	d := h.delivery(entity.LaneTransactional, ch, "ORDER_CONFIRMED")
	key := k.OrderingKey(entity.SourceOrder, orderSubject)
	subject := orderSubject
	d.Source, d.SourceSequence, d.OrderingKey, d.Subject = entity.SourceOrder, &seq, &key, &subject
	return d
}

func (h *harness) setHighest(k *pii.Keyring, version int, seq int64) {
	hash := k.SubjectHashes(entity.SourceOrder, orderSubject)[version]
	h.store.ord.ledger = append(h.store.ord.ledger, entity.SubjectSequence{
		Source: entity.SourceOrder, KeyVersion: hash.Version, KeyHash: hash.Hash, HighestSequence: seq})
}

// ---- tests --------------------------------------------------------------------

func TestOrderingLeaseHeldAroundTheProviderCall(t *testing.T) {
	h, k := orderHarness(t)
	h.setHighest(k, 0, 7)
	d := orderDelivery(h, k, entity.ChannelEmail, 7)
	h.sender.onSend = func() {
		assert.Len(t, h.store.ord.acquired, 1, "lease acquired before the provider call")
		assert.Empty(t, h.store.ord.released, "lease still held during the provider call")
	}
	h.process(t, d)
	assert.Len(t, h.sender.sent, 1)
	assert.Equal(t, "sent", h.store.deliv.last().Op)
	assert.False(t, h.store.deliv.last().PossDup)
	assert.Equal(t, []string{*d.OrderingKey}, h.store.ord.released, "released after the terminal write")
}

func TestOrderingLeaseBusyReleasesWithoutAttempt(t *testing.T) {
	h, k := orderHarness(t)
	h.store.ord.busy = true
	h.process(t, orderDelivery(h, k, entity.ChannelEmail, 8))
	assert.Empty(t, h.sender.sent, "no provider call while another delivery of the order holds the lease")
	c := h.store.deliv.last()
	assert.Equal(t, "release", c.Op)
	assert.Equal(t, entity.ReasonOrderingBusy, c.Reason)
	assert.Equal(t, now.Add(2*time.Second), c.Next, "busy retry, not an immediate re-claim")
	assert.Equal(t, 1.0, counterValue(t, h.metrics.OrderingLeaseContention.WithLabelValues(entity.SourceOrder)))
	assert.Empty(t, h.store.ord.released, "nothing to release when the lease was never acquired")
}

// IT-ORD-02 at unit level: sequence 7 retried after 8 was ingested.
func TestOrderingLowerSequenceSupersededUnderLease(t *testing.T) {
	h, k := orderHarness(t)
	h.setHighest(k, 0, 8)
	h.process(t, orderDelivery(h, k, entity.ChannelEmail, 7))
	assert.Empty(t, h.sender.sent)
	c := h.store.deliv.last()
	assert.Equal(t, "final", c.Op)
	assert.Equal(t, entity.StatusSkipped, c.Status)
	assert.Equal(t, entity.ReasonSuperseded, c.Reason)
	assert.Len(t, h.store.ord.released, 1, "lease released after the skip")
}

// Dual-read: a highest sequence stored under the previous key version still
// supersedes (key rotation in progress).
func TestOrderingSupersededAcrossKeyVersions(t *testing.T) {
	h, k := orderHarness(t)
	h.setHighest(k, 1, 9)
	h.process(t, orderDelivery(h, k, entity.ChannelInApp, 8))
	c := h.store.deliv.last()
	assert.Equal(t, entity.StatusSkipped, c.Status)
	assert.Equal(t, entity.ReasonSuperseded, c.Reason)
}

func TestOrderingLeaseLostBeforeSendDoesNotCallProvider(t *testing.T) {
	h, k := orderHarness(t)
	h.svc.cfg.Deliver.OrderingLease = 10 * time.Second // shorter than the 12 s send budget
	h.process(t, orderDelivery(h, k, entity.ChannelEmail, 1))
	assert.Empty(t, h.sender.sent, "a provider call the lease cannot cover must not start")
	c := h.store.deliv.last()
	assert.Equal(t, "release", c.Op)
	assert.Equal(t, entity.ReasonOrderingLost, c.Reason)
	assert.Equal(t, 1.0, counterValue(t, h.metrics.OrderingLeaseLost.WithLabelValues(entity.SourceOrder, "before_send")))
}

// IT-ORD-06 at unit level: the lease ran out while the provider call was in
// flight → the send is recorded but flagged as an uncertain outcome.
func TestOrderingLeaseLostDuringSendMarksPossiblyDuplicate(t *testing.T) {
	h, k := orderHarness(t)
	h.sender.onSend = func() { h.clk.Advance(time.Minute) }
	h.process(t, orderDelivery(h, k, entity.ChannelEmail, 1))
	require.Len(t, h.sender.sent, 1)
	c := h.store.deliv.last()
	assert.Equal(t, "sent", c.Op)
	assert.True(t, c.PossDup, "ownership lost during the call → possibly_duplicate")
	assert.Equal(t, 1.0, counterValue(t, h.metrics.OrderingLeaseLost.WithLabelValues(entity.SourceOrder, "during_send")))
}

func TestOrderingInAppCommitsOnlyWhileOwningTheLease(t *testing.T) {
	h, k := orderHarness(t)
	h.process(t, orderDelivery(h, k, entity.ChannelInApp, 1))
	assert.Equal(t, "delivered_in_app", h.store.deliv.last().Op)
	assert.Len(t, h.store.inbox.items, 1)

	h2, k2 := orderHarness(t)
	h2.store.ord.holds = false
	h2.process(t, orderDelivery(h2, k2, entity.ChannelInApp, 1))
	assert.Empty(t, h2.store.inbox.items, "the in_app transaction rolls back without the lease")
	c := h2.store.deliv.last()
	assert.Equal(t, "release", c.Op)
	assert.Equal(t, entity.ReasonOrderingLost, c.Reason)
}

func TestNonOrderRowsNeverTouchTheLease(t *testing.T) {
	h, _ := orderHarness(t)
	h.process(t, h.delivery(entity.LaneTransactional, entity.ChannelEmail, "ORDER_CONFIRMED"))
	assert.Len(t, h.sender.sent, 1)
	assert.Empty(t, h.store.ord.acquired)
}
