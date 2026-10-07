// Package event_test checks the wire shape of the messages against
// contracts/events/user/v1 and contracts/events/auth/v1. A drift here is a
// contract break, not an internal detail.
package event_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/user-service/internal/domain/event"
)

var testNow = time.Date(2026, 8, 28, 8, 0, 0, 0, time.UTC)

func TestProfileUpdatedMatchesTheContractEnvelope(t *testing.T) {
	userID := uuid.MustParse("0198e098-96f2-7ad3-91d8-570608021328")

	envelope := event.NewProfileUpdated(userID, "Nguyen Van A", testNow, event.Metadata{})

	var decoded map[string]any
	payload, err := json.Marshal(envelope)
	require.NoError(t, err)
	require.NoError(t, json.Unmarshal(payload, &decoded))

	require.Equal(t, "1.0", decoded["specversion"])
	require.Equal(t, "com.ecommerce.user.profile-updated.v1", decoded["type"])
	require.Equal(t, "/services/user", decoded["source"])
	require.Equal(t, "users/"+userID.String(), decoded["subject"])
	require.Equal(t, "application/json", decoded["datacontenttype"])
	require.Equal(t,
		"/contracts/events/user/v1/schemas/user-profile-updated.schema.json",
		decoded["dataschema"])
	require.Equal(t, "2026-08-28T08:00:00Z", decoded["time"])
	// Without a request correlation id the schema still needs a UUID, so the
	// user id stands in for it.
	require.Equal(t, userID.String(), decoded["correlationid"])

	data, ok := decoded["data"].(map[string]any)
	require.True(t, ok)
	require.Equal(t, []string{"sender_name", "updated_at", "user_id"}, sortedKeys(data),
		"the payload carries nothing beyond the three contract fields")
	require.Equal(t, "Nguyen Van A", data["sender_name"])
}

func TestPreferencesUpdatedCarriesOnlyTheReplicatedFlags(t *testing.T) {
	userID := uuid.New()

	envelope := event.NewPreferencesUpdated(userID, true, true, false, testNow, event.Metadata{})

	payload, err := json.Marshal(envelope)
	require.NoError(t, err)

	var decoded struct {
		Type string         `json:"type"`
		Data map[string]any `json:"data"`
	}
	require.NoError(t, json.Unmarshal(payload, &decoded))
	require.Equal(t, "com.ecommerce.user.preferences-updated.v1", decoded.Type)
	require.Equal(t,
		[]string{"email_enabled", "in_app_enabled", "marketing_enabled", "updated_at", "user_id"},
		sortedKeys(decoded.Data),
		"language and currency are fixed by the contract and are never published")
}

func TestEnvelopeKeyIsTheUserID(t *testing.T) {
	userID := uuid.New()

	envelope := event.NewProfileUpdated(userID, "Nguyen Van A", testNow, event.Metadata{})

	// The key is routing metadata: it decides the partition, which is what makes
	// per-user ordering the only ordering the contract promises.
	require.Equal(t, userID.String(), envelope.Key)
	payload, err := json.Marshal(envelope)
	require.NoError(t, err)
	require.NotContains(t, string(payload), `"Key"`)
}

func TestEnvelopeKeepsAUUIDCorrelationID(t *testing.T) {
	correlationID := uuid.NewString()

	envelope := event.NewProfileUpdated(uuid.New(), "Nguyen Van A", testNow, event.Metadata{
		CorrelationID: correlationID,
		TraceParent:   "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
	})

	require.Equal(t, correlationID, envelope.CorrelationID)
	require.NotEmpty(t, envelope.TraceParent)
}

func TestEveryEnvelopeGetsItsOwnID(t *testing.T) {
	userID := uuid.New()

	first := event.NewProfileUpdated(userID, "Nguyen Van A", testNow, event.Metadata{})
	second := event.NewProfileUpdated(userID, "Nguyen Van A", testNow, event.Metadata{})

	// A reconciliation snapshot is a new statement of current state, not a
	// redelivery, so it must not reuse an id (ADR-007).
	require.NotEqual(t, first.ID, second.ID)
}

