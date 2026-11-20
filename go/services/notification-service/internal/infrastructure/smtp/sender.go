// Package smtp is the email adapter for Mailpit in dev/CI (D-09,
// 13-development.md §2): it builds a multipart/alternative MIME message and
// hands it to a plain SMTP server through net/smtp. Production uses the ses
// package; both implement port.EmailSender.
package smtp

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/smtp"
	"net/textproto"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

var _ port.EmailSender = (*Sender)(nil)

const (
	// Reason codes recorded in last_error_code for permanent failures.
	codeInvalidRecipient = "invalid_recipient"
	codeProviderRejected = "provider_rejected"

	dependencyName = "smtp"
	bulkheadName   = "smtp_conn"
	heloName       = "notification-service"
)

// Sender sends through one SMTP server with a per-lane connection bulkhead
// (06-backpressure.md §3): a bulk drain cannot take every connection a
// critical OTP needs.
type Sender struct {
	cfg     config.EmailConfig
	clock   clock.Clock
	metrics *telemetry.Metrics
	logger  *zap.Logger
	sem     map[entity.Lane]chan struct{}
}

// NewSender wires the adapter. metrics and logger may be nil.
func NewSender(cfg config.EmailConfig, clk clock.Clock, metrics *telemetry.Metrics, logger *zap.Logger) *Sender {
	if clk == nil {
		clk = clock.System{}
	}
	if logger == nil {
		logger = zap.NewNop()
	}
	size := cfg.SMTP.MaxConnsPerLane
	if size < 1 {
		size = 1
	}
	s := &Sender{cfg: cfg, clock: clk, metrics: metrics, logger: logger, sem: map[entity.Lane]chan struct{}{}}
	for _, lane := range entity.AllLanes {
		s.sem[lane] = make(chan struct{}, size)
		if metrics != nil {
			metrics.BulkheadCapacity.WithLabelValues(string(lane), string(entity.ChannelEmail), bulkheadName).Set(float64(size))
		}
	}
	return s
}

// Provider reports mailpit: the SMTP path exists for Mailpit only.
func (s *Sender) Provider() entity.Provider { return entity.ProviderMailpit }

// Send builds the message and runs the SMTP dialogue under
// email.connect_timeout / email.send_timeout. Errors are classified:
// dial, timeout and 4xx replies are retryable; 550/551/553 at RCPT are
// permanent invalid_recipient; any other 5xx is permanent provider_rejected.
func (s *Sender) Send(ctx context.Context, msg *entity.EmailMessage) (*entity.SendResult, error) {
	if msg == nil || msg.To.IsZero() {
		return nil, apperr.NewPermanent(codeInvalidRecipient, errors.New("smtp: empty recipient"))
	}
	release, err := s.acquire(ctx, msg.Lane)
	if err != nil {
		return nil, apperr.NewRetryable(err)
	}
	defer release()

	messageID := MessageID(msg.DeliveryID, s.cfg.FromAddress)
	body, err := BuildMIME(msg, s.cfg.FromName, s.cfg.FromAddress, messageID, s.clock.Now())
	if err != nil {
		// A message that cannot be encoded will not encode next time either.
		return nil, apperr.NewPermanent(entity.ReasonRenderFailed, err)
	}

	start := s.clock.Now()
	sendErr := s.deliver(ctx, msg.To.Raw(), body)
	s.observe(msg.Lane, start, sendErr)
	if sendErr != nil {
		s.logger.Debug("smtp send failed",
			zap.String("delivery_id", msg.DeliveryID.String()),
			zap.String("lane", string(msg.Lane)),
			zap.Error(sendErr))
		return nil, sendErr
	}
	return &entity.SendResult{Provider: entity.ProviderMailpit, MessageID: messageID}, nil
}

