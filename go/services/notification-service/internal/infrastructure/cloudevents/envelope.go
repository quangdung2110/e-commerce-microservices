// Package cloudevents parses the structured CloudEvents 1.0 JSON envelope every
// consumed topic uses (contracts/shared/json-schema/v1/cloud-event.schema.json).
// Only the envelope is validated here; the data block is interpreted by the
// per-source handler and, for template_data, by Decide (08-decide.md §3 step 3).
package cloudevents

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// ErrInvalidEnvelope marks a message whose envelope cannot be parsed. The
// consumer dead-letters it immediately: retrying would decode the same bytes
// (02-pipeline.md §4 "lỗi envelope → DLQ ngay").
var ErrInvalidEnvelope = errors.New("cloudevents: invalid envelope")

// SpecVersion is the only CloudEvents version the contracts allow.
const SpecVersion = "1.0"

// Envelope is the parsed CloudEvents envelope. Time is zero when the message
// carried none; Sequence is nil when absent.
type Envelope struct {
	SpecVersion     string
	ID              string
	Type            string
	Source          string
	Subject         string
	Time            time.Time
	DataContentType string
	DataSchema      string
	CorrelationID   string
	TraceParent     string
	Sequence        *int64
	Data            json.RawMessage
}

type wire struct {
	SpecVersion     string          `json:"specversion"`
	ID              string          `json:"id"`
	Type            string          `json:"type"`
	Source          string          `json:"source"`
	Subject         string          `json:"subject"`
	Time            string          `json:"time"`
	DataContentType string          `json:"datacontenttype"`
	DataSchema      string          `json:"dataschema"`
	CorrelationID   string          `json:"correlationid"`
	TraceParent     string          `json:"traceparent"`
	Sequence        *int64          `json:"sequence"`
	Data            json.RawMessage `json:"data"`
}

// Parse decodes a structured CloudEvents JSON message and checks the fields
// Ingest depends on: id, type and source (dedup layer A key and routing),
// specversion 1.0 and a present, non-null data block. time is optional in
// practice (Cart facts carry it, some producers may not) but must parse when
// present because replay mode compares it (04-idempotency.md §5).
func Parse(raw []byte) (*Envelope, error) {
	if len(bytes.TrimSpace(raw)) == 0 {
		return nil, fmt.Errorf("%w: empty message", ErrInvalidEnvelope)
	}
	var w wire
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&w); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidEnvelope, err)
	}
	if w.SpecVersion != SpecVersion {
		return nil, fmt.Errorf("%w: specversion %q (want %s)", ErrInvalidEnvelope, w.SpecVersion, SpecVersion)
	}
	if w.ID == "" {
		return nil, fmt.Errorf("%w: missing id", ErrInvalidEnvelope)
	}
	if w.Type == "" {
		return nil, fmt.Errorf("%w: missing type", ErrInvalidEnvelope)
	}
	if w.Source == "" {
		return nil, fmt.Errorf("%w: missing source", ErrInvalidEnvelope)
	}
	if len(w.Data) == 0 || bytes.Equal(bytes.TrimSpace(w.Data), []byte("null")) {
		return nil, fmt.Errorf("%w: missing data", ErrInvalidEnvelope)
	}
	if w.Sequence != nil && *w.Sequence < 0 {
		return nil, fmt.Errorf("%w: negative sequence", ErrInvalidEnvelope)
	}

	env := &Envelope{
		SpecVersion:     w.SpecVersion,
		ID:              w.ID,
		Type:            w.Type,
		Source:          w.Source,
		Subject:         w.Subject,
		DataContentType: w.DataContentType,
		DataSchema:      w.DataSchema,
		CorrelationID:   w.CorrelationID,
		TraceParent:     w.TraceParent,
		Sequence:        w.Sequence,
		Data:            w.Data,
	}
	if w.Time != "" {
		t, err := time.Parse(time.RFC3339Nano, w.Time)
		if err != nil {
			return nil, fmt.Errorf("%w: time: %v", ErrInvalidEnvelope, err)
		}
		env.Time = t.UTC()
	}
	return env, nil
}

// IsInvalid reports whether err is (or wraps) ErrInvalidEnvelope.
func IsInvalid(err error) bool { return errors.Is(err, ErrInvalidEnvelope) }
