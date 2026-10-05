// Package response holds the JSON response bodies exactly as the OpenAPI
// schemas declare them, plus the mappers from entities. Nothing here carries
// template_data, a user id on the status API, or an address (11-privacy.md
// §5).
package response

import (
	"strconv"
	"time"

	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// ---- inbox (contracts/http/notification/v1) ------------------------------

// InboxItem is the InboxItem schema.
type InboxItem struct {
	ID        string     `json:"id"`
	Template  string     `json:"template"`
	Title     string     `json:"title"`
	Body      string     `json:"body"`
	DeepLink  *string    `json:"deep_link"`
	CreatedAt time.Time  `json:"created_at"`
	Read      bool       `json:"read"`
	ReadAt    *time.Time `json:"read_at"`
}

// InboxPage is the InboxPage schema; NextCursor is null on the last page.
type InboxPage struct {
	Items      []InboxItem `json:"items"`
	NextCursor *string     `json:"next_cursor"`
}

// UnreadCount is the UnreadCount schema.
type UnreadCount struct {
	Count int64     `json:"count"`
	AsOf  time.Time `json:"as_of"`
}

// FromInboxItem maps the entity.
func FromInboxItem(it entity.InboxItem) InboxItem {
	return InboxItem{
		ID:        it.ID.String(),
		Template:  it.Template,
		Title:     it.Title,
		Body:      it.Body,
		DeepLink:  it.DeepLink,
		CreatedAt: it.CreatedAt.UTC(),
		Read:      it.ReadAt != nil,
		ReadAt:    utcPtr(it.ReadAt),
	}
}

// FromInboxItems maps a page; an empty page is [] not null.
func FromInboxItems(items []entity.InboxItem) []InboxItem {
	out := make([]InboxItem, 0, len(items))
	for _, it := range items {
		out = append(out, FromInboxItem(it))
	}
	return out
}

// ---- status (contracts/http/notification/internal/v1) --------------------

// DeliveryStatus is the DeliveryStatus schema.
type DeliveryStatus struct {
	DeliveryID        string     `json:"delivery_id"`
	RequestID         string     `json:"request_id"`
	Lane              string     `json:"lane"`
	Channel           string     `json:"channel"`
	Template          string     `json:"template"`
	Status            string     `json:"status"`
	Attempt           int        `json:"attempt"`
	NextAttemptAt     *time.Time `json:"next_attempt_at"`
	ExpiresAt         *time.Time `json:"expires_at"`
	Provider          *string    `json:"provider"`
	ProviderMessageID *string    `json:"provider_message_id"`
	PossiblyDuplicate bool       `json:"possibly_duplicate"`
	LastErrorCode     *string    `json:"last_error_code"`
	SkipReason        *string    `json:"skip_reason"`
	SentAt            *time.Time `json:"sent_at"`
	DeliveredAt       *time.Time `json:"delivered_at"`
	FinishedAt        *time.Time `json:"finished_at"`
	CreatedAt         time.Time  `json:"created_at"`
}

// RequestStatus is the RequestStatus schema.
type RequestStatus struct {
	RequestID      string           `json:"request_id"`
	Source         string           `json:"source"`
	EventType      string           `json:"event_type"`
	Subject        *string          `json:"subject"`
	Template       *string          `json:"template"`
	Lane           *string          `json:"lane"`
	Decision       string           `json:"decision"`
	DecisionReason *string          `json:"decision_reason"`
	ReceivedAt     time.Time        `json:"received_at"`
	Deliveries     []DeliveryStatus `json:"deliveries"`
}

// DeliveryList is the 200 body of listDeliveriesBySubject.
type DeliveryList struct {
	Deliveries []DeliveryStatus `json:"deliveries"`
}

// FromDelivery maps the entity. next_attempt_at is only meaningful while the
// row is still queued; a final row reports null so a reader is not misled by
// a stale schedule.
func FromDelivery(d entity.Delivery) DeliveryStatus {
	out := DeliveryStatus{
		DeliveryID:        d.ID.String(),
		RequestID:         d.RequestID.String(),
		Lane:              string(d.Lane),
		Channel:           string(d.Channel),
		Template:          d.Template,
		Status:            string(d.Status),
		Attempt:           d.Attempt,
		ProviderMessageID: d.ProviderMessageID,
		PossiblyDuplicate: d.PossiblyDuplicate,
		LastErrorCode:     d.LastErrorCode,
		SkipReason:        d.SkipReason,
		SentAt:            utcPtr(d.SentAt),
		DeliveredAt:       utcPtr(d.DeliveredAt),
		FinishedAt:        utcPtr(d.FinishedAt),
		CreatedAt:         d.CreatedAt.UTC(),
	}
	if !d.Status.IsFinal() && !d.NextAttemptAt.IsZero() {
		t := d.NextAttemptAt.UTC()
		out.NextAttemptAt = &t
	}
	if !d.ExpiresAt.IsZero() {
		t := d.ExpiresAt.UTC()
		out.ExpiresAt = &t
	}
	if d.Provider != nil {
		p := string(*d.Provider)
		out.Provider = &p
	}
	return out
}

// FromDeliveries maps a list; empty is [] not null.
func FromDeliveries(ds []entity.Delivery) []DeliveryStatus {
	out := make([]DeliveryStatus, 0, len(ds))
	for _, d := range ds {
		out = append(out, FromDelivery(d))
	}
	return out
}

// FromRequest maps a request and its deliveries.
func FromRequest(r entity.Request, ds []entity.Delivery) RequestStatus {
	out := RequestStatus{
		RequestID:      r.ID.String(),
		Source:         r.Source,
		EventType:      r.EventType,
		Subject:        r.Subject,
		Template:       r.Template,
		Decision:       string(r.Decision),
		DecisionReason: r.DecisionReason,
		ReceivedAt:     r.ReceivedAt.UTC(),
		Deliveries:     FromDeliveries(ds),
	}
	if r.Lane != nil {
		l := string(*r.Lane)
		out.Lane = &l
	}
	return out
}

// ---- ops -----------------------------------------------------------------

// LaneState is the LaneState schema.
type LaneState struct {
	Lane      string    `json:"lane"`
	Email     string    `json:"email"`
	InApp     string    `json:"in_app"`
	UpdatedAt time.Time `json:"updated_at"`
	UpdatedBy *string   `json:"updated_by"`
}

// FromLaneState maps the entity (reason stays in the audit trail).
func FromLaneState(s entity.LaneState) LaneState {
	return LaneState{
		Lane:      string(s.Lane),
		Email:     string(s.Email),
		InApp:     string(s.InApp),
		UpdatedAt: s.UpdatedAt.UTC(),
		UpdatedBy: s.UpdatedBy,
	}
}

// DlqRedriveAccepted is the 202 body of redriveDlq.
type DlqRedriveAccepted struct {
	OperationID string `json:"operation_id"`
	Matched     int64  `json:"matched"`
}

// Suppression is the Suppression schema: no email_hash, no evidence.
type Suppression struct {
	ID        string     `json:"id"`
	UserID    *string    `json:"user_id"`
	Reason    string     `json:"reason"`
	Source    string     `json:"source"`
	Until     *time.Time `json:"until"`
	CreatedAt time.Time  `json:"created_at"`
}

// FromSuppression maps the entity.
func FromSuppression(s entity.Suppression) Suppression {
	out := Suppression{
		ID:        s.ID.String(),
		Reason:    string(s.Reason),
		Source:    s.Source,
		Until:     utcPtr(s.Until),
		CreatedAt: s.CreatedAt.UTC(),
	}
	if s.UserID != nil {
		u := s.UserID.String()
		out.UserID = &u
	}
	return out
}

// ReplayMode is the ReplayMode schema; durations are rendered back as
// ISO-8601.
type ReplayMode struct {
	Enabled               bool       `json:"enabled"`
	IgnoreEventsOlderThan *string    `json:"ignore_events_older_than,omitempty"`
	TTL                   *string    `json:"ttl,omitempty"`
	ExpiresAt             *time.Time `json:"expires_at"`
}

// FromReplayMode maps the entity; ttl is the remaining whole hours until
// expires_at, computed at now.
func FromReplayMode(m entity.ReplayMode, now time.Time) ReplayMode {
	out := ReplayMode{Enabled: m.Enabled, ExpiresAt: utcPtr(m.ExpiresAt)}
	if !m.Enabled {
		return out
	}
	if m.IgnoreEventsOlderThan > 0 {
		s := ISODuration(m.IgnoreEventsOlderThan)
		out.IgnoreEventsOlderThan = &s
	}
	if m.ExpiresAt != nil {
		hours := int(m.ExpiresAt.Sub(now).Round(time.Hour).Hours())
		if hours < 1 {
			hours = 1
		}
		s := "PT" + strconv.Itoa(hours) + "H"
		out.TTL = &s
	}
	return out
}

// Health is the /livez and /readyz body.
type Health struct {
	Status string   `json:"status"`
	Failed []string `json:"failed,omitempty"`
}

// ISODuration renders a duration in the subset the contract accepts
// (P{d}DT{h}H{m}M).
func ISODuration(d time.Duration) string {
	if d <= 0 {
		return "PT0M"
	}
	days := int(d / (24 * time.Hour))
	d -= time.Duration(days) * 24 * time.Hour
	hours := int(d / time.Hour)
	d -= time.Duration(hours) * time.Hour
	minutes := int(d / time.Minute)

	s := "P"
	if days > 0 {
		s += strconv.Itoa(days) + "D"
	}
	if hours > 0 || minutes > 0 {
		s += "T"
		if hours > 0 {
			s += strconv.Itoa(hours) + "H"
		}
		if minutes > 0 {
			s += strconv.Itoa(minutes) + "M"
		}
	}
	if s == "P" {
		s = "PT0M"
	}
	return s
}

func utcPtr(t *time.Time) *time.Time {
	if t == nil {
		return nil
	}
	u := t.UTC()
	return &u
}
