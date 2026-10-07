// Package workers runs the scheduled work of User Service: the GDPR retention
// purge and the daily projection reconciliation.
//
// Every run is singleton across replicas through a Redis lock, bounded by a
// timeout and reported with a duration and an outcome, as required by
// docs/user/operations.md §5.
package workers

import (
	"context"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/domain/port"
)

// Job is one scheduled unit of work.
type Job interface {
	// Name identifies the job in logs and in its distributed lock.
	Name() string
	// Interval is how often the job runs.
	Interval() time.Duration
	// Timeout bounds a single run.
	Timeout() time.Duration
	// Run performs one pass and returns a short outcome for the log.
	Run(ctx context.Context) ([]zap.Field, error)
}

// DelayedStart is implemented by a job that must not run right after a deploy.
// The reconciliation sweep uses it to wait for its off-peak hour instead of
// starting a full scan the moment a pod comes up.
type DelayedStart interface {
	InitialDelay(now time.Time) time.Duration
}

// Runner schedules the registered jobs until the context is cancelled.
type Runner struct {
	jobs    []Job
	locker  port.Locker
	lockTTL time.Duration
	logger  *zap.Logger
	wg      sync.WaitGroup
}

// NewRunner wires the scheduler.
func NewRunner(locker port.Locker, lockTTL time.Duration, logger *zap.Logger) *Runner {
	return &Runner{locker: locker, lockTTL: lockTTL, logger: logger}
}

// Register adds a job. A job with a non-positive interval is ignored, which is
// how a worker is disabled by configuration.
func (r *Runner) Register(job Job) {
	if job == nil || job.Interval() <= 0 {
		return
	}
	r.jobs = append(r.jobs, job)
}

// Start launches every registered job.
func (r *Runner) Start(ctx context.Context) {
	for _, job := range r.jobs {
		r.wg.Add(1)
		go r.loop(ctx, job)
	}
}

// Wait blocks until every job stopped.
func (r *Runner) Wait() {
	r.wg.Wait()
}

func (r *Runner) loop(ctx context.Context, job Job) {
	defer r.wg.Done()

	if delayed, ok := job.(DelayedStart); ok {
		delay := delayed.InitialDelay(time.Now().UTC())
		r.logger.Info("worker scheduled",
			zap.String("worker", job.Name()),
			zap.Duration("first_run_in", delay),
		)
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
	}

	ticker := time.NewTicker(job.Interval())
	defer ticker.Stop()

	// The first pass runs immediately so a restart picks up work that piled up
	// while the process was down.
	r.runOnce(ctx, job)

	for {
		select {
		case <-ctx.Done():
			r.logger.Info("worker stopped", zap.String("worker", job.Name()))
			return
		case <-ticker.C:
			r.runOnce(ctx, job)
		}
	}
}

func (r *Runner) runOnce(ctx context.Context, job Job) {
	lockTTL := r.lockTTL
	if job.Timeout() > lockTTL {
		// The lock must outlive the run it protects, otherwise a second replica
		// could start the same pass halfway through.
		lockTTL = job.Timeout() + time.Minute
	}

	lock, err := r.locker.Acquire(ctx, job.Name(), lockTTL)
	if err != nil {
		r.logger.Error("could not acquire the worker lock",
			zap.String("worker", job.Name()), zap.Error(err))
		return
	}
	if lock == nil {
		// Another replica is running this pass; skipping is the expected
		// outcome and is what keeps the sweep single-active.
		r.logger.Debug("worker skipped, lock held elsewhere", zap.String("worker", job.Name()))
		return
	}
	defer func() {
		releaseCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		if err := lock.Release(releaseCtx); err != nil {
			r.logger.Warn("could not release the worker lock",
				zap.String("worker", job.Name()), zap.Error(err))
		}
	}()

	runCtx := ctx
	if job.Timeout() > 0 {
		var cancel context.CancelFunc
		runCtx, cancel = context.WithTimeout(ctx, job.Timeout())
		defer cancel()
	}

	started := time.Now()
	fields, err := job.Run(runCtx)
	fields = append(fields,
		zap.String("worker", job.Name()),
		zap.Duration("duration", time.Since(started)),
	)
	if err != nil {
		r.logger.Error("worker run failed", append(fields, zap.Error(err))...)
		return
	}
	r.logger.Info("worker run completed", fields...)
}
