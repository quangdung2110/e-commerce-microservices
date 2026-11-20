package kafka

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/service"
)

const (
	authTopic     = "t.auth.events"
	authDLQ       = "t.auth.events.dlq"
	outcomesTopic = "t.notification.outcomes.v1"
	outcomesDLQ   = "t.notification.outcomes.v1.dlq"
)

type fakeLifecycle struct {
	mu        sync.Mutex
	bootstrap []uuid.UUID
}

func (f *fakeLifecycle) Bootstrap(_ context.Context, userID uuid.UUID, _ time.Time) (service.BootstrapResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.bootstrap = append(f.bootstrap, userID)
	return service.BootstrapResult{ProfileCreated: true}, nil
}

func (f *fakeLifecycle) Anonymize(context.Context, uuid.UUID, time.Time, time.Time, string) (service.AnonymizationResult, error) {
	return service.AnonymizationResult{}, nil
}

type fakeComplaints struct {
	mu       sync.Mutex
	users    []uuid.UUID
	failures []error // consumed one per call, then success
}

func (f *fakeComplaints) DisableMarketing(_ context.Context, userID uuid.UUID) (service.MarketingOptOutResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.users = append(f.users, userID)
	if len(f.failures) > 0 {
		err := f.failures[0]
		f.failures = f.failures[1:]
		return service.MarketingOptOutResult{}, err
	}
	return service.MarketingOptOutResult{Disabled: true}, nil
}

type deadLetter struct {
	topic  string
	reason string
}

type fakeDLQ struct {
	mu      sync.Mutex
	parked  []deadLetter
	failure error
}

func (f *fakeDLQ) PublishDeadLetter(_ context.Context, dlqTopic string, _, _ []byte, reason string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failure != nil {
		return f.failure
	}
	f.parked = append(f.parked, deadLetter{topic: dlqTopic, reason: reason})
	return nil
}

func newTestHandler(complaints *fakeComplaints, dlq *fakeDLQ) (*eventHandler, *fakeLifecycle) {
	lifecycle := &fakeLifecycle{}
	return &eventHandler{
		lifecycle:  lifecycle,
		complaints: complaints,
		dlq:        dlq,
		topics: topicRoutes{
			auth: authTopic, authDLQ: authDLQ,
			notification: outcomesTopic, notifDLQ: outcomesDLQ,
		},
		maxAttempts: 3,
		minBackoff:  time.Millisecond,
		maxBackoff:  2 * time.Millisecond,
		logger:      zap.NewNop(),
	}, lifecycle
}

func complaintMessage(userID string) *sarama.ConsumerMessage {
	return outcomeMessage("com.ecommerce.notification.complaint-received.v1", userID)
}

func outcomeMessage(eventType, userID string) *sarama.ConsumerMessage {
	return &sarama.ConsumerMessage{
		Topic: outcomesTopic,
		Value: []byte(`{
			"specversion": "1.0",
			"id": "0198e09a-97e4-7d66-8a2c-997d792ae826",
			"type": "` + eventType + `",
			"source": "/services/notification",
			"data": {"user_id": "` + userID + `", "marketing_disabled": true}
		}`),
	}
}

func TestComplaintOnTheOutcomesTopicDisablesMarketing(t *testing.T) {
	complaints, dlq := &fakeComplaints{}, &fakeDLQ{}
	handler, _ := newTestHandler(complaints, dlq)
	userID := uuid.New()

	require.NoError(t, handler.handle(context.Background(), complaintMessage(userID.String())))

	require.Equal(t, []uuid.UUID{userID}, complaints.users)
}

func TestOtherOutcomesAreAcknowledgedWithoutAction(t *testing.T) {
	complaints, dlq := &fakeComplaints{}, &fakeDLQ{}
	handler, _ := newTestHandler(complaints, dlq)

	for _, eventType := range []string{
		"com.ecommerce.notification.delivered.v1",
		"com.ecommerce.notification.bounced.v1",
		"com.ecommerce.notification.failed.v1",
	} {
		// The payload would even be invalid for a complaint; it is never decoded.
		require.NoError(t, handler.handle(context.Background(), outcomeMessage(eventType, "not-a-uuid")), eventType)
	}

	require.Empty(t, complaints.users)
}

