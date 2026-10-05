package sqs

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

func readSample(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	require.NoError(t, err)
	return b
}

// rawAddresses lists every address present in the samples; none may survive.
var rawAddresses = []string{
	"recipient@example.com", "no-reply@ecommerce.local", "nobody@example.com",
	"full.mailbox@example.org", "angry.user@example.net",
}

func assertNoAddress(t *testing.T, sanitized []byte) {
	t.Helper()
	s := string(sanitized)
	for _, a := range rawAddresses {
		require.NotContains(t, s, a)
		require.NotContains(t, strings.ToLower(s), strings.ToLower(a))
	}
	require.False(t, ContainsEmail(s), "sanitised output still matches the email regex: %s", s)
	require.False(t, regexp.MustCompile(`[^"\s]+@[^"\s]+`).MatchString(s), "an @ survived: %s", s)
}

func TestParseSESEvent_Samples(t *testing.T) {
	ts := func(s string) *time.Time {
		v, err := time.Parse(time.RFC3339Nano, s)
		require.NoError(t, err)
		v = v.UTC()
		return &v
	}
	cases := []struct {
		file       string
		typ        entity.ProviderEventType
		rawType    string
		messageID  string
		occurredAt *time.Time
		deliveryID string
		bounceType entity.BounceType
		recipients []string
	}{
		{"send.json", entity.ProviderEventSend, "Send", "0100019996a1b2c3-1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d-000000",
			ts("2026-09-22T07:47:30.000Z"), "4f6c1d2e-8a9b-4c3d-9e0f-1a2b3c4d5e6f", "", []string{"recipient@example.com"}},
		{"delivery.json", entity.ProviderEventDelivery, "Delivery", "0100019996a1b2c3-1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d-000000",
			ts("2026-09-22T07:47:34.800Z"), "4f6c1d2e-8a9b-4c3d-9e0f-1a2b3c4d5e6f", "", []string{"recipient@example.com"}},
		{"bounce-permanent.json", entity.ProviderEventBounce, "Bounce", "0100019996a1b2c3-2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e-000000",
			ts("2026-09-22T07:47:36.100Z"), "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", entity.BouncePermanent, []string{"nobody@example.com"}},
		{"bounce-transient.json", entity.ProviderEventBounce, "Bounce", "0100019996a1b2c3-3c4d5e6f-7a8b-9c0d-1e2f-3a4b5c6d7e8f-000000",
			ts("2026-09-22T07:50:00.000Z"), "9c0d1e2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f", entity.BounceTransient, []string{"full.mailbox@example.org"}},
		{"complaint.json", entity.ProviderEventComplaint, "Complaint", "0100019996a1b2c3-4d5e6f7a-8b9c-0d1e-2f3a-4b5c6d7e8f9a-000000",
			ts("2026-09-22T09:12:08.000Z"), "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e", "", []string{"angry.user@example.net"}},
	}
	for _, tc := range cases {
		t.Run(tc.file, func(t *testing.T) {
			raw := readSample(t, tc.file)
			ev, err := ParseSESEvent(raw)
			require.NoError(t, err)
			require.Equal(t, tc.typ, ev.Type)
			require.Equal(t, tc.rawType, ev.RawType)
			require.Equal(t, tc.messageID, ev.ProviderMessageID)
			require.Equal(t, tc.occurredAt, ev.OccurredAt)
			require.NotNil(t, ev.DeliveryID)
			require.Equal(t, tc.deliveryID, ev.DeliveryID.String())
			require.Equal(t, tc.bounceType, ev.BounceType)
			var wantHashes []string
			for _, r := range tc.recipients {
				wantHashes = append(wantHashes, pii.HashEmail(r))
			}
			require.Equal(t, wantHashes, ev.RecipientHashes)
			require.Len(t, ev.ProviderEventID, 64)

			assertNoAddress(t, ev.Sanitized)
			require.True(t, json.Valid(ev.Sanitized))

			// Stable ids: parsing the same bytes twice yields the same values.
			again, err := ParseSESEvent(raw)
			require.NoError(t, err)
			require.Equal(t, ev.ProviderEventID, again.ProviderEventID)
			require.JSONEq(t, string(ev.Sanitized), string(again.Sanitized))
		})
	}
}

