package workers

import (
	"context"
	"sync"

	"go.uber.org/zap"
)

// Worker interface for all background workers
type Worker interface {
	Start(ctx context.Context) error
	Stop()
}

// Runner manages all background workers
type Runner struct {
	workers []Worker
	logger  *zap.Logger
	wg      sync.WaitGroup
}

// NewRunner creates a new worker runner
func NewRunner(logger *zap.Logger) *Runner {
	return &Runner{
		workers: make([]Worker, 0),
		logger:  logger.With(zap.String("component", "workers")),
	}
}

// Register registers a worker to be started
func (r *Runner) Register(worker Worker) {
	r.workers = append(r.workers, worker)
}

// Start starts all registered workers
func (r *Runner) Start(ctx context.Context) {
	r.logger.Info("Starting workers", zap.Int("count", len(r.workers)))

	for _, worker := range r.workers {
		r.wg.Add(1)
		go func(w Worker) {
			defer r.wg.Done()
			if err := w.Start(ctx); err != nil {
				r.logger.Error("Worker error", zap.Error(err))
			}
		}(worker)
	}
}

// Stop stops all registered workers
func (r *Runner) Stop() {
	r.logger.Info("Stopping all workers", zap.Int("count", len(r.workers)))
	for _, worker := range r.workers {
		worker.Stop()
	}
	r.wg.Wait()
	r.logger.Info("All workers stopped")
}
