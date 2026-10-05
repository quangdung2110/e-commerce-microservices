// Package ses is the production email adapter (D-09): Amazon SES v2
// SendEmail with the per-lane configuration set of 03-tracking.md §5.3 so the
// Track path can tell which lane an event belongs to.
package ses

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"
	"github.com/aws/aws-sdk-go-v2/service/sesv2/types"
	"github.com/aws/smithy-go"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var _ port.EmailSender = (*Sender)(nil)

const (
	codeInvalidRecipient = "invalid_recipient"
	codeProviderRejected = "provider_rejected"

	dependencyName = "ses"
	bulkheadName   = "ses_conn"
	charsetUTF8    = "UTF-8"

	tagDeliveryID = "delivery_id"
	tagLane       = "lane"
	// SES limits a tag value to 256 characters of [A-Za-z0-9_-].
	maxTagLen = 256
)

// API is the slice of the SESv2 client the adapter needs (tests inject a
// fake through NewSenderWithClient's parameter type).
type API interface {
	SendEmail(ctx context.Context, params *sesv2.SendEmailInput, optFns ...func(*sesv2.Options)) (*sesv2.SendEmailOutput, error)
}

// Sender sends through SESv2 with a per-lane bulkhead (06-backpressure.md
// §3). SDK retries are disabled: the delivery worker owns retry (backoff,
// retry budget, breaker), and a hidden SDK retry would double-send under a
// timeout.
type Sender struct {
	client  API
	cfg     config.EmailConfig
	from    string
	clock   clock.Clock
	metrics *telemetry.Metrics
	logger  *zap.Logger
	sem     map[entity.Lane]chan struct{}
}

// NewSender builds the SESv2 client from the default AWS credential chain,
// region email.ses.region and, when email.ses.endpoint is set (LocalStack),
// that endpoint with static test credentials if the chain has none.
func NewSender(ctx context.Context, cfg config.EmailConfig, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) (*Sender, error) {
	opts := []func(*awsconfig.LoadOptions) error{awsconfig.WithRegion(cfg.SES.Region)}
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return nil, fmt.Errorf("ses: load aws config: %w", err)
	}
	if cfg.SES.Endpoint != "" {
		if _, err := awsCfg.Credentials.Retrieve(ctx); err != nil {
			// LocalStack accepts anything; production never sets an endpoint.
			awsCfg.Credentials = credentials.NewStaticCredentialsProvider("test", "test", "")
		}
	}
	client := sesv2.NewFromConfig(awsCfg, func(o *sesv2.Options) {
		if cfg.SES.Endpoint != "" {
			o.BaseEndpoint = aws.String(cfg.SES.Endpoint)
		}
		o.Retryer = aws.NopRetryer{}
		o.HTTPClient = awshttp.NewBuildableClient().
			WithDialerOptions(func(d *net.Dialer) { d.Timeout = cfg.ConnectTimeout }).
			WithTimeout(cfg.SendTimeout)
	})
	return NewSenderWithClient(client, cfg, clk, metrics, logger), nil
}

// NewSenderWithClient wires the adapter around an existing client (tests,
// or a caller that shares one SES client). metrics and logger may be nil.
func NewSenderWithClient(client API, cfg config.EmailConfig, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) *Sender {
	if clk == nil {
		clk = clock.System{}
	}
	if logger == nil {
		logger = zap.NewNop()
	}
	size := cfg.SES.MaxConnsPerLane
	if size < 1 {
		size = 1
	}
	s := &Sender{
		client:  client,
		cfg:     cfg,
		from:    formatFrom(cfg.FromName, cfg.FromAddress),
		clock:   clk,
		metrics: metrics,
		logger:  logger,
		sem:     map[entity.Lane]chan struct{}{},
	}
	for _, lane := range entity.AllLanes {
		s.sem[lane] = make(chan struct{}, size)
		if metrics != nil {
			metrics.BulkheadCapacity.WithLabelValues(string(lane), string(entity.ChannelEmail), bulkheadName).Set(float64(size))
		}
	}
	return s
}

// Provider reports ses.
func (s *Sender) Provider() entity.Provider { return entity.ProviderSES }

