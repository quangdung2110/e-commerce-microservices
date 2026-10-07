package smtp

import (
	"bufio"
	"context"
	"encoding/base64"
	"io"
	"mime"
	"mime/multipart"
	"mime/quotedprintable"
	"net"
	"net/mail"
	"strings"
	"sync"
	"testing"
	"time"

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

// fakeSMTP is a minimal in-process SMTP server: enough of the dialogue for
// net/smtp, with programmable replies for RCPT and DATA.
type fakeSMTP struct {
	ln        net.Listener
	rcptReply string // e.g. "250 OK" or "550 5.1.1 no such user"
	dataReply string // reply after the final "."
	hangData  bool   // never answer after DATA (timeout test)

	mu       sync.Mutex
	messages []string
	rcpts    []string
	from     string
}

func newFakeSMTP(t *testing.T) *fakeSMTP {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	f := &fakeSMTP{ln: ln, rcptReply: "250 2.1.5 OK", dataReply: "250 2.0.0 queued"}
	go f.serve()
	t.Cleanup(func() { _ = ln.Close() })
	return f
}

func (f *fakeSMTP) port() int {
	return f.ln.Addr().(*net.TCPAddr).Port
}

func (f *fakeSMTP) serve() {
	for {
		conn, err := f.ln.Accept()
		if err != nil {
			return
		}
		go f.handle(conn)
	}
}

func (f *fakeSMTP) handle(conn net.Conn) {
	defer conn.Close()
	r := bufio.NewReader(conn)
	w := bufio.NewWriter(conn)
	reply := func(s string) {
		_, _ = w.WriteString(s + "\r\n")
		_ = w.Flush()
	}
	reply("220 fake ESMTP")
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimRight(line, "\r\n")
		upper := strings.ToUpper(line)
		switch {
		case strings.HasPrefix(upper, "EHLO"):
			reply("250-fake")
			reply("250-8BITMIME")
			reply("250 SIZE 10485760")
		case strings.HasPrefix(upper, "HELO"):
			reply("250 fake")
		case strings.HasPrefix(upper, "MAIL FROM:"):
			f.mu.Lock()
			f.from = line[len("MAIL FROM:"):]
			f.mu.Unlock()
			reply("250 2.1.0 OK")
		case strings.HasPrefix(upper, "RCPT TO:"):
			f.mu.Lock()
			f.rcpts = append(f.rcpts, line[len("RCPT TO:"):])
			f.mu.Unlock()
			reply(f.rcptReply)
		case upper == "DATA":
			reply("354 End data with <CR><LF>.<CR><LF>")
			var body strings.Builder
			for {
				l, err := r.ReadString('\n')
				if err != nil {
					return
				}
				if l == ".\r\n" {
					break
				}
				body.WriteString(l)
			}
			f.mu.Lock()
			f.messages = append(f.messages, body.String())
			f.mu.Unlock()
			if f.hangData {
				time.Sleep(5 * time.Second)
				return
			}
			reply(f.dataReply)
		case upper == "QUIT":
			reply("221 bye")
			return
		case upper == "RSET":
			reply("250 OK")
		default:
			reply("500 unrecognised")
		}
	}
}

func (f *fakeSMTP) lastMessage(t *testing.T) string {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	require.NotEmpty(t, f.messages)
	return f.messages[len(f.messages)-1]
}

func newSender(f *fakeSMTP, m *telemetry.Metrics) *Sender {
	cfg := config.EmailConfig{
		Provider:       "smtp",
		FromAddress:    "no-reply@ecommerce.local",
		FromName:       "E-Commerce Việt",
		ConnectTimeout: 500 * time.Millisecond,
		SendTimeout:    500 * time.Millisecond,
		SMTP:           config.SMTPConfig{Host: "127.0.0.1", Port: f.port(), MaxConnsPerLane: 2},
	}
	return NewSender(cfg, clock.NewFake(time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)), m, nil)
}

func sampleMessage() *entity.EmailMessage {
	return &entity.EmailMessage{
		DeliveryID: uuid.MustParse("0f6e5d4c-3b2a-4190-8877-665544332211"),
		Lane:       entity.LaneBulk,
		To:         pii.MustEmail("khach@example.com"),
		Content: entity.EmailContent{
			Subject: "Giỏ hàng của bạn đang chờ",
			HTML:    "<html><body><p>Xin chào, <b>giỏ hàng</b> của bạn còn 2 sản phẩm — quay lại nhé!</p></body></html>",
			Text:    "Xin chao, gio hang cua ban con 2 san pham. Line two with = sign and a long line " + strings.Repeat("x", 100),
			Headers: map[string]string{
				"List-Unsubscribe": "<http://localhost:3000/profile/preferences>",
				// Must be ignored: reserved, and a CRLF injection attempt.
				"Subject":                 "evil",
				"X-Notification-Template": "CART_ABANDONED\r\nBcc: attacker@example.com",
			},
		},
	}
}