// acquire takes a bulkhead slot for the lane, waiting only while ctx lives.
func (s *Sender) acquire(ctx context.Context, lane entity.Lane) (func(), error) {
	sem, ok := s.sem[lane]
	if !ok {
		sem = s.sem[entity.LaneTransactional]
	}
	select {
	case sem <- struct{}{}:
	case <-ctx.Done():
		return nil, fmt.Errorf("smtp: bulkhead wait: %w", ctx.Err())
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

// stage names where a reply was received, used for classification.
type stage int

const (
	stageConnect stage = iota // dial, greeting, EHLO, STARTTLS, AUTH
	stageMail
	stageRcpt
	stageData
)

// deliver runs one SMTP session. The connection carries a hard deadline of
// send_timeout so a stalled server cannot hold the delivery lease
// (06-backpressure.md §6.2).
func (s *Sender) deliver(ctx context.Context, to string, body []byte) error {
	addr := net.JoinHostPort(s.cfg.SMTP.Host, fmt.Sprint(s.cfg.SMTP.Port))
	dialer := &net.Dialer{Timeout: s.cfg.ConnectTimeout}
	conn, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return classify(stageConnect, err)
	}
	defer conn.Close()

	deadline := time.Now().Add(s.cfg.SendTimeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	if s.cfg.SendTimeout > 0 {
		_ = conn.SetDeadline(deadline)
	}

	client, err := smtp.NewClient(conn, s.cfg.SMTP.Host)
	if err != nil {
		return classify(stageConnect, err)
	}
	defer client.Close()

	if err := client.Hello(heloName); err != nil {
		return classify(stageConnect, err)
	}
	// Mailpit speaks plain SMTP; STARTTLS is used only when the server offers
	// it so the same adapter also works against a hardened relay.
	if ok, _ := client.Extension("STARTTLS"); ok {
		if err := client.StartTLS(&tls.Config{ServerName: s.cfg.SMTP.Host, MinVersion: tls.VersionTLS12}); err != nil {
			return classify(stageConnect, err)
		}
	}
	if s.cfg.SMTP.Username != "" {
		if ok, _ := client.Extension("AUTH"); ok {
			auth := smtp.PlainAuth("", s.cfg.SMTP.Username, s.cfg.SMTP.Password, s.cfg.SMTP.Host)
			if err := client.Auth(auth); err != nil {
				return classify(stageConnect, err)
			}
		}
	}
	if err := client.Mail(s.cfg.FromAddress); err != nil {
		return classify(stageMail, err)
	}
	if err := client.Rcpt(to); err != nil {
		return classify(stageRcpt, err)
	}
	w, err := client.Data()
	if err != nil {
		return classify(stageData, err)
	}
	if _, err := w.Write(body); err != nil {
		return classify(stageData, err)
	}
	// Close sends the terminating "." and returns the server's verdict.
	if err := w.Close(); err != nil {
		return classify(stageData, err)
	}
	_ = client.Quit()
	return nil
}

// classify maps an SMTP/transport failure to the delivery-path vocabulary.
func classify(st stage, err error) error {
	var tpErr *textproto.Error
	if errors.As(err, &tpErr) {
		switch {
		case tpErr.Code >= 400 && tpErr.Code < 500:
			// Transient: greylisting, mailbox busy, insufficient storage.
			return apperr.NewRetryable(err)
		case tpErr.Code >= 500:
			if st == stageRcpt && (tpErr.Code == 550 || tpErr.Code == 551 || tpErr.Code == 553) {
				return apperr.NewPermanent(codeInvalidRecipient, err)
			}
			if st == stageConnect {
				// A refused EHLO/AUTH is a configuration problem; the message
				// itself is fine, so keep retrying until an operator fixes it.
				return apperr.NewRetryable(err)
			}
			return apperr.NewPermanent(codeProviderRejected, err)
		}
	}
	// Dial failures, deadlines, resets, EOF: try again later.
	return apperr.NewRetryable(err)
}

// MessageID builds the RFC 5322 Message-ID for a delivery: the delivery id
// at the sender's domain (ecommerce.local in dev), so Mailpit shows an id
// that can be searched by delivery. The bare id is returned (no angle
// brackets): that is what SendResult.MessageID stores; the header adds them.
func MessageID(deliveryID uuid.UUID, fromAddress string) string {
	domain := "ecommerce.local"
	if at := strings.LastIndex(fromAddress, "@"); at >= 0 && at < len(fromAddress)-1 {
		domain = fromAddress[at+1:]
	}
	return deliveryID.String() + "@" + domain
}

var _ port.HealthChecker = (*Sender)(nil)

// HealthCheck opens a session and answers NOOP, which proves the server is
// accepting connections without sending any mail.
func (s *Sender) HealthCheck(ctx context.Context) error {
	addr := net.JoinHostPort(s.cfg.SMTP.Host, fmt.Sprint(s.cfg.SMTP.Port))
	dialer := &net.Dialer{Timeout: s.cfg.ConnectTimeout}
	conn, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return fmt.Errorf("smtp: dial: %w", err)
	}
	defer conn.Close()
	deadline := time.Now().Add(s.cfg.SendTimeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	if s.cfg.SendTimeout > 0 {
		_ = conn.SetDeadline(deadline)
	}
	client, err := smtp.NewClient(conn, s.cfg.SMTP.Host)
	if err != nil {
		return fmt.Errorf("smtp: greeting: %w", err)
	}
	defer client.Close()
	if err := client.Hello(heloName); err != nil {
		return fmt.Errorf("smtp: hello: %w", err)
	}
	if err := client.Noop(); err != nil {
		return fmt.Errorf("smtp: noop: %w", err)
	}
	_ = client.Quit()
	return nil
}
