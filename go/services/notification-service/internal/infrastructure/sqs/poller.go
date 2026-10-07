package sqs

import (
	"context"
	"encoding/json"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// Client is the slice of the SQS API the poller uses (mockable in tests).
type Client interface {
	ReceiveMessage(ctx context.Context, in *sqs.ReceiveMessageInput, opts ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error)
	DeleteMessage(ctx context.Context, in *sqs.DeleteMessageInput, opts ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error)
}

// NewClient builds the aws-sdk-go-v2 SQS client. When AWSEndpoint is set
// (LocalStack) the endpoint is overridden and static test credentials are
// used; otherwise the default credential chain (IAM role) applies.
func NewClient(ctx context.Context, cfg config.TrackConfig) (*sqs.Client, error) {
	opts := []func(*awsconfig.LoadOptions) error{awsconfig.WithRegion(cfg.AWSRegion)}
	if cfg.AWSEndpoint != "" {
		opts = append(opts, awsconfig.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")))
	}
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return nil, err
	}
	return sqs.NewFromConfig(awsCfg, func(o *sqs.Options) {
		if cfg.AWSEndpoint != "" {
			o.BaseEndpoint = aws.String(cfg.AWSEndpoint)
		}
	}), nil
}

// Poller is steps 1–3 of 03-tracking.md §3: verify, INSERT provider_event,
// DeleteMessage. It never correlates or writes anything but provider_event
// and its dedup ledger.
type Poller struct {
	cfg      config.TrackConfig
	client   Client
	store    port.Store
	verifier *Verifier
	clock    clock.Clock
	metrics  *telemetry.Metrics
	log      *zap.Logger
	// backoff after a receive error, so a broken endpoint does not spin.
	receiveBackoff time.Duration
}

// NewPoller wires the poller. verifier may be disabled (dev only); a warning
// is logged at Run.
func NewPoller(cfg config.TrackConfig, client Client, store port.Store, verifier *Verifier, clk clock.Clock, m *telemetry.Metrics, log *zap.Logger) *Poller {
	if clk == nil {
		clk = clock.System{}
	}
	if log == nil {
		log = zap.NewNop()
	}
	return &Poller{
		cfg:            cfg,
		client:         client,
		store:          store,
		verifier:       verifier,
		clock:          clk,
		metrics:        m,
		log:            log.With(zap.String("component", "sqs_poller")),
		receiveBackoff: 2 * time.Second,
	}
}

// Run long-polls until ctx is cancelled. It returns ctx.Err().
func (p *Poller) Run(ctx context.Context) error {
	if p.verifier == nil || !p.verifier.Enabled() {
		p.log.Warn("SNS signature verification is DISABLED; acceptable in development only (03-tracking.md §6)")
	}
	p.log.Info("sqs poller started", zap.String("queue_url", p.cfg.QueueURL), zap.Int32("batch", p.batchSize()), zap.Duration("wait", p.cfg.WaitTime))
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		n, err := p.PollOnce(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			p.log.Error("sqs receive failed", zap.Error(err))
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(p.receiveBackoff):
			}
			continue
		}
		_ = n // long polling already paces an empty queue.
	}
}

func (p *Poller) batchSize() int32 {
	b := p.cfg.BatchSize
	if b <= 0 {
		b = 10
	}
	if b > 10 {
		b = 10 // SQS maximum
	}
	return b
}

// PollOnce performs one ReceiveMessage and handles every message returned.
// It returns how many messages were received.
func (p *Poller) PollOnce(ctx context.Context) (int, error) {
	wait := int32(p.cfg.WaitTime / time.Second)
	if wait < 0 {
		wait = 0
	}
	if wait > 20 {
		wait = 20
	}
	out, err := p.client.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
		QueueUrl:                    aws.String(p.cfg.QueueURL),
		MaxNumberOfMessages:         p.batchSize(),
		WaitTimeSeconds:             wait,
		MessageSystemAttributeNames: []types.MessageSystemAttributeName{types.MessageSystemAttributeNameSentTimestamp},
	})
	if err != nil {
		return 0, err
	}
	now := p.clock.Now()
	var oldest time.Duration
	for i := range out.Messages {
		msg := &out.Messages[i]
		if age, ok := messageAge(msg, now); ok && age > oldest {
			oldest = age
		}
		p.handle(ctx, msg)
	}
	if p.metrics != nil {
		p.metrics.SQSOldestMessageAge.Set(oldest.Seconds())
	}
	return len(out.Messages), nil
}

func messageAge(msg *types.Message, now time.Time) (time.Duration, bool) {
	raw, ok := msg.Attributes[string(types.MessageSystemAttributeNameSentTimestamp)]
	if !ok {
		return 0, false
	}
	ms, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return 0, false
	}
	age := now.Sub(time.UnixMilli(ms))
	if age < 0 {
		age = 0
	}
	return age, true
}

