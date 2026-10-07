// Package request holds the JSON request bodies exactly as the OpenAPI
// schemas declare them (contracts/http/notification/{v1,internal/v1}).
// Required scalars are pointers so an absent member can be told from a zero
// value.
package request

import "time"

// InboxItemUpdate is PATCH /inbox/{item_id}.
type InboxItemUpdate struct {
	Read *bool `json:"read"`
}

// LaneStateUpdate is PUT /ops/lanes/{lane}.
type LaneStateUpdate struct {
	State   *string `json:"state"`
	Channel string  `json:"channel,omitempty"`
	Reason  string  `json:"reason,omitempty"`
}

// Redrive is the optional body of POST /ops/deliveries/{id}/redrive.
type Redrive struct {
	ExpiresAt *time.Time `json:"expires_at,omitempty"`
	Reason    string     `json:"reason,omitempty"`
}

// DlqRedrive is POST /ops/dlq/redrive.
type DlqRedrive struct {
	Lane         *string    `json:"lane"`
	ReasonCode   string     `json:"reason_code,omitempty"`
	FailedAfter  *time.Time `json:"failed_after,omitempty"`
	FailedBefore *time.Time `json:"failed_before,omitempty"`
	ExpiresAt    *time.Time `json:"expires_at,omitempty"`
	Reason       *string    `json:"reason"`
}

// ManualSuppression is POST /ops/suppressions. The caller names a user, never
// an address.
type ManualSuppression struct {
	UserID *string    `json:"user_id"`
	Until  *time.Time `json:"until,omitempty"`
	Reason *string    `json:"reason"`
}

// ReplayMode is PUT /ops/replay-mode. expires_at is read-only in the schema
// and therefore not accepted here (additionalProperties: false would reject
// any other member; a read-only member sent by a client is ignored).
type ReplayMode struct {
	Enabled               *bool  `json:"enabled"`
	IgnoreEventsOlderThan string `json:"ignore_events_older_than,omitempty"`
	TTL                   string `json:"ttl,omitempty"`
	ExpiresAt             any    `json:"expires_at,omitempty"`
}