func decodePart(t *testing.T, p *multipart.Part) string {
	t.Helper()
	var r io.Reader = p
	switch strings.ToLower(p.Header.Get("Content-Transfer-Encoding")) {
	case "quoted-printable":
		r = quotedprintable.NewReader(p)
	case "base64":
		r = base64.NewDecoder(base64.StdEncoding, p)
	}
	b, err := io.ReadAll(r)
	require.NoError(t, err)
	return string(b)
}

func TestSender_SendBuildsMultipartMessage(t *testing.T) {
	f := newFakeSMTP(t)
	m := telemetry.New()
	s := newSender(f, m)
	msg := sampleMessage()

	res, err := s.Send(context.Background(), msg)
	require.NoError(t, err)
	assert.Equal(t, entity.ProviderMailpit, res.Provider)
	assert.Equal(t, "0f6e5d4c-3b2a-4190-8877-665544332211@ecommerce.local", res.MessageID)

	f.mu.Lock()
	assert.Equal(t, []string{"<khach@example.com>"}, f.rcpts)
	assert.Contains(t, f.from, "<no-reply@ecommerce.local>")
	f.mu.Unlock()

	raw := f.lastMessage(t)
	parsed, err := mail.ReadMessage(strings.NewReader(raw))
	require.NoError(t, err)
	h := parsed.Header

	dec := new(mime.WordDecoder)
	subject, err := dec.DecodeHeader(h.Get("Subject"))
	require.NoError(t, err)
	assert.Equal(t, "Giỏ hàng của bạn đang chờ", subject)
	assert.True(t, strings.HasPrefix(h.Get("Subject"), "=?utf-8?q?"), "subject must be RFC 2047 encoded: %s", h.Get("Subject"))

	from, err := dec.DecodeHeader(h.Get("From"))
	require.NoError(t, err)
	assert.Contains(t, from, "E-Commerce Việt")
	assert.Contains(t, from, "<no-reply@ecommerce.local>")
	assert.True(t, strings.HasPrefix(h.Get("From"), "=?utf-8?"), "from name must be RFC 2047 encoded: %s", h.Get("From"))
	assert.Equal(t, "<khach@example.com>", h.Get("To"))
	assert.Equal(t, "<0f6e5d4c-3b2a-4190-8877-665544332211@ecommerce.local>", h.Get("Message-Id"))
	assert.Equal(t, "Tue, 22 Sep 2026 10:00:00 +0000", h.Get("Date"))
	assert.Equal(t, "0f6e5d4c-3b2a-4190-8877-665544332211", h.Get("X-Notification-Delivery-Id"))
	assert.Equal(t, "<http://localhost:3000/profile/preferences>", h.Get("List-Unsubscribe"))
	assert.Equal(t, "CART_ABANDONED Bcc: attacker@example.com", h.Get("X-Notification-Template"), "CRLF folded into one line")
	assert.Empty(t, h.Get("Bcc"))
	assert.Len(t, h["Subject"], 1, "renderer Subject header must not duplicate ours")
	assert.Equal(t, "1.0", h.Get("Mime-Version"))

	mediaType, params, err := mime.ParseMediaType(h.Get("Content-Type"))
	require.NoError(t, err)
	assert.Equal(t, "multipart/alternative", mediaType)

	mr := multipart.NewReader(parsed.Body, params["boundary"])
	textPart, err := mr.NextPart()
	require.NoError(t, err)
	assert.Equal(t, "text/plain; charset=UTF-8", textPart.Header.Get("Content-Type"))
	// multipart.Reader decodes quoted-printable transparently and drops the
	// header, so the encoding is asserted on the raw message instead.
	assert.Contains(t, raw, "Content-Transfer-Encoding: quoted-printable")
	assert.Equal(t, msg.Content.Text, decodePart(t, textPart))

	htmlPart, err := mr.NextPart()
	require.NoError(t, err)
	assert.Equal(t, "text/html; charset=UTF-8", htmlPart.Header.Get("Content-Type"))
	assert.Equal(t, msg.Content.HTML, decodePart(t, htmlPart))

	_, err = mr.NextPart()
	assert.ErrorIs(t, err, io.EOF)

	// No raw line longer than 998 octets (RFC 5322 §2.1.1).
	for _, line := range strings.Split(raw, "\r\n") {
		assert.LessOrEqual(t, len(line), 998)
	}
}