// Send calls SendEmail with Simple content. The SES MessageId becomes
// SendResult.MessageID and is what Track correlates events on
// (03-tracking.md §5.1).
func (s *Sender) Send(ctx context.Context, msg *entity.EmailMessage) (*entity.SendResult, error) {
	if msg == nil || msg.To.IsZero() {
		return nil, apperr.NewPermanent(codeInvalidRecipient, errors.New("ses: empty recipient"))
	}
	release, err := s.acquire(ctx, msg.Lane)
	if err != nil {
		return nil, apperr.NewRetryable(err)
	}
	defer release()

	input := s.buildInput(msg)
	if s.cfg.SendTimeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, s.cfg.SendTimeout)
		defer cancel()
	}

	start := s.clock.Now()
	out, err := s.client.SendEmail(ctx, input)
	if err != nil {
		classified := classify(err)
		s.observe(msg.Lane, start, classified)
		s.logger.Debug("ses send failed",
			zap.String("delivery_id", msg.DeliveryID.String()),
			zap.String("lane", string(msg.Lane)),
			zap.Error(classified))
		return nil, classified
	}
	s.observe(msg.Lane, start, nil)
	return &entity.SendResult{Provider: entity.ProviderSES, MessageID: aws.ToString(out.MessageId)}, nil
}

// buildInput assembles the request: Simple content (subject/html/text), the
// lane's configuration set, message tags for the event payload and the
// renderer's headers (List-Unsubscribe on bulk) plus X-Notification-Delivery-Id.
// ListManagementOptions is deliberately unused: unsubscribe is owned by the
// User Service preference page, not by SES contact lists.
func (s *Sender) buildInput(msg *entity.EmailMessage) *sesv2.SendEmailInput {
	body := &types.Body{}
	if msg.Content.Text != "" {
		body.Text = &types.Content{Data: aws.String(msg.Content.Text), Charset: aws.String(charsetUTF8)}
	}
	if msg.Content.HTML != "" {
		body.Html = &types.Content{Data: aws.String(msg.Content.HTML), Charset: aws.String(charsetUTF8)}
	}

	headers := []types.MessageHeader{{
		Name:  aws.String(constants.HeaderDeliveryID),
		Value: aws.String(msg.DeliveryID.String()),
	}}
	for name, value := range msg.Content.Headers {
		if strings.EqualFold(name, constants.HeaderDeliveryID) || !validHeader(name, value) {
			continue
		}
		headers = append(headers, types.MessageHeader{Name: aws.String(name), Value: aws.String(value)})
	}

	return &sesv2.SendEmailInput{
		FromEmailAddress:     aws.String(s.from),
		Destination:          &types.Destination{ToAddresses: []string{msg.To.Raw()}},
		ConfigurationSetName: aws.String(s.configurationSet(msg.Lane)),
		EmailTags: []types.MessageTag{
			{Name: aws.String(tagDeliveryID), Value: aws.String(tagValue(msg.DeliveryID.String()))},
			{Name: aws.String(tagLane), Value: aws.String(tagValue(string(msg.Lane)))},
		},
		Content: &types.EmailContent{
			Simple: &types.Message{
				Subject: &types.Content{Data: aws.String(msg.Content.Subject), Charset: aws.String(charsetUTF8)},
				Body:    body,
				Headers: headers,
			},
		},
	}
}

// configurationSet picks the bulk set for bulk and the critical/transactional
// set otherwise (03-tracking.md §5.3: two sets, same SNS topic).
func (s *Sender) configurationSet(lane entity.Lane) string {
	if lane == entity.LaneBulk {
		return s.cfg.SES.ConfigurationSetBulk
	}
	return s.cfg.SES.ConfigurationSetCriticalTransactional
}

func (s *Sender) acquire(ctx context.Context, lane entity.Lane) (func(), error) {
	sem, ok := s.sem[lane]
	if !ok {
		sem = s.sem[entity.LaneTransactional]
	}
	select {
	case sem <- struct{}{}:
	case <-ctx.Done():
		return nil, fmt.Errorf("ses: bulkhead wait: %w", ctx.Err())
	}
	if s.metrics != nil {
		s.metrics.BulkheadInUse.WithLabelValues(string(lane), string(entity.ChannelEmail), bulkheadName).Inc()
	}
	return func() {
		<-sem
		if s.metrics != nil {
			s.metrics.BulkheadInUse.WithLabelValues(string(lane), string(entity.ChannelEmail), bulkheadName).Dec()
		}
	}, nil
}

