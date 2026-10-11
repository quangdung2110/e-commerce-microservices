package reaper

import (
	"context"
	"testing"
	"time"

	dto "github.com/prometheus/client_model/go"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

type deliveries struct {
	port.DeliveryRepository
}

func (deliveries) ReapExpiredLeases(context.Context, time.Time) (map[entity.Lane]int64, error) {
	return nil, nil
}
func (deliveries) PendingStats(context.Context, time.Time) ([]entity.PendingStat, error) {
	return nil, nil
}

type ordering struct {
	port.OrderingRepository
	reaped int
	ages   []entity.OrderingLeaseAge
}

func (o *ordering) ReapExpiredLeases(context.Context, time.Time) (int64, error) {
	o.reaped++
	return 1, nil
}

func (o *ordering) ExpiredLeaseAges(context.Context, time.Time) ([]entity.OrderingLeaseAge, error) {
	return o.ages, nil
}

func gauge(t *testing.T, m *telemetry.Metrics, source string) float64 {
	t.Helper()
	var out dto.Metric
	if err := m.OrderingLeaseOldestAge.WithLabelValues(source).Write(&out); err != nil {
		t.Fatal(err)
	}
	return out.Gauge.GetValue()
}

func TestReaperReclaimsOrderingLeasesAndExportsAge(t *testing.T) {
	m := telemetry.New()
	ord := &ordering{ages: []entity.OrderingLeaseAge{{Source: entity.SourceOrder, Age: 150 * time.Second}}}
	w := New(deliveries{}, ord, nil, time.Second, time.Second, clock.NewFake(time.Now()), m, nil)

	w.ReapOnce(context.Background())
	if ord.reaped != 1 {
		t.Fatalf("ordering leases reaped %d times", ord.reaped)
	}
	w.ExportStats(context.Background())
	if got := gauge(t, m, entity.SourceOrder); got != 150 {
		t.Fatalf("oldest age = %v", got)
	}
	ord.ages = nil
	w.ExportStats(context.Background())
	if got := gauge(t, m, entity.SourceOrder); got != 0 {
		t.Fatalf("gauge must drop to 0 once reclaimed, got %v", got)
	}
}

func TestReaperWithoutOrderingRepo(t *testing.T) {
	w := New(deliveries{}, nil, nil, time.Second, time.Second, clock.NewFake(time.Now()), telemetry.New(), nil)
	w.ReapOnce(context.Background())
	w.ExportStats(context.Background())
}

type laneDeliveries struct {
	deliveries
	reaped map[entity.Lane]int64
}

func (d laneDeliveries) ReapExpiredLeases(context.Context, time.Time) (map[entity.Lane]int64, error) {
	return d.reaped, nil
}

// delivery_lease_expired_total carries the lane of the reaped row
// (14-observability.md §2.2), so R-07 can tell which lane is losing leases.
func TestReaperCountsLeaseExpiryPerLane(t *testing.T) {
	m := telemetry.New()
	d := laneDeliveries{reaped: map[entity.Lane]int64{entity.LaneCritical: 2, entity.LaneBulk: 5}}
	w := New(d, nil, nil, time.Second, time.Second, clock.NewFake(time.Now()), m, nil)

	if got := w.ReapOnce(context.Background()); got != 7 {
		t.Fatalf("total reaped = %d", got)
	}
	for lane, want := range map[string]float64{"critical": 2, "bulk": 5, "transactional": 0} {
		var out dto.Metric
		if err := m.DeliveryLeaseExpired.WithLabelValues(lane).Write(&out); err != nil {
			t.Fatal(err)
		}
		if got := out.Counter.GetValue(); got != want {
			t.Fatalf("lane %s: %v, want %v", lane, got, want)
		}
	}
}
