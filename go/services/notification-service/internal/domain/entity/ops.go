package entity

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// LaneChannelState is active or paused.
type LaneChannelState string

const (
	LaneActive LaneChannelState = "active"
	LanePaused LaneChannelState = "paused"
)

// LaneState is the pause switch per lane × channel (16-operations.md §2),
// stored in ops_control under key "lane:<lane>".
type LaneState struct {
	Lane      Lane             `json:"lane"`
	Email     LaneChannelState `json:"email"`
	InApp     LaneChannelState `json:"in_app"`
	UpdatedAt time.Time        `json:"updated_at"`
	UpdatedBy *string          `json:"updated_by,omitempty"`
	Reason    *string          `json:"reason,omitempty"`
}

// IsPaused reports whether the given channel of the lane is paused.
func (s LaneState) IsPaused(ch Channel) bool {
	if ch == ChannelInApp {
		return s.InApp == LanePaused
	}
	return s.Email == LanePaused
}

// DefaultLaneState is active on both channels.
func DefaultLaneState(lane Lane, now time.Time) LaneState {
	return LaneState{Lane: lane, Email: LaneActive, InApp: LaneActive, UpdatedAt: now}
}

// ReplayMode makes consumers IGNORE events older than a threshold while an
// offset reset is replayed (04-idempotency.md §5, R-14).
type ReplayMode struct {
	Enabled               bool          `json:"enabled"`
	IgnoreEventsOlderThan time.Duration `json:"ignore_events_older_than"`
	ExpiresAt             *time.Time    `json:"expires_at,omitempty"`
}

// ActiveAt reports whether replay mode applies now.
func (r ReplayMode) ActiveAt(now time.Time) bool {
	if !r.Enabled {
		return false
	}
	return r.ExpiresAt == nil || r.ExpiresAt.After(now)
}

// IdempotencyRecord stores an ops call keyed by Idempotency-Key for 24 h.
type IdempotencyRecord struct {
	Key         string
	RequestHash string
	Status      int
	Response    json.RawMessage
	CreatedAt   time.Time
}

// AuditEntry is one ops action (16-operations.md §2: every switch is audited).
type AuditEntry struct {
	ID        uuid.UUID
	Principal string
	Operation string
	Params    json.RawMessage
	Result    string
	CreatedAt time.Time
}
