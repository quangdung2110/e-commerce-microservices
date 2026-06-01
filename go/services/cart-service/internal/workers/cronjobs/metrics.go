package cronjobs

import (
	"sync/atomic"

	"github.com/robfig/cron/v3"
	"go.uber.org/zap"
)

// Metrics holds counters for all cronjobs.
type Metrics struct {
	logger *zap.Logger

	processed atomic.Int64
	failed   atomic.Int64
	deleted  atomic.Int64
	trimmed  atomic.Int64
	errors   atomic.Int64
}

// NewMetrics creates new metrics for all cronjobs.
func NewMetrics(logger *zap.Logger) *Metrics {
	return &Metrics{logger: logger}
}

// RecordProcessed records a processed item.
func (m *Metrics) RecordProcessed(n int) {
	m.processed.Add(int64(n))
}

// RecordError records a processing error.
func (m *Metrics) RecordError() {
	m.errors.Add(1)
}

// RecordDeleted records a hard delete operation.
func (m *Metrics) RecordDeleted() {
	m.deleted.Add(1)
}

// RecordTrimmed records a message trim operation.
func (m *Metrics) RecordTrimmed(n int64) {
	m.trimmed.Add(n)
}

// Snapshot returns a point-in-time snapshot.
func (m *Metrics) Snapshot() MetricsSnapshot {
	return MetricsSnapshot{
		Processed: m.processed.Load(),
		Failed:   m.failed.Load(),
		Deleted:  m.deleted.Load(),
		Trimmed:  m.trimmed.Load(),
		Errors:   m.errors.Load(),
	}
}

// MetricsSnapshot holds a point-in-time snapshot.
type MetricsSnapshot struct {
	Processed int64
	Failed   int64
	Deleted  int64
	Trimmed  int64
	Errors   int64
}

// Log logs the current metrics.
func (m *Metrics) Log(label string) {
	s := m.Snapshot()
	m.logger.Info("cronjobs metrics",
		zap.String("job", label),
		zap.Int64("processed", s.Processed),
		zap.Int64("failed", s.Failed),
		zap.Int64("deleted", s.Deleted),
		zap.Int64("trimmed", s.Trimmed),
		zap.Int64("errors", s.Errors),
	)
}

// Scheduler wraps robfig/cron for cronjob scheduling.
type Scheduler struct {
	cron    *cron.Cron
	logger  *zap.Logger
	entries map[string]cron.EntryID
}

// NewScheduler creates a new cron scheduler.
func NewScheduler(logger *zap.Logger) *Scheduler {
	return &Scheduler{
		cron:    cron.New(cron.WithSeconds()),
		logger:  logger,
		entries: make(map[string]cron.EntryID),
	}
}

// Add adds a job with a cron expression.
func (s *Scheduler) Add(name string, spec string, job cron.Job) error {
	id, err := s.cron.AddFunc(spec, job.Run)
	if err != nil {
		return err
	}
	s.entries[name] = id
	s.logger.Info("cronjob scheduled",
		zap.String("name", name),
		zap.String("spec", spec),
	)
	return nil
}

// AddWithSchedule adds a job with a pre-parsed schedule.
func (s *Scheduler) AddWithSchedule(name string, schedule cron.Schedule, job cron.Job) error {
	id := s.cron.Schedule(schedule, job)
	s.entries[name] = id
	s.logger.Info("cronjob scheduled",
		zap.String("name", name),
	)
	return nil
}

// Start starts the scheduler.
func (s *Scheduler) Start() {
	s.cron.Start()
	s.logger.Info("cron scheduler started")
}

// Stop stops the scheduler.
func (s *Scheduler) Stop() {
	ctx := s.cron.Stop()
	<-ctx.Done()
	s.logger.Info("cron scheduler stopped")
}

// Entry returns the cron entry for a job name.
func (s *Scheduler) Entry(name string) (cron.Entry, bool) {
	id, ok := s.entries[name]
	if !ok {
		return cron.Entry{}, false
	}
	return s.cron.Entry(id), true
}

// RunNow triggers a job to run immediately.
func (s *Scheduler) RunNow(name string) bool {
	id, ok := s.entries[name]
	if !ok {
		return false
	}
	s.cron.Entry(id).Job.Run()
	return true
}
