package ses

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// fakeSES is an httptest server that answers SendEmail with a canned SESv2
// response: {"MessageId": ...} on success, or an error type + message.
type fakeSES struct {
	srv *httptest.Server

	mu       sync.Mutex
	status   int
	errType  string
	errMsg   string
	hang     time.Duration
	requests []map[string]any
}

func newFakeSES(t *testing.T) *fakeSES {
	t.Helper()
	f := &fakeSES{status: http.StatusOK}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var req map[string]any
		_ = json.Unmarshal(body, &req)
		f.mu.Lock()
		f.requests = append(f.requests, req)
		status, errType, errMsg, hang := f.status, f.errType, f.errMsg, f.hang
		f.mu.Unlock()

		assert.Equal(t, "/v2/email/outbound-emails", r.URL.Path)
		if hang > 0 {
			time.Sleep(hang)
		}
		w.Header().Set("Content-Type", "application/json")
		if status == http.StatusOK {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"MessageId":"0100018c-ses-message-id"}`))
			return
		}
		if errType != "" {
			w.Header().Set("X-Amzn-Errortype", errType)
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"message":"` + errMsg + `"}`))
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeSES) fail(status int, errType, msg string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.status, f.errType, f.errMsg = status, errType, msg
}

func (f *fakeSES) lastRequest(t *testing.T) map[string]any {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	require.NotEmpty(t, f.requests)
	return f.requests[len(f.requests)-1]
}

func testConfig() config.EmailConfig {
	return config.EmailConfig{
		Provider:       "ses",
		FromAddress:    "no-reply@ecommerce.local",
		FromName:       "E-Commerce",
		ConnectTimeout: 500 * time.Millisecond,
		SendTimeout:    500 * time.Millisecond,
		SES: config.SESConfig{
			Region:                                "ap-southeast-1",
			ConfigurationSetCriticalTransactional: "notification-critical-transactional",
			ConfigurationSetBulk:                  "notification-bulk",
			MaxConnsPerLane:                       2,
		},
	}
}

func newSender(t *testing.T, f *fakeSES, m *telemetry.Metrics) *Sender {
	t.Helper()
	client := sesv2.New(sesv2.Options{
		Region:       "ap-southeast-1",
		Credentials:  credentials.NewStaticCredentialsProvider("test", "test", ""),
		BaseEndpoint: aws.String(f.srv.URL),
		Retryer:      aws.NopRetryer{},
	})
	return NewSenderWithClient(client, testConfig(), clock.NewFake(time.Unix(1_700_000_000, 0)), m, nil)
}

func sampleMessage(lane entity.Lane) *entity.EmailMessage {
	return &entity.EmailMessage{
		DeliveryID: uuid.MustParse("0f6e5d4c-3b2a-4190-8877-665544332211"),
		Lane:       lane,
		To:         pii.MustEmail("khach@example.com"),
		Content: entity.EmailContent{
			Subject: "Đơn hàng #1000234 đã xác nhận",
			HTML:    "<p>Cảm ơn bạn</p>",
			Text:    "Cảm ơn bạn",
			Headers: map[string]string{
				"List-Unsubscribe":           "<http://localhost:3000/profile/preferences>",
				"X-Notification-Delivery-Id": "must-not-duplicate",
				"X-Bad":                      "line\r\nbreak",
			},
		},
	}
}

