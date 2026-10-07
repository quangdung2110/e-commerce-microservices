package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// ops_control keys (brief: "lane:<lane>" → entity.LaneState JSON,
// "replay_mode" → entity.ReplayMode JSON).
const (
	ControlKeyLanePrefix = "lane:"
	ControlKeyReplayMode = "replay_mode"
)

// ControlKeyLane is the ops_control key of a lane's pause switch.
func ControlKeyLane(lane entity.Lane) string { return ControlKeyLanePrefix + string(lane) }

var _ port.LaneControl = (*Control)(nil)

// Control implements port.LaneControl on ops_control (16-operations.md §2).
// Workers ask for the lane state on every claim loop, so reads are served
// from an in-memory snapshot refreshed at most every pollInterval
// (deliver.control_poll_interval); writes go through and refresh the
// snapshot immediately so the process that flipped the switch sees it at
// once. Other replicas see it within one poll interval, which is the
// documented latency of a pause.
type Control struct {
	ops          port.OpsRepository
	clock        clock.Clock
	pollInterval time.Duration
	logger       *zap.Logger

	mu       sync.Mutex
	loadedAt time.Time
	lanes    map[entity.Lane]entity.LaneState
	replay   entity.ReplayMode
	loaded   bool
}

// NewControl wires the control on any OpsRepository (pool-bound in practice).
func NewControl(ops port.OpsRepository, clk clock.Clock, pollInterval time.Duration, logger *zap.Logger) *Control {
	if logger == nil {
		logger = zap.NewNop()
	}
	if clk == nil {
		clk = clock.System{}
	}
	if pollInterval <= 0 {
		pollInterval = 5 * time.Second
	}
	return &Control{
		ops:          ops,
		clock:        clk,
		pollInterval: pollInterval,
		logger:       logger.Named("lane-control"),
		lanes:        map[entity.Lane]entity.LaneState{},
	}
}

func (c *Control) LaneState(ctx context.Context, lane entity.Lane) (entity.LaneState, error) {
	if err := c.ensureFresh(ctx); err != nil {
		return entity.LaneState{}, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if st, ok := c.lanes[lane]; ok {
		return st, nil
	}
	return entity.DefaultLaneState(lane, c.loadedAt), nil
}

func (c *Control) SetLaneState(ctx context.Context, state entity.LaneState) error {
	if state.Lane == "" {
		return apperr.ErrValidation.WithFields(apperr.Field{Field: "lane", Message: "required"})
	}
	if state.Email == "" {
		state.Email = entity.LaneActive
	}
	if state.InApp == "" {
		state.InApp = entity.LaneActive
	}
	if state.UpdatedAt.IsZero() {
		state.UpdatedAt = c.clock.Now()
	}
	raw, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("control: marshal lane state: %w", err)
	}
	updatedBy := ""
	if state.UpdatedBy != nil {
		updatedBy = *state.UpdatedBy
	}
	if err := c.ops.SetControl(ctx, ControlKeyLane(state.Lane), raw, updatedBy, state.UpdatedAt); err != nil {
		return err
	}
	return c.refresh(ctx)
}

func (c *Control) ReplayMode(ctx context.Context) (entity.ReplayMode, error) {
	if err := c.ensureFresh(ctx); err != nil {
		return entity.ReplayMode{}, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.replay, nil
}

func (c *Control) SetReplayMode(ctx context.Context, mode entity.ReplayMode, updatedBy string) error {
	raw, err := json.Marshal(mode)
	if err != nil {
		return fmt.Errorf("control: marshal replay mode: %w", err)
	}
	if err := c.ops.SetControl(ctx, ControlKeyReplayMode, raw, updatedBy, c.clock.Now()); err != nil {
		return err
	}
	return c.refresh(ctx)
}

// Refresh forces a reload (tests, notifctl).
func (c *Control) Refresh(ctx context.Context) error { return c.refresh(ctx) }

// ensureFresh reloads when the snapshot is older than pollInterval. A failed
// reload keeps serving the previous snapshot: a worker must not stop
// claiming because the control table blinked; only the first load is fatal.
func (c *Control) ensureFresh(ctx context.Context) error {
	c.mu.Lock()
	fresh := c.loaded && c.clock.Now().Sub(c.loadedAt) < c.pollInterval
	loaded := c.loaded
	c.mu.Unlock()
	if fresh {
		return nil
	}
	if err := c.refresh(ctx); err != nil {
		if !loaded {
			return err
		}
		c.logger.Warn("control refresh failed; serving cached state", zap.Error(err))
		c.mu.Lock()
		c.loadedAt = c.clock.Now() // do not hammer the DB on every claim
		c.mu.Unlock()
	}
	return nil
}

func (c *Control) refresh(ctx context.Context) error {
	values, err := c.ops.ListControls(ctx, "")
	if err != nil {
		return err
	}
	lanes := make(map[entity.Lane]entity.LaneState, len(entity.AllLanes))
	var replay entity.ReplayMode
	for key, raw := range values {
		switch {
		case key == ControlKeyReplayMode:
			if err := json.Unmarshal(raw, &replay); err != nil {
				c.logger.Warn("invalid replay_mode json; treating as disabled", zap.Error(err))
				replay = entity.ReplayMode{}
			}
		case strings.HasPrefix(key, ControlKeyLanePrefix):
			lane, err := entity.ParseLane(strings.TrimPrefix(key, ControlKeyLanePrefix))
			if err != nil {
				continue
			}
			var st entity.LaneState
			if err := json.Unmarshal(raw, &st); err != nil {
				c.logger.Warn("invalid lane state json; treating as active", zap.String("key", key), zap.Error(err))
				continue
			}
			st.Lane = lane
			if st.Email == "" {
				st.Email = entity.LaneActive
			}
			if st.InApp == "" {
				st.InApp = entity.LaneActive
			}
			lanes[lane] = st
		}
	}
	c.mu.Lock()
	c.lanes = lanes
	c.replay = replay
	c.loadedAt = c.clock.Now()
	c.loaded = true
	c.mu.Unlock()
	return nil
}