func TestAComplaintCarriedOnTheAuthTopicIsNotActedOn(t *testing.T) {
	complaints, dlq := &fakeComplaints{}, &fakeDLQ{}
	handler, _ := newTestHandler(complaints, dlq)

	message := complaintMessage(uuid.NewString())
	message.Topic = authTopic

	// Routing is by topic: the Auth parser refuses a Notification-sourced event.
	require.Error(t, handler.handle(context.Background(), message))
	require.Empty(t, complaints.users)
}

func TestMalformedComplaintIsDeadLetteredToItsOwnTopicWithoutRetry(t *testing.T) {
	complaints, dlq := &fakeComplaints{}, &fakeDLQ{}
	handler, _ := newTestHandler(complaints, dlq)

	require.NoError(t, handler.handleWithRetry(context.Background(), complaintMessage("not-a-uuid")))

	require.Empty(t, complaints.users, "an undecodable payload never reaches the domain")
	require.Equal(t, []deadLetter{{topic: outcomesDLQ, reason: apperr.CodeValidationFailed}}, dlq.parked,
		"complaints are parked apart from Auth events")
}

func TestAMalformedAuthEventStillGoesToTheAuthDLQ(t *testing.T) {
	complaints, dlq := &fakeComplaints{}, &fakeDLQ{}
	handler, _ := newTestHandler(complaints, dlq)

	require.NoError(t, handler.handleWithRetry(context.Background(),
		&sarama.ConsumerMessage{Topic: authTopic, Value: []byte("not json")}))

	require.Equal(t, []deadLetter{{topic: authDLQ, reason: apperr.CodeValidationFailed}}, dlq.parked)
}

func TestATransientFailureIsRetriedUntilTheComplaintIsApplied(t *testing.T) {
	complaints := &fakeComplaints{failures: []error{apperr.ErrDependencyUnavailable, apperr.ErrDependencyUnavailable}}
	dlq := &fakeDLQ{}
	handler, _ := newTestHandler(complaints, dlq)

	require.NoError(t, handler.handleWithRetry(context.Background(), complaintMessage(uuid.NewString())))

	require.Len(t, complaints.users, 3, "two failures, then success on the last allowed attempt")
	require.Empty(t, dlq.parked, "nothing is parked when a retry succeeds")
}

func TestExhaustedRetriesParkTheComplaint(t *testing.T) {
	complaints := &fakeComplaints{failures: []error{
		apperr.ErrDependencyUnavailable, apperr.ErrDependencyUnavailable, apperr.ErrDependencyUnavailable,
	}}
	dlq := &fakeDLQ{}
	handler, _ := newTestHandler(complaints, dlq)

	require.NoError(t, handler.handleWithRetry(context.Background(), complaintMessage(uuid.NewString())))

	require.Len(t, complaints.users, 3)
	require.Equal(t, []deadLetter{{topic: outcomesDLQ, reason: apperr.CodeDependencyUnavailable}}, dlq.parked)
}

func TestAnAuthEventIsStillRoutedToTheLifecycle(t *testing.T) {
	complaints, dlq := &fakeComplaints{}, &fakeDLQ{}
	handler, lifecycle := newTestHandler(complaints, dlq)
	userID := uuid.New()

	message := &sarama.ConsumerMessage{
		Topic: authTopic,
		Value: []byte(`{
			"specversion": "1.0",
			"id": "0198e099-97e4-7d66-8a2c-997d792ae826",
			"type": "com.ecommerce.auth.user-verified-success.v1",
			"source": "/services/auth",
			"data": {"user_id": "` + userID.String() + `", "verified_at": "2026-08-27T12:00:00Z"}
		}`),
	}

	require.NoError(t, handler.handle(context.Background(), message))

	require.Equal(t, []uuid.UUID{userID}, lifecycle.bootstrap)
	require.Empty(t, complaints.users)
}
