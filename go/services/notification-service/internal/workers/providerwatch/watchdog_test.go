package providerwatch

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

type fakeLanes struct {
	state entity.LaneState
	sets  int
}

func (f *fakeLanes) LaneState(context.Context, entity.Lane) (entity.LaneState, error) {
	return f.state, nil
}
func (f *fakeLanes) SetLaneState(_ context.Context, s entity.LaneState) error {
	f.state = s
	f.sets++
	return nil
}
func (f *fakeLanes) ReplayMode(context.Context) (entity.ReplayMode, error) {
	return entity.ReplayMode{}, nil
}
func (f *fakeLanes) SetReplayMode(context.Context, entity.ReplayMode, string) error { return nil }

type fakeAudit struct {
	port.OpsRepository
	entries []*entity.AuditEntry
}

func (f *fakeAudit) InsertAudit(_ context.Context, e *entity.AuditEntry) error {
	f.entries = append(f.entries, e)
	return nil
}

type fakeHealth struct {
	err   error
	calls int
}

func (f *fakeHealth) HealthCheck(context.Context) error { f.calls++; return f.err }

type rig struct {
	clk    *clock.Fake
	br     *breaker.Breaker
	lanes  *fakeLanes
	audit  *fakeAudit
	health *fakeHealth
	wd     *Watchdog
}

func newRig(t *testing.T) *rig {
	t.Helper()
	clk := clock.NewFake(time.Date(2026, 10, 10, 0, 0, 0, 0, time.UTC))
	br := breaker.New(config.BreakerConfig{WindowSize: 4, FailureRatio: 0.5, OpenDuration: 30 * time.Second, HalfOpenSuccesses: 2}, clk)
	r := &rig{clk: clk, br: br, lanes: &fakeLanes{state: entity.DefaultLaneState(entity.LaneBulk, clk.Now())},
		audit: &fakeAudit{}, health: &fakeHealth{err: errors.New("provider still down")}}
	r.wd = New(Config{ProbeInterval: 10 * time.Second, ProbeIdle: 15 * time.Second, AutoPauseAfter: time.Hour},
		Deps{Breaker: br, Health: r.health, Lanes: r.lanes, Audit: r.audit, Provider: entity.ProviderSES, Clock: clk, Metrics: telemetry.New()})
	return r
}

func (r *rig) trip() {
	for i := 0; i < 4; i++ {
		r.br.Failure()
	}
}

func TestHealthProbeClosesAQuietHalfOpenBreaker(t *testing.T) {
	r := newRig(t)
	r.health.err = nil
	r.trip()
	r.wd.Tick(context.Background())
	assert.Zero(t, r.health.calls, "open breaker: no probe")

	r.clk.Advance(30 * time.Second)
	require.Equal(t, breaker.StateHalfOpen, r.br.State())
	r.wd.Tick(context.Background())
	assert.Equal(t, 1, r.health.calls)
	r.clk.Advance(15 * time.Second)
	r.wd.Tick(context.Background())
	assert.Equal(t, 2, r.health.calls)
	assert.Equal(t, breaker.StateClosed, r.br.State(), "half_open_successes health probes close the circuit")
}

func TestHealthProbeFailureReopens(t *testing.T) {
	r := newRig(t)
	r.trip()
	r.clk.Advance(30 * time.Second)
	r.wd.Tick(context.Background())
	assert.Equal(t, 1, r.health.calls)
	assert.Equal(t, breaker.StateOpen, r.br.State())
}

func TestHealthProbeStandsDownForRealProbes(t *testing.T) {
	r := newRig(t)
	r.trip()
	r.clk.Advance(30 * time.Second)
	require.True(t, r.br.Allow(), "a real delivery of a lower lane probes first")
	r.wd.Tick(context.Background())
	assert.Zero(t, r.health.calls)
}

func TestBulkEmailIsPausedOnceAfterTheBreakerStaysUnhealthy(t *testing.T) {
	r := newRig(t)
	r.trip()
	r.clk.Advance(59 * time.Minute)
	r.wd.Tick(context.Background())
	assert.Zero(t, r.lanes.sets, "not yet an hour")

	r.clk.Advance(2 * time.Minute)
	r.wd.Tick(context.Background())
	require.Equal(t, 1, r.lanes.sets)
	assert.True(t, r.lanes.state.IsPaused(entity.ChannelEmail))
	assert.False(t, r.lanes.state.IsPaused(entity.ChannelInApp), "in-app does not depend on the provider")
	require.NotNil(t, r.lanes.state.UpdatedBy)
	assert.Equal(t, Principal, *r.lanes.state.UpdatedBy)
	require.Len(t, r.audit.entries, 1)
	assert.Equal(t, Operation, r.audit.entries[0].Operation)
	assert.Equal(t, Principal, r.audit.entries[0].Principal)

	r.wd.Tick(context.Background())
	assert.Equal(t, 1, r.lanes.sets, "one pause per outage")
}

func TestAutoPauseRespectsAnOperatorResumeUntilTheCircuitCloses(t *testing.T) {
	r := newRig(t)
	r.trip()
	r.clk.Advance(61 * time.Minute)
	r.wd.Tick(context.Background())
	require.Equal(t, 1, r.lanes.sets)

	r.lanes.state.Email = entity.LaneActive // operator resumed while still down
	r.wd.Tick(context.Background())
	assert.Equal(t, 1, r.lanes.sets, "same outage: the watchdog does not override the operator")

	// Recovery then a new outage is a new streak and may pause again.
	r.health.err = nil
	r.clk.Advance(time.Minute)
	for i := 0; i < 2 && r.br.State() != breaker.StateClosed; i++ {
		r.wd.Tick(context.Background())
		r.clk.Advance(20 * time.Second)
	}
	require.Equal(t, breaker.StateClosed, r.br.State())
	r.wd.Tick(context.Background())
	r.trip()
	r.clk.Advance(61 * time.Minute)
	r.wd.Tick(context.Background())
	assert.Equal(t, 2, r.lanes.sets)
}

func TestAlreadyPausedByAnOperatorIsLeftAlone(t *testing.T) {
	r := newRig(t)
	r.lanes.state.Email = entity.LanePaused
	by := "notification-ops"
	r.lanes.state.UpdatedBy = &by
	r.trip()
	r.clk.Advance(61 * time.Minute)
	r.wd.Tick(context.Background())
	assert.Zero(t, r.lanes.sets)
	assert.Empty(t, r.audit.entries)
	assert.Equal(t, "notification-ops", *r.lanes.state.UpdatedBy)
}

func TestAutoPauseCanBeDisabled(t *testing.T) {
	r := newRig(t)
	r.wd = New(Config{ProbeInterval: time.Second, AutoPauseAfter: 0},
		Deps{Breaker: r.br, Lanes: r.lanes, Clock: r.clk})
	r.trip()
	r.clk.Advance(48 * time.Hour)
	r.wd.Tick(context.Background())
	assert.Zero(t, r.lanes.sets)
}
