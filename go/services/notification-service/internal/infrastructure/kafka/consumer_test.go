package kafka

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/infrastructure/cloudevents"
)

func TestFailureAction(t *testing.T) {
	boom := errors.New("pg down")
	cases := []struct {
		name    string
		err     error
		attempt int
		max     int
		ctxErr  error
		want    Action
		reason  string
	}{
		{"session ending → abort", boom, 1, 5, context.Canceled, ActionAbort, ""},
		{"invalid envelope → DLQ at once", fmt.Errorf("wrap: %w", cloudevents.ErrInvalidEnvelope), 1, 5, nil, ActionDeadLetter, ReasonInvalidEnvelope},
		{"permanent → DLQ with its code", apperr.NewPermanent("invalid_payload", boom), 1, 5, nil, ActionDeadLetter, "invalid_payload"},
		{"infra error first attempt → retry", boom, 1, 5, nil, ActionRetry, ""},
		{"infra error attempt 4 of 5 → retry", boom, 4, 5, nil, ActionRetry, ""},
		{"infra error attempt 5 of 5 → DLQ", boom, 5, 5, nil, ActionDeadLetter, ReasonMaxAttempts},
		{"retryable classified error still capped", apperr.NewRetryable(boom), 5, 5, nil, ActionDeadLetter, ReasonMaxAttempts},
		{"max attempts 0 means one attempt", boom, 1, 0, nil, ActionDeadLetter, ReasonMaxAttempts},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, reason := FailureAction(tc.err, tc.attempt, tc.max, tc.ctxErr)
			if got != tc.want || reason != tc.reason {
				t.Fatalf("got %v/%q want %v/%q", got, reason, tc.want, tc.reason)
			}
		})
	}
}

func TestBackoff(t *testing.T) {
	min, max := 200*time.Millisecond, 5*time.Second
	if got := Backoff(1, min, max); got != 200*time.Millisecond {
		t.Fatalf("attempt 1 = %v", got)
	}
	if got := Backoff(3, min, max); got != 800*time.Millisecond {
		t.Fatalf("attempt 3 = %v", got)
	}
	if got := Backoff(10, min, max); got != max {
		t.Fatalf("attempt 10 = %v", got)
	}
	if got := Backoff(1, 0, 0); got != 200*time.Millisecond {
		t.Fatalf("default min = %v", got)
	}
}

func TestCopyHeaders(t *testing.T) {
	msg := &sarama.ConsumerMessage{Headers: []*sarama.RecordHeader{
		{Key: []byte("ce_id"), Value: []byte("1")}, nil, {Key: []byte("traceparent"), Value: []byte("00-a-b-01")},
	}}
	h := CopyHeaders(msg)
	if len(h) != 2 || h["ce_id"] != "1" || h["traceparent"] != "00-a-b-01" {
		t.Fatalf("headers %v", h)
	}
}

// fakeDLQ records dead letters.
type fakeDLQ struct {
	topic   string
	headers map[string]string
	reason  string
	calls   int
}

func (f *fakeDLQ) PublishDeadLetter(_ context.Context, topic string, _, _ []byte, headers map[string]string, reason string) error {
	f.calls++
	f.topic, f.headers, f.reason = topic, headers, reason
	return nil
}

func TestProcessWithRetry(t *testing.T) {
	msg := &sarama.ConsumerMessage{Topic: "t.src", Partition: 1, Offset: 7, Headers: []*sarama.RecordHeader{{Key: []byte("ce_id"), Value: []byte("x")}}}

	t.Run("retries then succeeds, no DLQ", func(t *testing.T) {
		dlq := &fakeDLQ{}
		calls := 0
		h := &groupHandler{src: srcCfg(), dlq: dlq, maxAttempts: 3, minBackoff: time.Millisecond, maxBackoff: time.Millisecond,
			handle: func(context.Context, *sarama.ConsumerMessage) error {
				calls++
				if calls < 3 {
					return errors.New("transient")
				}
				return nil
			}}
		h.logger = nopLogger()
		if err := h.processWithRetry(context.Background(), msg); err != nil {
			t.Fatal(err)
		}
		if calls != 3 || dlq.calls != 0 {
			t.Fatalf("calls=%d dlq=%d", calls, dlq.calls)
		}
	})
	t.Run("exhausted → DLQ with headers copied and reason", func(t *testing.T) {
		dlq := &fakeDLQ{}
		h := &groupHandler{src: srcCfg(), dlq: dlq, maxAttempts: 2, minBackoff: time.Millisecond, maxBackoff: time.Millisecond,
			handle: func(context.Context, *sarama.ConsumerMessage) error { return errors.New("pg down") }}
		h.logger = nopLogger()
		if err := h.processWithRetry(context.Background(), msg); err != nil {
			t.Fatal(err)
		}
		if dlq.calls != 1 || dlq.topic != "t.src.dlq" || dlq.reason != ReasonMaxAttempts || dlq.headers["ce_id"] != "x" || dlq.headers[HeaderDLQTopic] != "t.src" {
			t.Fatalf("dlq %+v", dlq)
		}
	})
	t.Run("invalid envelope → DLQ on first attempt", func(t *testing.T) {
		dlq := &fakeDLQ{}
		calls := 0
		h := &groupHandler{src: srcCfg(), dlq: dlq, maxAttempts: 5,
			handle: func(context.Context, *sarama.ConsumerMessage) error {
				calls++
				return fmt.Errorf("%w: bad", cloudevents.ErrInvalidEnvelope)
			}}
		h.logger = nopLogger()
		_ = h.processWithRetry(context.Background(), msg)
		if calls != 1 || dlq.reason != ReasonInvalidEnvelope {
			t.Fatalf("calls=%d reason=%s", calls, dlq.reason)
		}
	})
	t.Run("cancelled session → abort without DLQ", func(t *testing.T) {
		dlq := &fakeDLQ{}
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		h := &groupHandler{src: srcCfg(), dlq: dlq, maxAttempts: 5,
			handle: func(context.Context, *sarama.ConsumerMessage) error { return errors.New("x") }}
		h.logger = nopLogger()
		if err := h.processWithRetry(ctx, msg); err == nil {
			t.Fatal("expected abort error")
		}
		if dlq.calls != 0 {
			t.Fatal("must not dead-letter on shutdown")
		}
	})
}

func srcCfg() config.KafkaSourceConfig {
	return config.KafkaSourceConfig{Enabled: true, Topic: "t.src", Group: "g", DLQ: "t.src.dlq"}
}

func nopLogger() *zap.Logger { return zap.NewNop() }
