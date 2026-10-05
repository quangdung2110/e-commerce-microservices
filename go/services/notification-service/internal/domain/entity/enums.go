// Package entity holds the domain model of the Notification Service: the
// normalised request, the delivery row that doubles as the queue, the
// replicated preference, suppression, inbox item, provider event and outbox
// event (docs/notification/09-data-model.md).
package entity

import "fmt"

// Lane is the priority lane (01-lanes.md).
type Lane string

const (
	LaneCritical      Lane = "critical"
	LaneTransactional Lane = "transactional"
	LaneBulk          Lane = "bulk"
)

// AllLanes is the fixed lane list, in priority order.
var AllLanes = []Lane{LaneCritical, LaneTransactional, LaneBulk}

func ParseLane(s string) (Lane, error) {
	switch Lane(s) {
	case LaneCritical, LaneTransactional, LaneBulk:
		return Lane(s), nil
	}
	return "", fmt.Errorf("unknown lane %q", s)
}

// Channel is a delivery channel (D-08: email + in_app in phase 1).
type Channel string

const (
	ChannelEmail Channel = "email"
	ChannelInApp Channel = "in_app"
)

var AllChannels = []Channel{ChannelEmail, ChannelInApp}

func ParseChannel(s string) (Channel, error) {
	switch Channel(s) {
	case ChannelEmail, ChannelInApp:
		return Channel(s), nil
	}
	return "", fmt.Errorf("unknown channel %q", s)
}

// InputKind tells whether the request came as a producer request or as a
// domain fact mapped by Notification (D-07).
type InputKind string

const (
	InputRequest InputKind = "request"
	InputFact    InputKind = "fact"
)

// Decision is the request-level outcome of Decide (08-decide.md §7).
type Decision string

const (
	DecisionDecided Decision = "DECIDED"
	DecisionSkipped Decision = "SKIPPED"
	DecisionFailed  Decision = "FAILED"
	DecisionExpired Decision = "EXPIRED"
	DecisionIgnored Decision = "IGNORED"
)

// DeliveryStatus is the queue/state column (02-pipeline.md §5.5).
type DeliveryStatus string

const (
	StatusPending   DeliveryStatus = "PENDING"
	StatusSending   DeliveryStatus = "SENDING"
	StatusSent      DeliveryStatus = "SENT"
	StatusRetry     DeliveryStatus = "RETRY"
	StatusFailed    DeliveryStatus = "FAILED"
	StatusDelivered DeliveryStatus = "DELIVERED"
	StatusBounced   DeliveryStatus = "BOUNCED"
	StatusExpired   DeliveryStatus = "EXPIRED"
	StatusSkipped   DeliveryStatus = "SKIPPED"
)

// IsFinal reports whether the status is terminal.
func (s DeliveryStatus) IsFinal() bool {
	switch s {
	case StatusFailed, StatusDelivered, StatusBounced, StatusExpired, StatusSkipped:
		return true
	}
	return false
}

// TrackingRank orders provider-reported states so late events cannot
// downgrade a delivery (03-tracking.md §5.1): SENT(1) < DELIVERED(2) <
// BOUNCED(3). Unknown states rank 0.
func (s DeliveryStatus) TrackingRank() int {
	switch s {
	case StatusSent:
		return 1
	case StatusDelivered:
		return 2
	case StatusBounced:
		return 3
	}
	return 0
}

// Provider identifies who carried a delivery.
type Provider string

const (
	ProviderSES     Provider = "ses"
	ProviderMailpit Provider = "mailpit"
	ProviderInbox   Provider = "inbox"
)

// Reason codes are an operational contract (08-decide.md §7, 10-interfaces §3).
const (
	// Request-level FAILED (producer contract errors) — alert on first sight.
	ReasonUnknownTemplate     = "unknown_template"
	ReasonLaneMismatch        = "lane_mismatch"
	ReasonInvalidTemplateData = "invalid_template_data"
	ReasonMissingDedupKey     = "missing_dedup_key"
	ReasonNoRecipient         = "no_recipient"
	// Request-level EXPIRED.
	ReasonAlreadyExpired       = "already_expired"
	ReasonQuietHoursPastExpiry = "quiet_hours_past_expiry"
	// Request-level SKIPPED.
	ReasonAllChannelsOff = "all_channels_off"
	ReasonFrequencyCap   = "frequency_cap"
	ReasonDuplicate      = "duplicate"
	// Request-level IGNORED.
	ReasonUnmappedFact = "unmapped_fact"
	ReasonReplayTooOld = "replay_too_old"

	// Delivery-level skip_reason (channel_reason).
	ReasonPreferenceOff     = "preference_off"
	ReasonSuppressed        = "suppressed"
	ReasonChannelNotAllowed = "channel_not_allowed"
	ReasonUserDeleted       = "user_deleted"
	ReasonEmailUnverified   = "email_unverified"
	ReasonSuperseded        = "superseded"

	// Delivery-level last_error_code / reason_code in notification.failed.v1.
	ReasonExpired          = "expired"
	ReasonLeaseExpired     = "lease_expired"
	ReasonProviderError    = "provider_error"
	ReasonRenderFailed     = "render_failed"
	ReasonBreakerOpen      = "breaker_open"
	ReasonRateLimited      = "rate_limited"
	ReasonContentExpired   = "content_expired"
	ReasonTemplateMismatch = "template_mismatch"
)

// ProducerContractReasons are the FAILED reasons that must never be redriven
// (the payload itself is wrong, R-04).
var ProducerContractReasons = map[string]bool{
	ReasonUnknownTemplate:     true,
	ReasonLaneMismatch:        true,
	ReasonInvalidTemplateData: true,
	ReasonMissingDedupKey:     true,
	ReasonNoRecipient:         true,
	ReasonRenderFailed:        true,
	ReasonTemplateMismatch:    true,
}