func TestParseUserVerifiedSuccess(t *testing.T) {
	raw := []byte(`{
		"specversion": "1.0",
		"id": "0198e098-97e4-7d66-8a2c-997d792ae826",
		"type": "com.ecommerce.auth.user-verified-success.v1",
		"source": "/services/auth",
		"subject": "accounts/0198e098-96f2-7ad3-91d8-570608021328",
		"time": "2026-08-27T12:00:00Z",
		"datacontenttype": "application/json",
		"dataschema": "/contracts/events/auth/v1/schemas/user-verified-success.schema.json",
		"correlationid": "0198e098-96f2-7ad3-91d8-570608021328",
		"data": {
			"user_id": "0198e098-96f2-7ad3-91d8-570608021328",
			"roles": ["USER"],
			"verified_at": "2026-08-27T12:00:00Z"
		}
	}`)

	envelope, err := event.ParseAuthEnvelope(raw)
	require.NoError(t, err)
	require.Equal(t, event.TypeAuthUserVerifiedSuccess, envelope.Type)

	payload, err := event.ParseUserVerifiedSuccess(envelope.Data)
	require.NoError(t, err)
	require.Equal(t, "0198e098-96f2-7ad3-91d8-570608021328", payload.UserID.String())
	require.Equal(t, time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC), payload.VerifiedAt)
}

func TestParseUserDeleted(t *testing.T) {
	raw := []byte(`{
		"specversion": "1.0",
		"id": "0198e099-97e4-7d66-8a2c-997d792ae826",
		"type": "com.ecommerce.auth.user-deleted.v1",
		"source": "/services/auth",
		"subject": "accounts/0198e098-96f2-7ad3-91d8-570608021328",
		"time": "2026-08-27T12:30:00Z",
		"datacontenttype": "application/json",
		"dataschema": "/contracts/events/auth/v1/schemas/user-deleted.schema.json",
		"data": {
			"user_id": "0198e098-96f2-7ad3-91d8-570608021328",
			"deleted_at": "2026-08-27T12:30:00Z",
			"reason": "GDPR_REQUEST",
			"hard_delete_after": "2026-11-25T12:30:00Z"
		}
	}`)

	envelope, err := event.ParseAuthEnvelope(raw)
	require.NoError(t, err)

	payload, err := event.ParseUserDeleted(envelope.Data)
	require.NoError(t, err)
	require.Equal(t, "GDPR_REQUEST", payload.Reason)
	require.Equal(t, time.Date(2026, 11, 25, 12, 30, 0, 0, time.UTC), payload.HardDeleteAfter,
		"the deadline is taken from Auth, never recomputed")
}

func TestConsumerToleratesAnUnknownOptionalField(t *testing.T) {
	raw := []byte(`{
		"specversion": "1.0",
		"id": "0198e099-97e4-7d66-8a2c-997d792ae826",
		"type": "com.ecommerce.auth.user-deleted.v1",
		"source": "/services/auth",
		"subject": "accounts/0198e098-96f2-7ad3-91d8-570608021328",
		"time": "2026-08-27T12:30:00Z",
		"datacontenttype": "application/json",
		"dataschema": "/contracts/events/auth/v1/schemas/user-deleted.schema.json",
		"sequence": 12,
		"data": {
			"user_id": "0198e098-96f2-7ad3-91d8-570608021328",
			"deleted_at": "2026-08-27T12:30:00Z",
			"reason": "GDPR_REQUEST",
			"hard_delete_after": "2026-11-25T12:30:00Z",
			"requested_by": "support-agent-7"
		}
	}`)

	envelope, err := event.ParseAuthEnvelope(raw)
	require.NoError(t, err)

	// Adding an optional field is a compatible change, so it must not break the
	// consumer (contracts/conventions/events.md).
	payload, err := event.ParseUserDeleted(envelope.Data)
	require.NoError(t, err)
	require.Equal(t, "GDPR_REQUEST", payload.Reason)
}

func TestParseRejectsAForeignOrMalformedMessage(t *testing.T) {
	_, err := event.ParseAuthEnvelope([]byte(`not json`))
	require.Error(t, err)

	_, err = event.ParseAuthEnvelope([]byte(`{"specversion":"0.3","id":"x","type":"t","source":"/services/auth"}`))
	require.Error(t, err, "an unsupported CloudEvents version is refused")

	_, err = event.ParseAuthEnvelope([]byte(`{"specversion":"1.0","id":"x","type":"t","source":"/services/cart"}`))
	require.Error(t, err, "only Auth may produce on this topic")
}

func sortedKeys(m map[string]any) []string {
	keys := make([]string, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	for i := 1; i < len(keys); i++ {
		for j := i; j > 0 && keys[j] < keys[j-1]; j-- {
			keys[j], keys[j-1] = keys[j-1], keys[j]
		}
	}
	return keys
}
