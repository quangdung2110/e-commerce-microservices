package sqs

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// TagDeliveryID is the SES message tag the deliver worker sets so Track can
// tie an event to a delivery without trusting the payload's addresses
// (03-tracking.md §6 item 3).
const TagDeliveryID = "delivery_id"

// emailRE finds addresses anywhere inside a string ("Name <a@b.c>", SMTP
// diagnostic codes, header lines). It is deliberately broad: hashing a
// non-address that looks like one (e.g. an RFC 5322 Message-ID) is harmless,
// leaving a real address is not (11-privacy.md).
var emailRE = regexp.MustCompile(`[A-Za-z0-9._%+\-'/=]+@[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?)*\.[A-Za-z]{2,}`)

// SESEvent is the parsed, sanitised view of one SES event-publishing record
// (the SNS Message body).
type SESEvent struct {
	// Type is the normalised event type; RawType keeps the provider spelling.
	Type    entity.ProviderEventType
	RawType string
	// ProviderMessageID is mail.messageId, the correlation key to
	// notification_delivery.provider_message_id.
	ProviderMessageID string
	// ProviderEventID is sha256(messageId | eventType | mail.timestamp |
	// event timestamp) because SES issues no event id (09-data-model.md §5.1).
	ProviderEventID string
	// OccurredAt is the event-specific timestamp, falling back to
	// mail.timestamp; nil when neither parses.
	OccurredAt *time.Time
	// DeliveryID is mail.tags.delivery_id[0] when present and a UUID.
	DeliveryID *uuid.UUID
	// BounceType is set for bounce events (Undetermined → transient).
	BounceType entity.BounceType
	// RecipientHashes are the sha256 of the bounced/complained recipients, or
	// of mail.destination for other events; deduplicated, order preserved.
	RecipientHashes []string
	// Sanitized is the event JSON with every address replaced by its hash;
	// this is what goes into provider_event.raw.
	Sanitized json.RawMessage
}

type sesRecipient struct {
	EmailAddress string `json:"emailAddress"`
}

type sesRaw struct {
	EventType        string `json:"eventType"`
	NotificationType string `json:"notificationType"` // identity-notification format
	Mail             struct {
		Timestamp   string              `json:"timestamp"`
		MessageID   string              `json:"messageId"`
		Destination []string            `json:"destination"`
		Tags        map[string][]string `json:"tags"`
	} `json:"mail"`
	Bounce *struct {
		BounceType        string         `json:"bounceType"`
		BouncedRecipients []sesRecipient `json:"bouncedRecipients"`
		Timestamp         string         `json:"timestamp"`
	} `json:"bounce"`
	Complaint *struct {
		ComplainedRecipients []sesRecipient `json:"complainedRecipients"`
		Timestamp            string         `json:"timestamp"`
	} `json:"complaint"`
	Delivery *struct {
		Timestamp  string   `json:"timestamp"`
		Recipients []string `json:"recipients"`
	} `json:"delivery"`
	DeliveryDelay *struct {
		Timestamp string `json:"timestamp"`
	} `json:"deliveryDelay"`
}

// NormalizeEventType maps the SES spelling (case-insensitive) to the domain
// enum; anything unknown (Open, Click, Subscription, RenderingFailure) → other.
func NormalizeEventType(raw string) entity.ProviderEventType {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "send":
		return entity.ProviderEventSend
	case "delivery":
		return entity.ProviderEventDelivery
	case "bounce":
		return entity.ProviderEventBounce
	case "complaint":
		return entity.ProviderEventComplaint
	case "reject":
		return entity.ProviderEventReject
	case "deliverydelay", "delivery_delay":
		return entity.ProviderEventDeliveryDelay
	}
	return entity.ProviderEventOther
}