func TestSender_Base64ForNonASCIIHeavyBody(t *testing.T) {
	f := newFakeSMTP(t)
	s := newSender(f, nil)
	msg := sampleMessage()
	msg.Content.Text = strings.Repeat("Đặng Thị Hồng Nhung ", 20)
	msg.Content.HTML = ""

	_, err := s.Send(context.Background(), msg)
	require.NoError(t, err)
	parsed, err := mail.ReadMessage(strings.NewReader(f.lastMessage(t)))
	require.NoError(t, err)
	_, params, _ := mime.ParseMediaType(parsed.Header.Get("Content-Type"))
	mr := multipart.NewReader(parsed.Body, params["boundary"])
	part, err := mr.NextPart()
	require.NoError(t, err)
	assert.Equal(t, "base64", part.Header.Get("Content-Transfer-Encoding"))
	assert.Equal(t, msg.Content.Text, decodePart(t, part))
}

func TestSender_ErrorClassification(t *testing.T) {
	cases := []struct {
		name      string
		configure func(f *fakeSMTP)
		retryable bool
		code      string
	}{
		{"rcpt 550 → invalid_recipient", func(f *fakeSMTP) { f.rcptReply = "550 5.1.1 no such user" }, false, "invalid_recipient"},
		{"rcpt 553 → invalid_recipient", func(f *fakeSMTP) { f.rcptReply = "553 5.1.3 bad mailbox syntax" }, false, "invalid_recipient"},
		{"rcpt 552 → provider_rejected", func(f *fakeSMTP) { f.rcptReply = "552 5.2.2 mailbox full" }, false, "provider_rejected"},
		{"rcpt 450 → retryable", func(f *fakeSMTP) { f.rcptReply = "450 4.2.1 greylisted" }, true, ""},
		{"data 451 → retryable", func(f *fakeSMTP) { f.dataReply = "451 4.3.0 temporary failure" }, true, ""},
		{"data 554 → provider_rejected", func(f *fakeSMTP) { f.dataReply = "554 5.7.1 message rejected" }, false, "provider_rejected"},
		{"data timeout → retryable", func(f *fakeSMTP) { f.hangData = true }, true, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeSMTP(t)
			tc.configure(f)
			s := newSender(f, telemetry.New())
			_, err := s.Send(context.Background(), sampleMessage())
			require.Error(t, err)
			assert.Equal(t, tc.retryable, apperr.IsRetryable(err), "err=%v", err)
			code, permanent := apperr.PermanentCode(err)
			assert.Equal(t, !tc.retryable, permanent)
			assert.Equal(t, tc.code, code)
		})
	}
}

func TestSender_ConnectionRefusedIsRetryable(t *testing.T) {
	f := newFakeSMTP(t)
	s := newSender(f, nil)
	_ = f.ln.Close()
	_, err := s.Send(context.Background(), sampleMessage())
	require.True(t, apperr.IsRetryable(err), "err=%v", err)
}

func TestSender_BulkheadPerLane(t *testing.T) {
	f := newFakeSMTP(t)
	f.hangData = true
	s := newSender(f, nil) // MaxConnsPerLane = 2

	ctx := context.Background()
	// Two bulk sends occupy both slots (they hang until send_timeout).
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = s.Send(ctx, sampleMessage())
		}()
	}
	time.Sleep(100 * time.Millisecond)
	assert.Len(t, s.sem[entity.LaneBulk], 2)

	// A third bulk waits for a slot: with a short context it gives up, retryable.
	short, cancel := context.WithTimeout(ctx, 50*time.Millisecond)
	defer cancel()
	_, err := s.Send(short, sampleMessage())
	assert.True(t, apperr.IsRetryable(err), "err=%v", err)

	// Critical has its own bulkhead and is not blocked by bulk.
	assert.Len(t, s.sem[entity.LaneCritical], 0)
	wg.Wait()
	assert.Len(t, s.sem[entity.LaneBulk], 0)
}

func TestMessageID(t *testing.T) {
	id := uuid.MustParse("0f6e5d4c-3b2a-4190-8877-665544332211")
	assert.Equal(t, "0f6e5d4c-3b2a-4190-8877-665544332211@shop.example", MessageID(id, "no-reply@shop.example"))
	assert.Equal(t, "0f6e5d4c-3b2a-4190-8877-665544332211@ecommerce.local", MessageID(id, "garbage"))
}

func TestChooseEncoding(t *testing.T) {
	assert.Equal(t, "quoted-printable", chooseEncoding("plain ascii"))
	assert.Equal(t, "quoted-printable", chooseEncoding("mostly ascii with é"))
	assert.Equal(t, "base64", chooseEncoding("ĐặngThịHồngNhung"))
	assert.Equal(t, "quoted-printable", chooseEncoding(""))
}