func (s *Sender) observe(lane entity.Lane, start time.Time, err error) {
	if s.metrics == nil {
		return
	}
	status := "ok"
	switch {
	case err == nil:
	case apperr.IsRetryable(err):
		status = "retryable"
	default:
		status = "permanent"
	}
	s.metrics.DependencyDuration.WithLabelValues(dependencyName, string(lane), status).Observe(s.clock.Now().Sub(start).Seconds())
}

// classify maps SESv2 failures to the delivery-path vocabulary:
//   - throttling, quota, 5xx, network, timeout → retryable
//   - MessageRejected, MailFromDomainNotVerified, AccountSuspended → permanent provider_rejected
//   - BadRequest about the address → permanent invalid_recipient, other
//     BadRequest → permanent provider_rejected (the request will not change).
func classify(err error) error {
	var (
		suspended   *types.AccountSuspendedException
		mailFrom    *types.MailFromDomainNotVerifiedException
		rejected    *types.MessageRejected
		badRequest  *types.BadRequestException
		tooMany     *types.TooManyRequestsException
		limit       *types.LimitExceededException
		paused      *types.SendingPausedException
		internalErr *types.InternalServiceErrorException
		responseErr *awshttp.ResponseError
		apiErr      smithy.APIError
	)
	switch {
	case errors.As(err, &suspended), errors.As(err, &mailFrom), errors.As(err, &rejected):
		return apperr.NewPermanent(codeProviderRejected, err)
	case errors.As(err, &badRequest):
		if mentionsAddress(badRequest.ErrorMessage()) {
			return apperr.NewPermanent(codeInvalidRecipient, err)
		}
		return apperr.NewPermanent(codeProviderRejected, err)
	case errors.As(err, &tooMany), errors.As(err, &limit), errors.As(err, &paused), errors.As(err, &internalErr):
		// SendingPaused is an account-level switch an operator flips back;
		// the message itself is fine.
		return apperr.NewRetryable(err)
	}
	if errors.As(err, &apiErr) {
		switch apiErr.ErrorCode() {
		case "Throttling", "ThrottlingException", "TooManyRequestsException", "RequestTimeout",
			"ServiceUnavailable", "InternalFailure", "InternalServiceError", "RequestThrottled":
			return apperr.NewRetryable(err)
		}
	}
	if errors.As(err, &responseErr) {
		code := responseErr.HTTPStatusCode()
		if code >= 500 || code == http.StatusTooManyRequests || code == http.StatusRequestTimeout {
			return apperr.NewRetryable(err)
		}
		if code >= 400 {
			// A 4xx the SDK could not name: the request is wrong, not the network.
			return apperr.NewPermanent(codeProviderRejected, err)
		}
	}
	// Dial failure, deadline, reset, unknown: another attempt may succeed.
	return apperr.NewRetryable(err)
}

func mentionsAddress(message string) bool {
	m := strings.ToLower(message)
	for _, needle := range []string{"address", "recipient", "destination", "email"} {
		if strings.Contains(m, needle) {
			return true
		}
	}
	return false
}

// formatFrom renders "Name <addr>"; SES accepts UTF-8 display names in
// SendEmail v2. Quotes and angle brackets in the name would break parsing.
func formatFrom(name, address string) string {
	name = strings.NewReplacer(`"`, "", "<", "", ">", "", "\r", "", "\n", "").Replace(strings.TrimSpace(name))
	if name == "" {
		return address
	}
	return name + " <" + address + ">"
}

// tagValue makes a value legal for SES message tags: only [A-Za-z0-9_-],
// at most 256 characters.
func tagValue(v string) string {
	var b strings.Builder
	for _, r := range v {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '_', r == '-':
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
		if b.Len() >= maxTagLen {
			break
		}
	}
	return b.String()
}

// validHeader applies the SES MessageHeader constraints: name printable
// ASCII without ':' (≤ 126), value printable ASCII (≤ 995), no CR/LF.
func validHeader(name, value string) bool {
	if name == "" || len(name) > 126 || len(value) > 995 {
		return false
	}
	for i := 0; i < len(name); i++ {
		if c := name[i]; c < 33 || c > 126 || c == ':' {
			return false
		}
	}
	for i := 0; i < len(value); i++ {
		if c := value[i]; c < 32 || c > 126 {
			return false
		}
	}
	return true
}