// handle processes one SQS message. Only a committed INSERT (or a decision
// that the message can never be used) deletes it; a DB failure leaves it for
// the visibility timeout to return it, and after maxReceiveCount SQS parks
// it on the DLQ.
func (p *Poller) handle(ctx context.Context, msg *types.Message) {
	log := p.log.With(zap.String("sqs_message_id", aws.ToString(msg.MessageId)))
	body := aws.ToString(msg.Body)

	var env Envelope
	if err := json.Unmarshal([]byte(body), &env); err != nil {
		log.Error("sqs message is not an SNS envelope; dropping", zap.Error(err))
		p.received("unknown")
		p.observe("unknown", "malformed")
		p.delete(ctx, msg, log)
		return
	}
	log = log.With(zap.String("sns_message_id", env.MessageID))

	switch env.Type {
	case TypeNotification:
	case TypeSubscriptionConfirmation, TypeUnsubscribeConfirmation:
		// Never auto-confirm: subscriptions are created by infrastructure
		// (init-ses-events.sh / Terraform), not by trusting a queue message.
		log.Warn("SNS subscription message received on the queue; not confirming, dropping",
			zap.String("sns_type", env.Type), zap.String("topic_arn", env.TopicArn))
		p.received("unknown")
		p.observe("unknown", "ignored")
		p.delete(ctx, msg, log)
		return
	default:
		log.Warn("unknown SNS message type; dropping", zap.String("sns_type", env.Type))
		p.received("unknown")
		p.observe("unknown", "ignored")
		p.delete(ctx, msg, log)
		return
	}

	if p.verifier != nil {
		if err := p.verifier.Verify(ctx, &env); err != nil {
			// 03-tracking.md §6 item 1: log, delete, never INSERT. The raw
			// message is not logged (may carry addresses).
			log.Error("invalid_signature: SNS signature verification failed; dropping",
				zap.Error(err), zap.String("topic_arn", env.TopicArn))
			p.received("unknown")
			p.observe("unknown", "invalid_signature")
			p.delete(ctx, msg, log)
			return
		}
	}

	ev, err := ParseSESEvent([]byte(env.Message))
	if err != nil {
		log.Error("SES event unparseable; dropping", zap.Error(err))
		p.received("unknown")
		p.observe("unknown", "malformed")
		p.delete(ctx, msg, log)
		return
	}
	p.received(string(ev.Type))
	log = log.With(zap.String("event_type", string(ev.Type)), zap.String("provider_message_id", ev.ProviderMessageID))

	now := p.clock.Now()
	row := &entity.ProviderEvent{
		Provider:          entity.ProviderSES,
		ProviderEventID:   ev.ProviderEventID,
		ProviderMessageID: aws.String(ev.ProviderMessageID),
		EventType:         ev.Type,
		OccurredAt:        ev.OccurredAt,
		Raw:               ev.Sanitized,
		Status:            entity.ProviderEventPending,
		Attempt:           0,
		NextAttemptAt:     now,
		ReceivedAt:        now,
	}
	duplicate := false
	err = p.store.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		fresh, err := tx.Dedup().ClaimProviderEvent(ctx, entity.ProviderSES, ev.ProviderEventID)
		if err != nil {
			return err
		}
		if !fresh {
			duplicate = true
			return nil
		}
		return tx.ProviderEvents().Insert(ctx, row)
	})
	if err != nil {
		// Leave the message: visibility timeout returns it; DLQ after 5.
		log.Error("provider_event insert failed; leaving message on the queue", zap.Error(err))
		return
	}
	if duplicate {
		log.Debug("duplicate provider event; dropping")
		p.observe(string(ev.Type), "duplicate")
	} else {
		log.Debug("provider event recorded", zap.Int64("provider_event_id", row.ID))
	}
	p.delete(ctx, msg, log)
}

func (p *Poller) delete(ctx context.Context, msg *types.Message, log *zap.Logger) {
	_, err := p.client.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(p.cfg.QueueURL),
		ReceiptHandle: msg.ReceiptHandle,
	})
	if err != nil {
		// The row is committed (or the message was junk); a redelivery hits
		// the dedup ledger and is dropped again. Log only.
		log.Warn("sqs DeleteMessage failed; dedup ledger will absorb the redelivery", zap.Error(err))
	}
}

func (p *Poller) received(eventType string) {
	if p.metrics != nil {
		p.metrics.SQSMessagesReceived.WithLabelValues(eventType).Inc()
	}
}

func (p *Poller) observe(eventType, outcome string) {
	if p.metrics != nil {
		p.metrics.ProviderEventProcessed.WithLabelValues(eventType, outcome).Inc()
	}
}
