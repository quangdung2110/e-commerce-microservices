package entity

import "time"

// SourceOrder is the only producer whose requests carry a mandatory,
// monotonic notification sequence and are serialised per subject
// (05-ordering.md §4, D-05).
const SourceOrder = "/services/order"

// SubjectSequence is one notification_subject_sequence row: the highest Order
// notification sequence seen for HMAC(source ‖ NUL ‖ subject) under one key
// version (09-data-model.md §3). No raw subject is stored.
type SubjectSequence struct {
	Source               string
	KeyVersion           int16
	KeyHash              string
	HighestSequence      int64
	HighestEventID       string
	HighestPayloadSHA256 string
	UpdatedAt            time.Time
}

// SequenceOutcome is what the consumer decided for one Order request
// (metric subject_sequence_events_total{outcome}).
type SequenceOutcome string

const (
	SequenceAdvanced   SequenceOutcome = "advanced"
	SequenceDuplicate  SequenceOutcome = "duplicate"
	SequenceSuperseded SequenceOutcome = "superseded"
	SequenceCollision  SequenceOutcome = "collision"
	SequenceMissing    SequenceOutcome = "missing"
)

// OrderingLeaseAge is the oldest expired-but-unreclaimed ordering lease of a
// source (gauge ordering_lease_oldest_age_seconds).
type OrderingLeaseAge struct {
	Source string
	Age    time.Duration
}
