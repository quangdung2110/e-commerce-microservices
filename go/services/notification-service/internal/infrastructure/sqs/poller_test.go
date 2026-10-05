package sqs

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// ---- fakes -------------------------------------------------------------

type fakeSQS struct {
	messages []types.Message
	deleted  []string
}

func (f *fakeSQS) ReceiveMessage(_ context.Context, _ *sqs.ReceiveMessageInput, _ ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error) {
	out := &sqs.ReceiveMessageOutput{Messages: f.messages}
	f.messages = nil
	return out, nil
}

func (f *fakeSQS) DeleteMessage(_ context.Context, in *sqs.DeleteMessageInput, _ ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error) {
	f.deleted = append(f.deleted, aws.ToString(in.ReceiptHandle))
	return &sqs.DeleteMessageOutput{}, nil
}

type fakeDedup struct {
	port.DedupRepository
	seen map[string]bool
}

func (f *fakeDedup) ClaimProviderEvent(_ context.Context, _ entity.Provider, id string) (bool, error) {
	if f.seen[id] {
		return false, nil
	}
	f.seen[id] = true
	return true, nil
}

type fakeEvents struct {
	port.ProviderEventRepository
	rows      []*entity.ProviderEvent
	insertErr error
}

func (f *fakeEvents) Insert(_ context.Context, ev *entity.ProviderEvent) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	ev.ID = int64(len(f.rows) + 1)
	f.rows = append(f.rows, ev)
	return nil
}

type fakeTx struct {
	port.Tx
	dedup  *fakeDedup
	events *fakeEvents
}

func (f *fakeTx) Dedup() port.DedupRepository                  { return f.dedup }
func (f *fakeTx) ProviderEvents() port.ProviderEventRepository { return f.events }

type fakeStore struct {
	port.Store
	tx *fakeTx
}

func (f *fakeStore) WithinTx(ctx context.Context, fn func(context.Context, port.Tx) error) error {
	// A failing fn "rolls back": drop rows inserted during this call.
	before := len(f.tx.events.rows)
	seenBefore := make(map[string]bool, len(f.tx.dedup.seen))
	for k, v := range f.tx.dedup.seen {
		seenBefore[k] = v
	}
	if err := fn(ctx, f.tx); err != nil {
		f.tx.events.rows = f.tx.events.rows[:before]
		f.tx.dedup.seen = seenBefore
		return err
	}
	return nil
}

func newFakeStore() *fakeStore {
	return &fakeStore{tx: &fakeTx{dedup: &fakeDedup{seen: map[string]bool{}}, events: &fakeEvents{}}}
}

func snsMessage(t *testing.T, receipt string, sesBody []byte, now time.Time) types.Message {
	t.Helper()
	env := Envelope{
		Type:             TypeNotification,
		MessageID:        "sns-" + receipt,
		TopicArn:         "arn:aws:sns:ap-southeast-1:000000000000:ses-events",
		Message:          string(sesBody),
		Timestamp:        now.Format("2006-01-02T15:04:05.000Z"),
		SignatureVersion: "1",
		Signature:        "unchecked",
		SigningCertURL:   "https://sns.ap-southeast-1.amazonaws.com/x.pem",
	}
	body, err := json.Marshal(env)
	require.NoError(t, err)
	return types.Message{
		MessageId:     aws.String("sqs-" + receipt),
		ReceiptHandle: aws.String(receipt),
		Body:          aws.String(string(body)),
		Attributes:    map[string]string{"SentTimestamp": "1758527250000"},
	}
}

func newTestPoller(t *testing.T, client *fakeSQS, store port.Store, verifier *Verifier) *Poller {
	t.Helper()
	cfg := config.TrackConfig{QueueURL: "http://localhost:4566/000000000000/q", WaitTime: 20 * time.Second, BatchSize: 10}
	now := time.Date(2026, 9, 22, 7, 50, 0, 0, time.UTC)
	return NewPoller(cfg, client, store, verifier, clock.NewFake(now), telemetry.New(), nil)
}

// ---- tests -------------------------------------------------------------