func TestSender_SendSuccess(t *testing.T) {
	f := newFakeSES(t)
	s := newSender(t, f, telemetry.New())

	res, err := s.Send(context.Background(), sampleMessage(entity.LaneBulk))
	require.NoError(t, err)
	assert.Equal(t, entity.ProviderSES, res.Provider)
	assert.Equal(t, "0100018c-ses-message-id", res.MessageID)
	assert.Equal(t, entity.ProviderSES, s.Provider())

	req := f.lastRequest(t)
	assert.Equal(t, "E-Commerce <no-reply@ecommerce.local>", req["FromEmailAddress"])
	assert.Equal(t, "notification-bulk", req["ConfigurationSetName"])
	assert.Nil(t, req["ListManagementOptions"])
	dest := req["Destination"].(map[string]any)
	assert.Equal(t, []any{"khach@example.com"}, dest["ToAddresses"])

	tags := req["EmailTags"].([]any)
	got := map[string]string{}
	for _, tg := range tags {
		m := tg.(map[string]any)
		got[m["Name"].(string)] = m["Value"].(string)
	}
	assert.Equal(t, map[string]string{"delivery_id": "0f6e5d4c-3b2a-4190-8877-665544332211", "lane": "bulk"}, got)

	simple := req["Content"].(map[string]any)["Simple"].(map[string]any)
	subject := simple["Subject"].(map[string]any)
	assert.Equal(t, "Đơn hàng #1000234 đã xác nhận", subject["Data"])
	assert.Equal(t, "UTF-8", subject["Charset"])
	body := simple["Body"].(map[string]any)
	assert.Equal(t, "<p>Cảm ơn bạn</p>", body["Html"].(map[string]any)["Data"])
	assert.Equal(t, "Cảm ơn bạn", body["Text"].(map[string]any)["Data"])

	headers := map[string]string{}
	for _, h := range simple["Headers"].([]any) {
		m := h.(map[string]any)
		assert.NotContains(t, headers, m["Name"].(string), "duplicate header")
		headers[m["Name"].(string)] = m["Value"].(string)
	}
	assert.Equal(t, map[string]string{
		"X-Notification-Delivery-Id": "0f6e5d4c-3b2a-4190-8877-665544332211",
		"List-Unsubscribe":           "<http://localhost:3000/profile/preferences>",
	}, headers, "renderer's delivery-id header replaced, CRLF header dropped")
}

func TestSender_ConfigurationSetPerLane(t *testing.T) {
	f := newFakeSES(t)
	s := newSender(t, f, nil)
	for lane, want := range map[entity.Lane]string{
		entity.LaneCritical:      "notification-critical-transactional",
		entity.LaneTransactional: "notification-critical-transactional",
		entity.LaneBulk:          "notification-bulk",
	} {
		_, err := s.Send(context.Background(), sampleMessage(lane))
		require.NoError(t, err)
		assert.Equal(t, want, f.lastRequest(t)["ConfigurationSetName"], lane)
	}
}

func TestSender_ErrorClassification(t *testing.T) {
	cases := []struct {
		name      string
		status    int
		errType   string
		msg       string
		retryable bool
		code      string
	}{
		{"TooManyRequests → retryable", 429, "TooManyRequestsException", "Too many requests", true, ""},
		{"Throttling → retryable", 400, "Throttling", "Maximum sending rate exceeded.", true, ""},
		{"LimitExceeded → retryable", 400, "LimitExceededException", "Daily message quota exceeded", true, ""},
		{"SendingPaused → retryable", 400, "SendingPausedException", "Sending paused for account", true, ""},
		{"500 InternalFailure → retryable", 500, "InternalFailure", "boom", true, ""},
		{"503 no type → retryable", 503, "", "unavailable", true, ""},
		{"MessageRejected → provider_rejected", 400, "MessageRejected", "Email address is not verified.", false, "provider_rejected"},
		{"MailFromDomainNotVerified → provider_rejected", 400, "MailFromDomainNotVerifiedException", "not verified", false, "provider_rejected"},
		{"AccountSuspended → provider_rejected", 400, "AccountSuspendedException", "suspended", false, "provider_rejected"},
		{"BadRequest on address → invalid_recipient", 400, "BadRequestException", "Invalid email address <khach@example>", false, "invalid_recipient"},
		{"BadRequest other → provider_rejected", 400, "BadRequestException", "Header name contains invalid characters", false, "provider_rejected"},
		{"unknown 403 → provider_rejected", 403, "", "forbidden", false, "provider_rejected"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeSES(t)
			f.fail(tc.status, tc.errType, tc.msg)
			s := newSender(t, f, telemetry.New())
			_, err := s.Send(context.Background(), sampleMessage(entity.LaneTransactional))
			require.Error(t, err)
			assert.Equal(t, tc.retryable, apperr.IsRetryable(err), "err=%v", err)
			code, permanent := apperr.PermanentCode(err)
			assert.Equal(t, !tc.retryable, permanent, "err=%v", err)
			assert.Equal(t, tc.code, code)
		})
	}
}

