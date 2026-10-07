// Package sqs implements the Track ingress of 03-tracking.md §3: a thin poller
// that long-polls the SES → SNS → SQS queue, verifies the SNS signature,
// hashes every address out of the SES event and appends it to provider_event.
// Everything slow (correlate, suppression, outbox) is left to the track
// worker so the queue keeps draining at a steady pace.
package sqs

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha1" //nolint:gosec // SNS SignatureVersion 1 is SHA1withRSA by AWS definition.
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/errors"
)

// SNS message types (envelope field Type).
const (
	TypeNotification             = "Notification"
	TypeSubscriptionConfirmation = "SubscriptionConfirmation"
	TypeUnsubscribeConfirmation  = "UnsubscribeConfirmation"
)

// Envelope is the SNS JSON delivered to SQS when RawMessageDelivery=false
// (deploy/localstack/init-ses-events.sh keeps it so dev verifies like prod).
type Envelope struct {
	Type             string `json:"Type"`
	MessageID        string `json:"MessageId"`
	TopicArn         string `json:"TopicArn"`
	Subject          string `json:"Subject,omitempty"`
	Message          string `json:"Message"`
	Timestamp        string `json:"Timestamp"`
	SignatureVersion string `json:"SignatureVersion"`
	Signature        string `json:"Signature"`
	SigningCertURL   string `json:"SigningCertURL"`
	UnsubscribeURL   string `json:"UnsubscribeURL,omitempty"`
	SubscribeURL     string `json:"SubscribeURL,omitempty"`
	Token            string `json:"Token,omitempty"`
}

// CanonicalString builds the string AWS signed, following the documented
// key order ("Verifying the signatures of Amazon SNS messages"). Each key and
// value is followed by a newline. Subject is included only when present.
func (e *Envelope) CanonicalString() string {
	var b strings.Builder
	add := func(k, v string) {
		b.WriteString(k)
		b.WriteByte('\n')
		b.WriteString(v)
		b.WriteByte('\n')
	}
	switch e.Type {
	case TypeSubscriptionConfirmation, TypeUnsubscribeConfirmation:
		add("Message", e.Message)
		add("MessageId", e.MessageID)
		add("SubscribeURL", e.SubscribeURL)
		add("Timestamp", e.Timestamp)
		add("Token", e.Token)
		add("TopicArn", e.TopicArn)
		add("Type", e.Type)
	default: // Notification
		add("Message", e.Message)
		add("MessageId", e.MessageID)
		if e.Subject != "" {
			add("Subject", e.Subject)
		}
		add("Timestamp", e.Timestamp)
		add("TopicArn", e.TopicArn)
		add("Type", e.Type)
	}
	return b.String()
}

// maxCertBytes bounds the certificate download (a PEM cert is ~2 KB).
const maxCertBytes = 64 * 1024

// maxCachedCerts bounds the cert cache; AWS rotates certs rarely, so the
// cache is reset (not evicted LRU) when an attacker-controlled URL set could
// grow it — every cached URL passed the host allow-list anyway.
const maxCachedCerts = 32

// Verifier checks SNS signatures (03-tracking.md §6 items 1–2): the
// certificate is fetched only from an allow-listed host, never from an
// arbitrary URL in the payload, and the Timestamp must be within MaxClockSkew
// of now to bound replay.
type Verifier struct {
	enabled      bool
	allowedHosts map[string]bool
	maxSkew      time.Duration
	clock        clock.Clock
	http         *http.Client

	mu    sync.Mutex
	certs map[string]*rsa.PublicKey
}

// NewVerifier builds a verifier. allowedHosts entries are compared against
// url.Host (host[:port]) case-insensitively; https is required unless the host
// is allow-listed (LocalStack serves its certificate over plain http).
func NewVerifier(enabled bool, allowedHosts []string, maxSkew time.Duration, clk clock.Clock, httpClient *http.Client) *Verifier {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 5 * time.Second}
	}
	if clk == nil {
		clk = clock.System{}
	}
	hosts := make(map[string]bool, len(allowedHosts))
	for _, h := range allowedHosts {
		hosts[strings.ToLower(strings.TrimSpace(h))] = true
	}
	return &Verifier{
		enabled:      enabled,
		allowedHosts: hosts,
		maxSkew:      maxSkew,
		clock:        clk,
		http:         httpClient,
		certs:        make(map[string]*rsa.PublicKey),
	}
}

// Enabled reports whether signatures are actually checked (dev may disable).
func (v *Verifier) Enabled() bool { return v.enabled }

