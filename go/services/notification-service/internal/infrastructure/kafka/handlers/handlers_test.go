package handlers_test

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/decide"
	"github.com/ecommerce/notification-service/internal/domain/service/ingest"
	"github.com/ecommerce/notification-service/internal/domain/service/lifecycle"
	"github.com/ecommerce/notification-service/internal/domain/service/preference"
	"github.com/ecommerce/notification-service/internal/infrastructure/cloudevents"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka/handlers"
)

const examplesDir = "../../../../../../contracts/examples/events"

// Contract test (15-verification.md §1): every consumer accepts the public
// examples of its source and produces the IngestInput Decide expects. The
// examples are the same files producers validate against, so a drift shows
// up here before it shows up in a DLQ.

type captureIngest struct {
	inputs []entity.IngestInput
}

func (c *captureIngest) Ingest(_ context.Context, in entity.IngestInput) (ingest.Outcome, error) {
	c.inputs = append(c.inputs, in)
	return ingest.Outcome{RequestID: uuid.New(), Decision: entity.DecisionDecided}, nil
}

type capturePrefs struct{ updates []preference.Update }

func (c *capturePrefs) Apply(_ context.Context, u preference.Update) (bool, error) {
	c.updates = append(c.updates, u)
	return true, nil
}

type captureLifecycle struct{ deleted []uuid.UUID }

func (c *captureLifecycle) HandleUserDeleted(_ context.Context, id uuid.UUID) (lifecycle.Result, error) {
	c.deleted = append(c.deleted, id)
	return lifecycle.Result{}, nil
}

type fixtures struct {
	t       *testing.T
	deps    handlers.HandlerDeps
	ingest  *captureIngest
	prefs   *capturePrefs
	life    *captureLifecycle
	catalog *catalog.Catalog
}

func newFixtures(t *testing.T) *fixtures {
	t.Helper()
	cat, err := catalog.Load()
	require.NoError(t, err)
	f := &fixtures{t: t, ingest: &captureIngest{}, prefs: &capturePrefs{}, life: &captureLifecycle{}, catalog: cat}
	f.deps = handlers.HandlerDeps{
		Ingest: f.ingest, Preferences: f.prefs, Lifecycle: f.life, Catalog: cat,
		Frontend: config.FrontendConfig{BaseURL: "http://localhost:3000", CartPath: "/cart"},
		Clock:    clock.NewFake(clock.System{}.Now()),
	}
	return f
}

func (f *fixtures) message(path string) *sarama.ConsumerMessage {
	raw, err := os.ReadFile(path)
	require.NoError(f.t, err, path)
	env, err := cloudevents.Parse(raw)
	require.NoError(f.t, err, path)
	return &sarama.ConsumerMessage{Topic: "t", Key: []byte(env.Subject), Value: raw}
}

func (f *fixtures) example(name string) *sarama.ConsumerMessage {
	path := filepath.Join(examplesDir, name)
	if _, err := os.Stat(path); err != nil {
		f.t.Skipf("contracts examples not available: %v", err)
	}
	return f.message(path)
}

func (f *fixtures) local(name string) *sarama.ConsumerMessage {
	return f.message(filepath.Join("testdata", name))
}

func (f *fixtures) last() entity.IngestInput {
	require.NotEmpty(f.t, f.ingest.inputs, "handler produced no IngestInput")
	return f.ingest.inputs[len(f.ingest.inputs)-1]
}

