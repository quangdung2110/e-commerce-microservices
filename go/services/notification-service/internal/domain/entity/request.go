package entity

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// IngestInput is the normalised NotificationRequest every consumer produces
// before Decide (02-pipeline.md §2.1). From here on the pipeline does not care
// which source the message came from.
type IngestInput struct {
	Source    string // CloudEvents source, e.g. /services/order
	EventID   string // CloudEvents id (dedup layer A key with Source)
	EventType string // CloudEvents type
	EventTime time.Time
	Subject   string
	InputKind InputKind

	// Lane implied by the topic (request) or by the fact mapping (fact). Decide
	// checks it against the catalog lane of the template (lane_mismatch).
	Lane Lane
	// Template is empty when a fact has no mapping (→ IGNORED unmapped_fact).
	Template string
	// UserID is kept raw; Decide validates it is a UUID (no_recipient).
	UserID string
	// DedupKey is the producer value (request) or the mapping formula (fact);
	// empty when the template declares mode none.
	DedupKey string
	// ExpiresAt is the producer's optional cut-off; Decide takes
	// min(ExpiresAt, now + max_age).
	ExpiresAt *time.Time
	// TemplateData is validated against the template schema. Nil for Auth
	// secret templates (OTP/reset): the content is resolved at send time.
	TemplateData json.RawMessage
	// SourceRef is the producer-side reference the worker needs at send time
	// (Auth delivery_id). Empty for other sources.
	SourceRef string

	CorrelationID string
	TraceParent   string
	// Sequence is the CloudEvents sequence; required (>= 1) for /services/order,
	// optional for every other source (05-ordering.md §4).
	Sequence *int64
	// PayloadSHA256 is the hex sha256 of the canonical JSON of the CloudEvents
	// data block (keys sorted, numbers verbatim). Two Order requests with the
	// same sequence must carry the same event id and digest, otherwise they
	// collide (09-data-model.md §3).
	PayloadSHA256 string
}

// Request is the persisted notification_request row (09-data-model.md §3.2).
type Request struct {
	ID             uuid.UUID
	Source         string
	EventID        string
	EventType      string
	EventTime      *time.Time
	Subject        *string
	SourceSequence *int64
	InputKind      InputKind
	Template       *string
	Lane           *Lane
	UserID         *uuid.UUID
	DedupKey       *string
	ExpiresAt      *time.Time
	TemplateData   json.RawMessage // nil → SQL NULL
	SourceRef      *string
	CorrelationID  *string
	TraceParent    *string
	Decision       Decision
	DecisionReason *string
	ReceivedAt     time.Time
}

// ChannelDecision is Decide's verdict for one channel of a template.
type ChannelDecision struct {
	Channel Channel
	// Keep is true when a PENDING delivery is created; false creates a SKIPPED
	// delivery row carrying SkipReason (so the timeline shows why).
	Keep       bool
	SkipReason string
	// NextAttemptAt is now, or the end of quiet hours for bulk.
	NextAttemptAt time.Time
}

// DecideResult is the full output of the 13-step Decide (08-decide.md §3).
type DecideResult struct {
	Decision Decision
	Reason   string
	// Step reports which step (1–13) ended the evaluation; 0 when DECIDED.
	Step int
	// EffectiveExpiresAt = min(request.expires_at, now + template.max_age).
	EffectiveExpiresAt time.Time
	Channels           []ChannelDecision
	// UserID is the parsed recipient, valid when Step >= 6.
	UserID uuid.UUID
}

// Kept returns the channels that get a PENDING delivery.
func (r DecideResult) Kept() []ChannelDecision {
	var out []ChannelDecision
	for _, c := range r.Channels {
		if c.Keep {
			out = append(out, c)
		}
	}
	return out
}