// Verify returns nil when the envelope carries a valid signature from an
// allow-listed certificate and a fresh timestamp. Signature mismatches are
// reported as errors.ErrInvalidSignature (wrapped); configuration or transport
// failures while fetching the certificate are returned as plain errors so the
// caller can tell "forged" from "cannot check".
func (v *Verifier) Verify(ctx context.Context, env *Envelope) error {
	if !v.enabled {
		return nil
	}
	if env == nil {
		return fmt.Errorf("%w: nil envelope", errors.ErrInvalidSignature)
	}
	if err := v.checkTimestamp(env.Timestamp); err != nil {
		return err
	}
	var hash crypto.Hash
	switch env.SignatureVersion {
	case "1":
		hash = crypto.SHA1
	case "2":
		hash = crypto.SHA256
	default:
		return fmt.Errorf("%w: unsupported SignatureVersion %q", errors.ErrInvalidSignature, env.SignatureVersion)
	}
	sig, err := base64.StdEncoding.DecodeString(env.Signature)
	if err != nil {
		return fmt.Errorf("%w: signature is not base64: %v", errors.ErrInvalidSignature, err)
	}
	pub, err := v.publicKey(ctx, env.SigningCertURL)
	if err != nil {
		return err
	}
	canonical := []byte(env.CanonicalString())
	var digest []byte
	switch hash {
	case crypto.SHA1:
		sum := sha1.Sum(canonical) //nolint:gosec // see SignatureVersion 1 note above
		digest = sum[:]
	default:
		sum := sha256.Sum256(canonical)
		digest = sum[:]
	}
	if err := rsa.VerifyPKCS1v15(pub, hash, digest, sig); err != nil {
		return fmt.Errorf("%w: %v", errors.ErrInvalidSignature, err)
	}
	return nil
}

// checkTimestamp rejects messages outside ±maxSkew (03-tracking.md §6 item 2).
func (v *Verifier) checkTimestamp(ts string) error {
	t, err := time.Parse(time.RFC3339Nano, ts)
	if err != nil {
		return fmt.Errorf("%w: bad Timestamp %q", errors.ErrInvalidSignature, ts)
	}
	if v.maxSkew <= 0 {
		return nil
	}
	delta := v.clock.Now().Sub(t)
	if delta < 0 {
		delta = -delta
	}
	if delta > v.maxSkew {
		return fmt.Errorf("%w: Timestamp %s outside allowed clock skew %s", errors.ErrInvalidSignature, ts, v.maxSkew)
	}
	return nil
}

// checkCertURL enforces the host allow-list and the https requirement. The
// URL in the payload is data, not a command: an attacker with a valid-looking
// envelope must not be able to point us at their own certificate.
func (v *Verifier) checkCertURL(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, fmt.Errorf("sns: bad SigningCertURL: %w", err)
	}
	host := strings.ToLower(u.Host)
	if host == "" || !v.allowedHosts[host] {
		return nil, fmt.Errorf("sns: SigningCertURL host %q is not allow-listed", u.Host)
	}
	switch u.Scheme {
	case "https":
	case "http":
		// Only tolerated because the host passed the allow-list (LocalStack).
	default:
		return nil, fmt.Errorf("sns: SigningCertURL scheme %q not allowed", u.Scheme)
	}
	if !strings.HasSuffix(strings.ToLower(u.Path), ".pem") {
		return nil, fmt.Errorf("sns: SigningCertURL path %q is not a .pem", u.Path)
	}
	return u, nil
}

func (v *Verifier) publicKey(ctx context.Context, certURL string) (*rsa.PublicKey, error) {
	v.mu.Lock()
	if pub, ok := v.certs[certURL]; ok {
		v.mu.Unlock()
		return pub, nil
	}
	v.mu.Unlock()

	u, err := v.checkCertURL(certURL)
	if err != nil {
		return nil, err
	}
	pub, err := v.fetch(ctx, u.String())
	if err != nil {
		return nil, err
	}

	v.mu.Lock()
	if len(v.certs) >= maxCachedCerts {
		v.certs = make(map[string]*rsa.PublicKey)
	}
	v.certs[certURL] = pub
	v.mu.Unlock()
	return pub, nil
}

func (v *Verifier) fetch(ctx context.Context, certURL string) (*rsa.PublicKey, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, certURL, nil)
	if err != nil {
		return nil, fmt.Errorf("sns: build cert request: %w", err)
	}
	resp, err := v.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("sns: fetch cert: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("sns: fetch cert: status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxCertBytes+1))
	if err != nil {
		return nil, fmt.Errorf("sns: read cert: %w", err)
	}
	if len(body) > maxCertBytes {
		return nil, fmt.Errorf("sns: certificate larger than %d bytes", maxCertBytes)
	}
	return parseRSACert(body, v.clock.Now())
}

func parseRSACert(pemBytes []byte, now time.Time) (*rsa.PublicKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, fmt.Errorf("sns: certificate is not PEM CERTIFICATE")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("sns: parse certificate: %w", err)
	}
	if now.Before(cert.NotBefore) || now.After(cert.NotAfter) {
		return nil, fmt.Errorf("sns: certificate not valid at %s (valid %s..%s)", now.Format(time.RFC3339), cert.NotBefore.Format(time.RFC3339), cert.NotAfter.Format(time.RFC3339))
	}
	pub, ok := cert.PublicKey.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("sns: certificate key is %T, want RSA", cert.PublicKey)
	}
	return pub, nil
}