func TestSender_TimeoutAndConnectionRefusedAreRetryable(t *testing.T) {
	f := newFakeSES(t)
	f.mu.Lock()
	f.hang = 2 * time.Second
	f.mu.Unlock()
	s := newSender(t, f, nil)
	_, err := s.Send(context.Background(), sampleMessage(entity.LaneCritical))
	require.True(t, apperr.IsRetryable(err), "timeout err=%v", err)

	f.srv.Close()
	_, err = s.Send(context.Background(), sampleMessage(entity.LaneCritical))
	require.True(t, apperr.IsRetryable(err), "refused err=%v", err)
}

func TestSender_BulkheadPerLane(t *testing.T) {
	f := newFakeSES(t)
	f.mu.Lock()
	f.hang = 300 * time.Millisecond
	f.mu.Unlock()
	s := newSender(t, f, nil) // MaxConnsPerLane = 2

	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = s.Send(context.Background(), sampleMessage(entity.LaneBulk))
		}()
	}
	time.Sleep(100 * time.Millisecond)
	assert.Len(t, s.sem[entity.LaneBulk], 2)

	short, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, err := s.Send(short, sampleMessage(entity.LaneBulk))
	assert.True(t, apperr.IsRetryable(err), "err=%v", err)
	assert.Len(t, s.sem[entity.LaneCritical], 0, "critical bulkhead untouched")
	wg.Wait()
	assert.Len(t, s.sem[entity.LaneBulk], 0)
}

func TestHelpers(t *testing.T) {
	assert.Equal(t, "abc-123_x", tagValue("abc-123_x"))
	assert.Equal(t, "CART_ABANDONED_v1", tagValue("CART ABANDONED.v1"))
	assert.Equal(t, "Shop <a@b>", formatFrom(`"Shop"`, "a@b"))
	assert.Equal(t, "Shop x <a@b>", formatFrom(`Shop <x>`, "a@b"))
	assert.Equal(t, "a@b", formatFrom("", "a@b"))
	assert.True(t, validHeader("List-Unsubscribe", "<https://x>"))
	assert.False(t, validHeader("Bad:Name", "v"))
	assert.False(t, validHeader("X", "line\nbreak"))
	assert.True(t, mentionsAddress("Invalid email address"))
	assert.False(t, mentionsAddress("Header is too long"))
}

// healthSES answers GetAccount (GET /v2/email/account) with a canned body.
func healthSES(t *testing.T, status int, body string) *Sender {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/v2/email/account", r.URL.Path)
		assert.Equal(t, http.MethodGet, r.Method)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	client := sesv2.New(sesv2.Options{
		Region:       "ap-southeast-1",
		Credentials:  credentials.NewStaticCredentialsProvider("test", "test", ""),
		BaseEndpoint: aws.String(srv.URL),
		Retryer:      aws.NopRetryer{},
	})
	return NewSenderWithClient(client, testConfig(), clock.NewFake(time.Unix(1_700_000_000, 0)), telemetry.New(), nil)
}

func TestSender_HealthCheck(t *testing.T) {
	t.Run("sending enabled", func(t *testing.T) {
		require.NoError(t, healthSES(t, http.StatusOK, `{"SendingEnabled":true}`).HealthCheck(context.Background()))
	})
	t.Run("sending disabled is unhealthy", func(t *testing.T) {
		err := healthSES(t, http.StatusOK, `{"SendingEnabled":false}`).HealthCheck(context.Background())
		require.ErrorContains(t, err, "sending is disabled")
	})
	t.Run("API error is unhealthy", func(t *testing.T) {
		require.Error(t, healthSES(t, http.StatusServiceUnavailable, `{"message":"down"}`).HealthCheck(context.Background()))
	})
}