func TestOrderRequestExample(t *testing.T) {
	f := newFixtures(t)
	require.NoError(t, handlers.Request(f.deps, entity.LaneTransactional)(context.Background(), f.example("notification-requested.v1.json")))
	in := f.last()
	assert.Equal(t, entity.InputRequest, in.InputKind)
	assert.Equal(t, "/services/order", in.Source)
	assert.Equal(t, "5b1c0f2e-2f0a-4d0e-9c1a-1a2b3c4d5e6f", in.EventID)
	assert.Equal(t, "orders/1000234", in.Subject)
	assert.Equal(t, entity.LaneTransactional, in.Lane)
	assert.Equal(t, "ORDER_CONFIRMED", in.Template)
	assert.Equal(t, "0f6d3a8e-6a4e-4d0d-9c3b-2b1e6f7a8c9d", in.UserID)
	assert.Equal(t, "order_confirmed:1000234", in.DedupKey)
	require.NotNil(t, in.ExpiresAt)
	assert.Equal(t, "order-checkout-9d40d5ae-c636-4d55-bf7d-bad6d388bd7f", in.CorrelationID)
	require.NotNil(t, in.Sequence)
	assert.EqualValues(t, 3, *in.Sequence)
	assert.Len(t, in.PayloadSHA256, 64, "Order requests carry the canonical payload digest")
	// The template_data passes the catalog schema untouched.
	assert.NoError(t, f.catalog.ValidateTemplateData(in.Template, in.TemplateData))

	// Same payload on the bulk topic is still ingested (Decide records lane_mismatch).
	require.NoError(t, handlers.Request(f.deps, entity.LaneBulk)(context.Background(), f.example("notification-requested.v1.json")))
	assert.Equal(t, entity.LaneBulk, f.last().Lane)
}

func TestRequestWithoutTemplateStillIngests(t *testing.T) {
	f := newFixtures(t)
	raw := []byte(`{"specversion":"1.0","id":"e1","type":"com.ecommerce.notification.requested.v1","source":"/services/order","subject":"orders/1","time":"2026-09-20T10:15:30Z","datacontenttype":"application/json","dataschema":"x","data":{"user_id":"0f6d3a8e-6a4e-4d0d-9c3b-2b1e6f7a8c9d","template_data":{}}}`)
	require.NoError(t, handlers.Request(f.deps, entity.LaneTransactional)(context.Background(), &sarama.ConsumerMessage{Value: raw}))
	assert.Equal(t, "", f.last().Template, "missing template is a producer contract error handled by Decide, not a DLQ")

	err := handlers.Request(f.deps, entity.LaneTransactional)(context.Background(), &sarama.ConsumerMessage{Value: []byte(`{"not":"cloudevents"}`)})
	assert.ErrorIs(t, err, cloudevents.ErrInvalidEnvelope)
}

func TestCartAbandonedExample(t *testing.T) {
	f := newFixtures(t)
	require.NoError(t, handlers.Cart(f.deps)(context.Background(), f.example("cart-abandoned.v1.json")))
	in := f.last()
	assert.Equal(t, entity.InputFact, in.InputKind)
	assert.Equal(t, entity.LaneBulk, in.Lane)
	assert.Equal(t, "CART_ABANDONED", in.Template)
	assert.Equal(t, "0198e098-94f4-7f8f-9e16-ae42dfbd93c1", in.UserID)
	assert.Equal(t, "cart_abandoned:c1:MDk4ZTA5OGI5NGY0N2Y4Zg:1", in.DedupKey)
	assert.Equal(t, "cart-outbox:v2:cart:v2:{b17}:outbox:1780000000000-0", in.EventID)
	var td map[string]any
	require.NoError(t, json.Unmarshal(in.TemplateData, &td))
	assert.Equal(t, "http://localhost:3000/cart", td["cart_url"])
	assert.NoError(t, f.catalog.ValidateTemplateData(in.Template, in.TemplateData))
}