func TestPoller_InsertsSanitizedAndDeletes(t *testing.T) {
	now := time.Date(2026, 9, 22, 7, 50, 0, 0, time.UTC)
	client := &fakeSQS{messages: []types.Message{
		snsMessage(t, "r1", readSample(t, "bounce-permanent.json"), now),
		snsMessage(t, "r2", readSample(t, "delivery.json"), now),
	}}
	store := newFakeStore()
	p := newTestPoller(t, client, store, NewVerifier(false, nil, 0, nil, nil))

	n, err := p.PollOnce(context.Background())
	require.NoError(t, err)
	require.Equal(t, 2, n)
	require.Equal(t, []string{"r1", "r2"}, client.deleted)
	require.Len(t, store.tx.events.rows, 2)

	row := store.tx.events.rows[0]
	require.Equal(t, entity.ProviderSES, row.Provider)
	require.Equal(t, entity.ProviderEventBounce, row.EventType)
	require.Equal(t, entity.ProviderEventPending, row.Status)
	require.Equal(t, "0100019996a1b2c3-2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e-000000", aws.ToString(row.ProviderMessageID))
	require.NotNil(t, row.OccurredAt)
	require.Equal(t, now, row.ReceivedAt)
	assertNoAddress(t, row.Raw)
}

func TestPoller_DuplicateIsDeletedWithoutInsert(t *testing.T) {
	now := time.Date(2026, 9, 22, 7, 50, 0, 0, time.UTC)
	client := &fakeSQS{messages: []types.Message{
		snsMessage(t, "r1", readSample(t, "send.json"), now),
		snsMessage(t, "r1-again", readSample(t, "send.json"), now),
	}}
	store := newFakeStore()
	p := newTestPoller(t, client, store, NewVerifier(false, nil, 0, nil, nil))
	_, err := p.PollOnce(context.Background())
	require.NoError(t, err)
	require.Len(t, store.tx.events.rows, 1)
	require.Equal(t, []string{"r1", "r1-again"}, client.deleted)
}

func TestPoller_InvalidSignatureDeletedNotInserted(t *testing.T) {
	now := time.Date(2026, 9, 22, 7, 50, 0, 0, time.UTC)
	client := &fakeSQS{messages: []types.Message{snsMessage(t, "r1", readSample(t, "send.json"), now)}}
	store := newFakeStore()
	// Enabled verifier with an empty allow-list: the cert URL is rejected.
	v := NewVerifier(true, nil, 10*time.Minute, clock.NewFake(now), nil)
	p := newTestPoller(t, client, store, v)
	_, err := p.PollOnce(context.Background())
	require.NoError(t, err)
	require.Empty(t, store.tx.events.rows)
	require.Equal(t, []string{"r1"}, client.deleted)
}

func TestPoller_SubscriptionConfirmationAndJunkDeleted(t *testing.T) {
	now := time.Date(2026, 9, 22, 7, 50, 0, 0, time.UTC)
	sub := snsMessage(t, "sub", []byte("{}"), now)
	var env Envelope
	require.NoError(t, json.Unmarshal([]byte(aws.ToString(sub.Body)), &env))
	env.Type = TypeSubscriptionConfirmation
	env.SubscribeURL = "https://sns.example/confirm"
	body, _ := json.Marshal(env)
	sub.Body = aws.String(string(body))

	junk := types.Message{ReceiptHandle: aws.String("junk"), Body: aws.String("not json")}
	badSES := snsMessage(t, "bad", []byte(`{"eventType":"Send"}`), now)

	client := &fakeSQS{messages: []types.Message{sub, junk, badSES}}
	store := newFakeStore()
	p := newTestPoller(t, client, store, NewVerifier(false, nil, 0, nil, nil))
	_, err := p.PollOnce(context.Background())
	require.NoError(t, err)
	require.Empty(t, store.tx.events.rows)
	require.Equal(t, []string{"sub", "junk", "bad"}, client.deleted)
}

func TestPoller_DBErrorLeavesMessage(t *testing.T) {
	now := time.Date(2026, 9, 22, 7, 50, 0, 0, time.UTC)
	client := &fakeSQS{messages: []types.Message{snsMessage(t, "r1", readSample(t, "send.json"), now)}}
	store := newFakeStore()
	store.tx.events.insertErr = errors.New("connection reset")
	p := newTestPoller(t, client, store, NewVerifier(false, nil, 0, nil, nil))
	_, err := p.PollOnce(context.Background())
	require.NoError(t, err)
	require.Empty(t, client.deleted, "message must stay for the visibility timeout / DLQ")
	require.Empty(t, store.tx.events.rows)
	require.Empty(t, store.tx.dedup.seen, "dedup claim rolled back with the insert")
}