func TestParseSESEvent_IDsDifferPerEvent(t *testing.T) {
	// Send and Delivery of the same mail share messageId but not the event id.
	send, err := ParseSESEvent(readSample(t, "send.json"))
	require.NoError(t, err)
	del, err := ParseSESEvent(readSample(t, "delivery.json"))
	require.NoError(t, err)
	require.Equal(t, send.ProviderMessageID, del.ProviderMessageID)
	require.NotEqual(t, send.ProviderEventID, del.ProviderEventID)

	// Case of eventType does not change the id.
	require.Equal(t, ProviderEventID("m", "Bounce", "t1", "t2"), ProviderEventID("m", "bounce", "t1", "t2"))
	require.NotEqual(t, ProviderEventID("m", "bounce", "t1", "t2"), ProviderEventID("m", "bounce", "t1", "t3"))
}

func TestSanitize_HashedValuesMatchContactHash(t *testing.T) {
	ev, err := ParseSESEvent(readSample(t, "bounce-permanent.json"))
	require.NoError(t, err)
	var doc struct {
		Bounce struct {
			BouncedRecipients []struct {
				EmailAddress   string `json:"emailAddress"`
				DiagnosticCode string `json:"diagnosticCode"`
			} `json:"bouncedRecipients"`
		} `json:"bounce"`
		Mail struct {
			Destination []string `json:"destination"`
			Source      string   `json:"source"`
			Headers     []struct {
				Name  string `json:"name"`
				Value string `json:"value"`
			} `json:"headers"`
		} `json:"mail"`
	}
	require.NoError(t, json.Unmarshal(ev.Sanitized, &doc))
	h := pii.HashEmail("nobody@example.com")
	require.Equal(t, []string{h}, doc.Mail.Destination)
	// "Name <addr>" keeps the name, replaces the address.
	require.Equal(t, "Nguyen Van A <"+h+">", doc.Bounce.BouncedRecipients[0].EmailAddress)
	// Addresses embedded in a diagnostic string are hashed in place.
	require.Contains(t, doc.Bounce.BouncedRecipients[0].DiagnosticCode, "<"+h+">")
	require.Equal(t, pii.HashEmail("no-reply@ecommerce.local"), doc.Mail.Source)
	// Non-address strings survive untouched.
	require.Equal(t, "Có người trả lời đánh giá của bạn", doc.Mail.Headers[2].Value)
	// The recipient hash used for suppression is the address hash, not the
	// hash of the display form.
	require.Equal(t, []string{h}, ev.RecipientHashes)
}

func TestNormalizeEventType(t *testing.T) {
	cases := map[string]entity.ProviderEventType{
		"Send": entity.ProviderEventSend, "send": entity.ProviderEventSend, "DELIVERY": entity.ProviderEventDelivery,
		"Bounce": entity.ProviderEventBounce, "Complaint": entity.ProviderEventComplaint, "Reject": entity.ProviderEventReject,
		"DeliveryDelay": entity.ProviderEventDeliveryDelay, "Open": entity.ProviderEventOther, "Click": entity.ProviderEventOther,
		"": entity.ProviderEventOther,
	}
	for in, want := range cases {
		require.Equal(t, want, NormalizeEventType(in), in)
	}
}

func TestParseSESEvent_Errors(t *testing.T) {
	_, err := ParseSESEvent([]byte(`not json`))
	require.Error(t, err)
	_, err = ParseSESEvent([]byte(`{"mail":{"messageId":"x"}}`))
	require.Error(t, err, "missing eventType")
	_, err = ParseSESEvent([]byte(`{"eventType":"Send","mail":{}}`))
	require.Error(t, err, "missing messageId")

	// Identity-notification format (notificationType) is accepted; missing
	// timestamps leave OccurredAt nil; Undetermined bounces are transient.
	ev, err := ParseSESEvent([]byte(`{"notificationType":"Bounce","mail":{"messageId":"m1","destination":["a@b.co"]},"bounce":{"bounceType":"Undetermined"}}`))
	require.NoError(t, err)
	require.Equal(t, entity.ProviderEventBounce, ev.Type)
	require.Nil(t, ev.OccurredAt)
	require.Nil(t, ev.DeliveryID)
	require.Equal(t, entity.BounceTransient, ev.BounceType)
	require.Equal(t, []string{pii.HashEmail("a@b.co")}, ev.RecipientHashes)
	assertNoAddress(t, ev.Sanitized)
}