// ParseSESEvent decodes the SES event JSON and produces the sanitised form.
// The raw bytes must never be logged or stored by the caller.
func ParseSESEvent(raw []byte) (*SESEvent, error) {
	var r sesRaw
	if err := json.Unmarshal(raw, &r); err != nil {
		return nil, fmt.Errorf("ses: decode event: %w", err)
	}
	rawType := r.EventType
	if rawType == "" {
		rawType = r.NotificationType
	}
	if rawType == "" {
		return nil, fmt.Errorf("ses: event has no eventType")
	}
	if r.Mail.MessageID == "" {
		return nil, fmt.Errorf("ses: event has no mail.messageId")
	}
	ev := &SESEvent{
		Type:              NormalizeEventType(rawType),
		RawType:           rawType,
		ProviderMessageID: r.Mail.MessageID,
	}

	// Event-specific timestamp and recipients.
	eventTS := ""
	var recipients []string
	switch ev.Type {
	case entity.ProviderEventBounce:
		if r.Bounce != nil {
			eventTS = r.Bounce.Timestamp
			for _, rc := range r.Bounce.BouncedRecipients {
				recipients = append(recipients, rc.EmailAddress)
			}
			ev.BounceType = normalizeBounceType(r.Bounce.BounceType)
		} else {
			ev.BounceType = entity.BounceTransient
		}
	case entity.ProviderEventComplaint:
		if r.Complaint != nil {
			eventTS = r.Complaint.Timestamp
			for _, rc := range r.Complaint.ComplainedRecipients {
				recipients = append(recipients, rc.EmailAddress)
			}
		}
	case entity.ProviderEventDelivery:
		if r.Delivery != nil {
			eventTS = r.Delivery.Timestamp
			recipients = r.Delivery.Recipients
		}
	case entity.ProviderEventDeliveryDelay:
		if r.DeliveryDelay != nil {
			eventTS = r.DeliveryDelay.Timestamp
		}
	}
	if len(recipients) == 0 {
		recipients = r.Mail.Destination
	}
	ev.RecipientHashes = hashRecipients(recipients)

	if t, ok := parseSESTime(eventTS); ok {
		ev.OccurredAt = &t
	} else if t, ok := parseSESTime(r.Mail.Timestamp); ok {
		ev.OccurredAt = &t
	}

	// Stable id: same event delivered twice by SNS/SQS hashes to the same
	// value; a Bounce and a Delivery of the same mail do not.
	ev.ProviderEventID = ProviderEventID(r.Mail.MessageID, string(ev.Type), r.Mail.Timestamp, eventTS)

	if ids := r.Mail.Tags[TagDeliveryID]; len(ids) > 0 {
		if id, err := uuid.Parse(strings.TrimSpace(ids[0])); err == nil {
			ev.DeliveryID = &id
		}
	}

	sanitized, err := Sanitize(raw)
	if err != nil {
		return nil, err
	}
	ev.Sanitized = sanitized
	return ev, nil
}

// ProviderEventID derives the dedup key for an SES event (09-data-model.md
// §5.1). Fields are joined with "|" so adjacent values cannot run together.
func ProviderEventID(messageID, eventType, mailTimestamp, eventTimestamp string) string {
	sum := sha256.Sum256([]byte(messageID + "|" + strings.ToLower(eventType) + "|" + mailTimestamp + "|" + eventTimestamp))
	return hex.EncodeToString(sum[:])
}

func normalizeBounceType(raw string) entity.BounceType {
	if strings.EqualFold(strings.TrimSpace(raw), "Permanent") {
		return entity.BouncePermanent
	}
	// Transient and Undetermined: never suppress permanently on a guess.
	return entity.BounceTransient
}

func parseSESTime(s string) (time.Time, bool) {
	if s == "" {
		return time.Time{}, false
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}, false
	}
	return t.UTC(), true
}

func hashRecipients(addrs []string) []string {
	seen := make(map[string]bool, len(addrs))
	out := make([]string, 0, len(addrs))
	for _, a := range addrs {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		// SES may report "Name <addr>"; hash the address only so it matches
		// the hash the deliver worker computed from the contact.
		if m := emailRE.FindString(a); m != "" {
			a = m
		}
		h := pii.HashEmail(a)
		if seen[h] {
			continue
		}
		seen[h] = true
		out = append(out, h)
	}
	return out
}

// Sanitize replaces every address-shaped substring in every string value of
// the JSON document with pii.HashEmail of that substring (03-tracking.md §3:
// "bản gốc không bao giờ chạm DB"). Keys are left untouched. The result is
// re-encoded, so key order may differ from the input.
func Sanitize(raw []byte) (json.RawMessage, error) {
	var doc any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, fmt.Errorf("ses: decode for sanitise: %w", err)
	}
	doc = sanitizeValue(doc)
	out, err := json.Marshal(doc)
	if err != nil {
		return nil, fmt.Errorf("ses: encode sanitised: %w", err)
	}
	return out, nil
}

func sanitizeValue(v any) any {
	switch t := v.(type) {
	case string:
		return SanitizeString(t)
	case []any:
		for i := range t {
			t[i] = sanitizeValue(t[i])
		}
		return t
	case map[string]any:
		for k, val := range t {
			t[k] = sanitizeValue(val)
		}
		return t
	default:
		return v
	}
}

// SanitizeString hashes every address-shaped substring of s.
func SanitizeString(s string) string {
	if !strings.Contains(s, "@") {
		return s
	}
	return emailRE.ReplaceAllStringFunc(s, pii.HashEmail)
}

// ContainsEmail reports whether s still holds something that looks like an
// address (used by tests and as a last-line assertion before INSERT).
func ContainsEmail(s string) bool {
	return emailRE.MatchString(s)
}
