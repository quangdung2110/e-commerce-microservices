package cronjobs

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/infrastructure/config"
)

// StreamTrimmer trims acknowledged messages from Redis Streams to free up RAM.
type StreamTrimmer struct {
	redisClient      *redis.Client
	kb               *keybuilder.KeyBuilder
	numShards        int
	cfg              config.StreamTrimmerCfg
	logger           *zap.Logger
	metrics          *Metrics
	interval         time.Duration
	pendingThreshold time.Duration
	schedule         cron.Schedule
}

// NewStreamTrimmer creates a new stream trimmer job.
func NewStreamTrimmer(
	redisClient *redis.Client,
	kb *keybuilder.KeyBuilder,
	numShards int,
	cfg config.StreamTrimmerCfg,
	logger *zap.Logger,
) *StreamTrimmer {
	interval := parseStreamTrimDuration(cfg.Interval, 5*time.Minute)
	pendingThreshold := parseStreamTrimDuration(cfg.PendingThreshold, 30*time.Minute)

	return &StreamTrimmer{
		redisClient:      redisClient,
		kb:               kb,
		numShards:        numShards,
		cfg:              cfg,
		logger:           logger.With(zap.String("job", "stream_trimmer")),
		metrics:          NewMetrics(logger),
		interval:         interval,
		pendingThreshold: pendingThreshold,
	}
}

// RunPeriodic starts the periodic trimming loop.
func (j *StreamTrimmer) RunPeriodic(ctx context.Context) {
	ticker := time.NewTicker(j.interval)
	defer ticker.Stop()

	j.logger.Info("stream trimmer started",
		zap.Duration("interval", j.interval),
		zap.Duration("pending_threshold", j.pendingThreshold),
		zap.Int("num_shards", j.numShards),
	)

	for {
		select {
		case <-ctx.Done():
			j.logger.Info("stream trimmer stopped")
			return
		case <-ticker.C:
			j.trimAllShards(ctx)
		}
	}
}

func (j *StreamTrimmer) trimAllShards(ctx context.Context) {
	for shardID := 0; shardID < j.numShards; shardID++ {
		j.trimShard(ctx, shardID)
	}
}

func (j *StreamTrimmer) trimShard(ctx context.Context, shardID int) {
	streamKey := j.kb.StreamShard(uint32(shardID))

	length, err := j.redisClient.XLen(ctx, streamKey).Result()
	if err != nil {
		if err == redis.Nil {
			return
		}
		j.logger.Warn("XLEN failed",
			zap.Int("shard", shardID),
			zap.String("stream", streamKey),
			zap.Error(err),
		)
		return
	}
	if length == 0 {
		return
	}

	groups, err := j.redisClient.XInfoGroups(ctx, streamKey).Result()
	if err != nil {
		if err == redis.Nil {
			j.logger.Debug("no consumer group, skipping trim",
				zap.String("stream", streamKey))
			return
		}
		j.logger.Warn("XINFO GROUPS failed",
			zap.String("stream", streamKey),
			zap.Error(err),
		)
		return
	}

	if len(groups) == 0 {
		return
	}

	group := groups[0]
	pendingCount := group.Pending

	var safeID string
	if pendingCount == 0 {
		safeID = group.LastDeliveredID
	} else {
		pending, err := j.redisClient.XPending(ctx, streamKey, group.Name).Result()
		if err != nil {
			j.logger.Warn("XPENDING failed",
				zap.String("stream", streamKey),
				zap.Error(err),
			)
			return
		}
		if pending.Lower != "" && pending.Lower < group.LastDeliveredID {
			safeID = pending.Lower
		} else {
			safeID = group.LastDeliveredID
		}
	}

	if safeID == "" || safeID == "0" {
		return
	}

	trimmed, err := j.redisClient.XTrimMinID(ctx, streamKey, safeID).Result()
	if err != nil {
		j.logger.Error("XTRIMMinID failed",
			zap.String("stream", streamKey),
			zap.String("safe_id", safeID),
			zap.Error(err),
		)
		return
	}

	if trimmed > 0 {
		j.metrics.RecordTrimmed(trimmed)
		j.logger.Info("stream trimmed",
			zap.String("stream", streamKey),
			zap.Int64("messages_trimmed", trimmed),
			zap.String("safe_id", safeID),
		)
	}
}

// Schedule returns the cron schedule for this job.
func (j *StreamTrimmer) Schedule() cron.Schedule {
	return j.schedule
}

// SetSchedule sets the cron schedule.
func (j *StreamTrimmer) SetSchedule(s cron.Schedule) {
	j.schedule = s
}

// Metrics returns the job metrics.
func (j *StreamTrimmer) Metrics() *Metrics {
	return j.metrics
}

// Config returns the job configuration.
func (j *StreamTrimmer) Config() config.StreamTrimmerCfg {
	return j.cfg
}

// Run implements the cron.Job interface.
func (j *StreamTrimmer) Run() {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	j.trimAllShards(ctx)
}

func parseStreamTrimDuration(s string, fallback time.Duration) time.Duration {
	if s == "" {
		return fallback
	}
	if d, err := time.ParseDuration(s); err == nil {
		return d
	}
	return fallback
}
