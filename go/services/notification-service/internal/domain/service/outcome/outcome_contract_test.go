package outcome

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/santhosh-tekuri/jsonschema/v6"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// contractsDir is the repository's contracts/ folder; the schemas $id host
// (https://contracts.ecommerce.local/) maps onto it.
const contractsDir = "../../../../../../../contracts"

const contractsHost = "https://contracts.ecommerce.local/"

// fileLoader serves the repository copy of every https://contracts.ecommerce.local/...
// schema, so $ref to the shared CloudEvent schema resolves offline.
type fileLoader struct{}

func (fileLoader) Load(url string) (any, error) {
	raw, err := os.ReadFile(filepath.Join(contractsDir, strings.TrimPrefix(url, contractsHost)))
	if err != nil {
		return nil, err
	}
	return jsonschema.UnmarshalJSON(bytes.NewReader(raw))
}

func compile(t *testing.T, schemaFile string) *jsonschema.Schema {
	t.Helper()
	c := jsonschema.NewCompiler()
	c.AssertFormat()
	c.UseLoader(jsonschema.SchemeURLLoader{"https": fileLoader{}})
	s, err := c.Compile(contractsHost + "events/notification/v1/schemas/" + schemaFile)
	require.NoError(t, err, schemaFile)
	return s
}

func validate(t *testing.T, s *jsonschema.Schema, raw []byte, what string) {
	t.Helper()
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	require.NoError(t, err, what)
	if err := s.Validate(inst); err != nil {
		t.Fatalf("%s violates its contract schema:\n%v\n%s", what, err, raw)
	}
}

func sampleDelivery(channel entity.Channel, withSubject bool) *entity.Delivery {
	user := uuid.New()
	d := &entity.Delivery{
		ID: uuid.New(), RequestID: uuid.New(), UserID: &user,
		Lane: entity.LaneTransactional, Channel: channel, Template: "ORDER_CONFIRMED", Attempt: 2,
	}
	if withSubject {
		subject := "orders/1000234"
		d.Subject = &subject
	}
	return d
}

// Every event the service writes to the outbox validates against the schema of
// its type (15-verification.md IT-CTR-05): this is the producer half of the
// contract in contracts/events/notification/v1.
func TestOutcomeEventsValidateAgainstContractSchemas(t *testing.T) {
	at := time.Date(2026, 10, 10, 9, 30, 0, 123_000_000, time.UTC)
	rc := Context{CorrelationID: "order-checkout-9d40d5ae", TraceParent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}

	cases := []struct {
		name   string
		schema string
		build  func(d *entity.Delivery, rc Context) (*entity.OutboxEvent, error)
	}{
		{"delivered (email)", "notification-delivered.schema.json", func(d *entity.Delivery, rc Context) (*entity.OutboxEvent, error) {
			return Delivered(d, entity.ProviderSES, at, rc)
		}},
		{"bounced permanent", "notification-bounced.schema.json", func(d *entity.Delivery, rc Context) (*entity.OutboxEvent, error) {
			return Bounced(d, entity.ProviderSES, entity.BouncePermanent, true, at, rc)
		}},
		{"bounced transient", "notification-bounced.schema.json", func(d *entity.Delivery, rc Context) (*entity.OutboxEvent, error) {
			return Bounced(d, entity.ProviderSES, entity.BounceTransient, false, at, rc)
		}},
		{"failed", "notification-failed.schema.json", func(d *entity.Delivery, rc Context) (*entity.OutboxEvent, error) {
			return Failed(d, entity.ProviderSES, entity.StatusFailed, "provider_error", at, rc)
		}},
		{"expired", "notification-failed.schema.json", func(d *entity.Delivery, rc Context) (*entity.OutboxEvent, error) {
			return Failed(d, entity.ProviderMailpit, entity.StatusExpired, "expired", at, rc)
		}},
		{"complaint received", "notification-complaint-received.schema.json", func(d *entity.Delivery, rc Context) (*entity.OutboxEvent, error) {
			return ComplaintReceived(d, entity.ProviderSES, true, at, rc)
		}},
	}
	for _, tc := range cases {
		schema := compile(t, tc.schema)
		for _, withSubject := range []bool{true, false} {
			for _, withContext := range []bool{true, false} {
				d := sampleDelivery(entity.ChannelEmail, withSubject)
				ctx := Context{}
				if withContext {
					ctx = rc
				}
				ev, err := tc.build(d, ctx)
				require.NoError(t, err, tc.name)
				validate(t, schema, ev.Payload, tc.name)
				assert.Equal(t, d.UserID.String(), ev.AggregateID, "the outbox key is the user id (x-message-key data.user_id)")
			}
		}
	}

	// In-app delivery: the inbox is the provider.
	inApp := sampleDelivery(entity.ChannelInApp, true)
	ev, err := Delivered(inApp, entity.ProviderInbox, at, rc)
	require.NoError(t, err)
	validate(t, compile(t, "notification-delivered.schema.json"), ev.Payload, "delivered (in_app)")
}

// The public examples validate too, so a schema change that breaks them is
// caught here and not by a consumer.
func TestNotificationOutcomeExamplesValidate(t *testing.T) {
	dir := filepath.Join(contractsDir, "examples", "events")
	for example, schema := range map[string]string{
		"notification-delivered.v1.json":          "notification-delivered.schema.json",
		"notification-bounced.v1.json":            "notification-bounced.schema.json",
		"notification-failed.v1.json":             "notification-failed.schema.json",
		"notification-complaint-received.v1.json": "notification-complaint-received.schema.json",
	} {
		f, err := os.Open(filepath.Join(dir, example))
		require.NoError(t, err, example)
		raw, err := io.ReadAll(f)
		_ = f.Close()
		require.NoError(t, err)
		var probe map[string]any
		require.NoError(t, json.Unmarshal(raw, &probe), example)
		validate(t, compile(t, schema), raw, example)
	}
}
