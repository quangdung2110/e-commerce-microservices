// Package providerwatch is the watchdog next to the provider circuit breaker
// (06-backpressure.md §5, decisions.md D-23). It does two things the breaker
// cannot do for itself:
//
//   - Health probe. A half-open breaker needs real calls to close. When no
//     delivery of a lower lane happens to be waiting, the watchdog asks the
//     provider directly (SES GetAccount, SMTP NOOP) so a quiet system still
//     recovers.
//   - Bulk auto-pause. When the breaker has not been closed for
//     deliver.breaker_auto_pause_after (1 h), the watchdog pauses the bulk lane
//     for email so a long outage does not leave bulk workers cycling through
//     the open circuit. Resuming is a manual ops decision (PUT lanes/bulk): the
//     watchdog never resumes, and never pauses again within the same outage
//     after an operator resumed.
package providerwatch

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/breaker"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Principal is recorded as updated_by and as the audit principal of the
// automatic pause.
const Principal = "system:breaker-watchdog"

// Operation names the automatic pause in ops_audit and ops_calls_total.
const Operation = "autoPauseBulkEmail"

// Breaker is the slice of the provider breaker the watchdog uses; the concrete
// *breaker.Breaker satisfies it.
type Breaker interface {
	State() int
	Success()
	Failure()
	UnhealthySince() (time.Time, bool)
	AllowIdleProbe(minIdle time.Duration) bool
}

var _ Breaker = (*breaker.Breaker)(nil)

// Config holds the watchdog knobs (deliver.*).
type Config struct {
	// ProbeInterval is how often the watchdog looks at the breaker; 0 disables
	// the whole watchdog.
	ProbeInterval time.Duration
	// ProbeIdle is how long a half-open breaker must have gone without any
	// probe before the health check is used.
	ProbeIdle time.Duration
	// ProbeTimeout bounds one health check.
	ProbeTimeout time.Duration
	// AutoPauseAfter is how long the breaker may stay unhealthy before bulk
	// email is paused; 0 disables the auto-pause.
	AutoPauseAfter time.Duration
}

// Deps are the watchdog's collaborators. Health may be nil (adapter without a
// health check): the probe is then skipped, the auto-pause still works.
type Deps struct {
	Breaker  Breaker
	Health   port.HealthChecker
	Lanes    port.LaneControl
	Audit    port.OpsRepository
	Provider entity.Provider
	Clock    clock.Clock
	Metrics  *telemetry.Metrics // nilable
	Logger   *zap.Logger        // nilable
}

// Watchdog runs the two duties on one ticker.
type Watchdog struct {
	cfg  Config
	deps Deps
	log  *zap.Logger

	// pausedSince is the unhealthy streak the automatic pause already acted on,
	// so an operator's resume is respected until the circuit closes.
	pausedSince time.Time
}

// New builds the watchdog.
func New(cfg Config, deps Deps) *Watchdog {
	if deps.Logger == nil {
		deps.Logger = zap.NewNop()
	}
	if deps.Clock == nil {
		deps.Clock = clock.System{}
	}
	if cfg.ProbeTimeout <= 0 {
		cfg.ProbeTimeout = 5 * time.Second
	}
	return &Watchdog{cfg: cfg, deps: deps, log: deps.Logger.Named("provider-watchdog")}
}

// Run ticks until ctx is cancelled. It returns immediately when disabled.
func (w *Watchdog) Run(ctx context.Context) {
	if w.cfg.ProbeInterval <= 0 {
		w.log.Info("provider watchdog disabled (deliver.health_probe_interval = 0)")
		return
	}
	t := time.NewTicker(w.cfg.ProbeInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			w.Tick(ctx)
		}
	}
}

// Tick performs one pass: probe, then auto-pause. Exported for tests.
func (w *Watchdog) Tick(ctx context.Context) {
	w.probe(ctx)
	w.autoPause(ctx)
}

func (w *Watchdog) probe(ctx context.Context) {
	if w.deps.Health == nil || w.deps.Breaker.State() != breaker.StateHalfOpen {
		return
	}
	if !w.deps.Breaker.AllowIdleProbe(w.cfg.ProbeIdle) {
		return
	}
	pctx, cancel := context.WithTimeout(ctx, w.cfg.ProbeTimeout)
	defer cancel()
	start := w.deps.Clock.Now()
	err := w.deps.Health.HealthCheck(pctx)
	status := "ok"
	if err != nil {
		status = "error"
		w.deps.Breaker.Failure()
		w.log.Warn("provider health probe failed", zap.String("provider", string(w.deps.Provider)), zap.Error(err))
	} else {
		w.deps.Breaker.Success()
		w.log.Info("provider health probe succeeded", zap.String("provider", string(w.deps.Provider)))
	}
	if w.deps.Metrics != nil {
		w.deps.Metrics.DependencyDuration.WithLabelValues(string(w.deps.Provider)+"_health", "none", status).
			Observe(w.deps.Clock.Now().Sub(start).Seconds())
	}
}

func (w *Watchdog) autoPause(ctx context.Context) {
	if w.cfg.AutoPauseAfter <= 0 || w.deps.Lanes == nil {
		return
	}
	since, unhealthy := w.deps.Breaker.UnhealthySince()
	if !unhealthy {
		w.pausedSince = time.Time{}
		return
	}
	now := w.deps.Clock.Now()
	if now.Sub(since) < w.cfg.AutoPauseAfter || w.pausedSince.Equal(since) {
		return
	}

	state, err := w.deps.Lanes.LaneState(ctx, entity.LaneBulk)
	if err != nil {
		w.log.Warn("cannot read bulk lane state for the auto-pause", zap.Error(err))
		return
	}
	if state.IsPaused(entity.ChannelEmail) {
		// Already paused (by an operator or another replica): nothing to do,
		// and do not try again within this outage.
		w.pausedSince = since
		return
	}
	reason := "provider circuit breaker unhealthy since " + since.UTC().Format(time.RFC3339) +
		" (deliver.breaker_auto_pause_after " + w.cfg.AutoPauseAfter.String() + ")"
	principal := Principal
	state.Lane = entity.LaneBulk
	state.Email = entity.LanePaused
	state.UpdatedAt = now
	state.UpdatedBy = &principal
	state.Reason = &reason
	if err := w.deps.Lanes.SetLaneState(ctx, state); err != nil {
		w.log.Error("auto-pause of bulk email failed; will retry", zap.Error(err))
		w.record("error")
		return
	}
	w.pausedSince = since
	w.record("ok")
	w.audit(ctx, now, since, reason)
	w.log.Warn("bulk email paused automatically: provider breaker unhealthy too long; resume manually after recovery",
		zap.Time("unhealthy_since", since), zap.Duration("after", w.cfg.AutoPauseAfter))
}

func (w *Watchdog) audit(ctx context.Context, now, since time.Time, reason string) {
	if w.deps.Audit == nil {
		return
	}
	params, _ := json.Marshal(map[string]any{
		"lane": entity.LaneBulk, "channel": entity.ChannelEmail, "state": entity.LanePaused,
		"unhealthy_since": since.UTC(), "reason": reason,
	})
	err := w.deps.Audit.InsertAudit(ctx, &entity.AuditEntry{
		ID: uuid.New(), Principal: Principal, Operation: Operation, Params: params, Result: "ok", CreatedAt: now,
	})
	if err != nil {
		w.log.Error("auto-pause audit entry not persisted", zap.Error(err))
	}
}

func (w *Watchdog) record(result string) {
	if w.deps.Metrics != nil {
		w.deps.Metrics.OpsCallsTotal.WithLabelValues(Operation, result).Inc()
	}
}
