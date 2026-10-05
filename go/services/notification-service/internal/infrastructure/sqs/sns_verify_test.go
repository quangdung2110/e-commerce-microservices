package sqs

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1" //nolint:gosec // SNS SignatureVersion 1
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/errors"
)

type certServer struct {
	srv  *httptest.Server
	key  *rsa.PrivateKey
	host string
	url  string
}

func newCertServer(t *testing.T, now time.Time) *certServer {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "sns.test"},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	require.NoError(t, err)
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})

	mux := http.NewServeMux()
	mux.HandleFunc("/SimpleNotificationService-test.pem", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write(pemBytes)
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	u, _ := url.Parse(srv.URL)
	return &certServer{srv: srv, key: key, host: u.Host, url: srv.URL + "/SimpleNotificationService-test.pem"}
}

func (c *certServer) sign(t *testing.T, env *Envelope) {
	t.Helper()
	canonical := []byte(env.CanonicalString())
	var sig []byte
	var err error
	switch env.SignatureVersion {
	case "2":
		sum := sha256.Sum256(canonical)
		sig, err = rsa.SignPKCS1v15(rand.Reader, c.key, crypto.SHA256, sum[:])
	default:
		sum := sha1.Sum(canonical) //nolint:gosec
		sig, err = rsa.SignPKCS1v15(rand.Reader, c.key, crypto.SHA1, sum[:])
	}
	require.NoError(t, err)
	env.Signature = base64.StdEncoding.EncodeToString(sig)
}

func testEnvelope(now time.Time, certURL string) *Envelope {
	return &Envelope{
		Type:             TypeNotification,
		MessageID:        "8d1c7f6e-1111-2222-3333-444455556666",
		TopicArn:         "arn:aws:sns:ap-southeast-1:000000000000:ses-events",
		Message:          `{"eventType":"Send","mail":{"messageId":"abc"}}`,
		Timestamp:        now.Format("2006-01-02T15:04:05.000Z"),
		SignatureVersion: "1",
		SigningCertURL:   certURL,
	}
}

func TestVerifier_ValidSignature(t *testing.T) {
	now := time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)
	cs := newCertServer(t, now)
	v := NewVerifier(true, []string{cs.host}, 10*time.Minute, clock.NewFake(now), cs.srv.Client())

	for _, version := range []string{"1", "2"} {
		env := testEnvelope(now, cs.url)
		env.SignatureVersion = version
		cs.sign(t, env)
		require.NoError(t, v.Verify(context.Background(), env), "version %s", version)
	}

	// Subject participates in the canonical string when present.
	env := testEnvelope(now, cs.url)
	env.Subject = "Amazon SES Email Event Notification"
	cs.sign(t, env)
	require.NoError(t, v.Verify(context.Background(), env))

	// Subscription confirmation uses the other key order.
	sub := testEnvelope(now, cs.url)
	sub.Type = TypeSubscriptionConfirmation
	sub.Token = "tok"
	sub.SubscribeURL = "https://sns.example/confirm"
	cs.sign(t, sub)
	require.NoError(t, v.Verify(context.Background(), sub))
}

func TestVerifier_TamperedMessage(t *testing.T) {
	now := time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)
	cs := newCertServer(t, now)
	v := NewVerifier(true, []string{cs.host}, 10*time.Minute, clock.NewFake(now), cs.srv.Client())

	env := testEnvelope(now, cs.url)
	cs.sign(t, env)
	env.Message = `{"eventType":"Bounce","mail":{"messageId":"abc"}}`
	err := v.Verify(context.Background(), env)
	require.ErrorIs(t, err, errors.ErrInvalidSignature)

	// Wrong signature version for the digest also fails.
	env2 := testEnvelope(now, cs.url)
	cs.sign(t, env2)
	env2.SignatureVersion = "2"
	require.ErrorIs(t, v.Verify(context.Background(), env2), errors.ErrInvalidSignature)

	// Garbage base64.
	env3 := testEnvelope(now, cs.url)
	env3.Signature = "!!!"
	require.ErrorIs(t, v.Verify(context.Background(), env3), errors.ErrInvalidSignature)
}

func TestVerifier_DisallowedCertHost(t *testing.T) {
	now := time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)
	cs := newCertServer(t, now)
	// Allow-list does not contain the test server.
	v := NewVerifier(true, []string{"sns.ap-southeast-1.amazonaws.com"}, 10*time.Minute, clock.NewFake(now), cs.srv.Client())

	env := testEnvelope(now, cs.url)
	cs.sign(t, env)
	err := v.Verify(context.Background(), env)
	require.Error(t, err)
	require.NotErrorIs(t, err, errors.ErrInvalidSignature, "a policy failure is not a forged signature")
	require.Contains(t, err.Error(), "not allow-listed")

	// Allow-listed host but non-.pem path.
	v2 := NewVerifier(true, []string{cs.host}, 10*time.Minute, clock.NewFake(now), cs.srv.Client())
	env2 := testEnvelope(now, cs.srv.URL+"/evil.txt")
	cs.sign(t, env2)
	require.Error(t, v2.Verify(context.Background(), env2))

	// Unknown scheme.
	env3 := testEnvelope(now, "ftp://"+cs.host+"/x.pem")
	cs.sign(t, env3)
	require.Error(t, v2.Verify(context.Background(), env3))
}

func TestVerifier_ClockSkew(t *testing.T) {
	now := time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)
	cs := newCertServer(t, now)
	v := NewVerifier(true, []string{cs.host}, 10*time.Minute, clock.NewFake(now), cs.srv.Client())

	env := testEnvelope(now.Add(-11*time.Minute), cs.url)
	cs.sign(t, env)
	require.ErrorIs(t, v.Verify(context.Background(), env), errors.ErrInvalidSignature)

	env = testEnvelope(now.Add(9*time.Minute), cs.url)
	cs.sign(t, env)
	require.NoError(t, v.Verify(context.Background(), env))
}

func TestVerifier_Disabled(t *testing.T) {
	v := NewVerifier(false, nil, time.Minute, clock.NewFake(time.Now()), nil)
	require.NoError(t, v.Verify(context.Background(), &Envelope{Type: TypeNotification, Signature: "bogus"}))
	require.False(t, v.Enabled())
}

func TestVerifier_CachesCertificate(t *testing.T) {
	now := time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)
	cs := newCertServer(t, now)
	v := NewVerifier(true, []string{cs.host}, 10*time.Minute, clock.NewFake(now), cs.srv.Client())
	env := testEnvelope(now, cs.url)
	cs.sign(t, env)
	require.NoError(t, v.Verify(context.Background(), env))
	cs.srv.Close() // second verify must not need the network
	require.NoError(t, v.Verify(context.Background(), env))
}