func TestAuthFixtures(t *testing.T) {
	f := newFixtures(t)
	cases := []struct {
		file, template, dedupPrefix string
		secret                      bool
	}{
		{"auth-user-otp-requested.v1.json", "EMAIL_CHANGE_VERIFICATION", "", true},
		{"auth-password-reset-requested.v1.json", "PASSWORD_RESET", "", true},
		{"auth-user-email-changed.v1.json", "EMAIL_CHANGED", "email_changed:", false},
		{"auth-user-password-changed.v1.json", "PASSWORD_CHANGED", "password_changed:", false},
		{"auth-user-account-locked.v1.json", "ACCOUNT_LOCKED", "account_locked:", false},
		{"auth-user-unlocked.v1.json", "ACCOUNT_UNLOCKED", "account_unlocked:", false},
	}
	for _, tc := range cases {
		t.Run(tc.file, func(t *testing.T) {
			require.NoError(t, handlers.Auth(f.deps)(context.Background(), f.local(tc.file)))
			in := f.last()
			assert.Equal(t, entity.LaneCritical, in.Lane)
			assert.Equal(t, tc.template, in.Template)
			assert.Equal(t, "0198e098-96f2-7ad3-91d8-570608021328", in.UserID)
			assert.NotEmpty(t, in.SourceRef, "Auth delivery_id must travel as source_ref")
			if tc.secret {
				assert.Nil(t, in.TemplateData, "secret templates never carry template_data")
				assert.Empty(t, in.DedupKey, "every resend is a new delivery")
				assert.NotNil(t, in.ExpiresAt)
			} else {
				assert.Equal(t, tc.dedupPrefix+in.SourceRef, in.DedupKey)
				assert.NoError(t, f.catalog.ValidateTemplateData(in.Template, in.TemplateData))
			}
		})
	}
	// user-deleted on the notification topic would be a producer mistake: ACK, no effect.
	before := len(f.ingest.inputs)
	require.NoError(t, handlers.Auth(f.deps)(context.Background(), f.example("auth-user-deleted.v1.json")))
	assert.Len(t, f.ingest.inputs, before)
}

func TestReviewFixtures(t *testing.T) {
	f := newFixtures(t)
	require.NoError(t, handlers.Review(f.deps)(context.Background(), f.local("review-reply-notification-requested.v1.json")))
	in := f.last()
	assert.Equal(t, "REVIEW_REPLIED", in.Template)
	assert.Equal(t, entity.LaneTransactional, in.Lane)
	assert.Contains(t, in.DedupKey, "review_replied:")
	var td map[string]string
	require.NoError(t, json.Unmarshal(in.TemplateData, &td))
	assert.NotEmpty(t, in.UserID, "recipient_user_id → user_id")
	assert.NotEmpty(t, td["review_id"])
	assert.Equal(t, "http://localhost:3000/reviews/"+td["review_id"], td["review_url"])
	assert.NoError(t, f.catalog.ValidateTemplateData(in.Template, in.TemplateData))

	require.NoError(t, handlers.Review(f.deps)(context.Background(), f.local("review-removal-notification-requested.v1.json")))
	in = f.last()
	assert.Equal(t, "REVIEW_REMOVED", in.Template)
	assert.NoError(t, f.catalog.ValidateTemplateData(in.Template, in.TemplateData))
}

func TestPreferencesAndLifecycle(t *testing.T) {
	f := newFixtures(t)
	require.NoError(t, handlers.Preferences(f.deps)(context.Background(), f.local("user-preferences-updated.v1.json")))
	require.Len(t, f.prefs.updates, 1)
	u := f.prefs.updates[0]
	assert.Equal(t, uuid.MustParse("0198e098-96f2-7ad3-91d8-570608021328"), u.UserID)
	assert.True(t, u.EmailEnabled)
	assert.False(t, u.MarketingEnabled)
	assert.False(t, u.UpdatedAt.IsZero())

	require.NoError(t, handlers.UserLifecycle(f.deps)(context.Background(), f.example("auth-user-deleted.v1.json")))
	require.Len(t, f.life.deleted, 1)
	assert.Equal(t, uuid.MustParse("0198e098-96f2-7ad3-91d8-570608021328"), f.life.deleted[0])

	// Unrelated types on the same topics are acknowledged without effect.
	before := len(f.life.deleted)
	require.NoError(t, handlers.UserLifecycle(f.deps)(context.Background(), f.local("user-preferences-updated.v1.json")))
	assert.Len(t, f.life.deleted, before)
	assert.Empty(t, f.ingest.inputs)
}

// Every notification-* example in contracts must at least be a valid
// CloudEvent for this service's parser (outcomes are what we publish).
func TestAllNotificationExamplesParse(t *testing.T) {
	matches, _ := filepath.Glob(filepath.Join(examplesDir, "notification-*.json"))
	if len(matches) == 0 {
		t.Skip("contracts examples not available")
	}
	for _, m := range matches {
		raw, err := os.ReadFile(m)
		require.NoError(t, err)
		_, err = cloudevents.Parse(raw)
		assert.NoError(t, err, m)
	}
	_ = kafka.HeaderCEID
	_ = decide.Policy{}
}
