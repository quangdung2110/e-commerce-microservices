package userdeleted

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/observability"
	"cart-service/internal/service"
)

const testUserID = "0198e098-96f2-7ad3-91d8-570608021328"

type recordingStore struct {
	purged []domain.Identity
	result port.PurgeResult
	err    error
}

func (s *recordingStore) LoadItems(context.Context, domain.Identity) ([]domain.Item, error) {
	return nil, nil
}

func (s *recordingStore) Upsert(context.Context, port.UpsertCommand) (port.UpsertResult, error) {
	return port.UpsertResult{}, nil
}

func (s *recordingStore) Remove(context.Context, port.RemoveCommand) (port.RemoveResult, error) {
	return port.RemoveResult{}, nil
}

func (s *recordingStore) MarkAbandoned(context.Context, port.MarkAbandonedCommand) (port.MarkAbandonedResult, error) {
	return port.MarkAbandonedResult{}, nil
}

func (s *recordingStore) PurgeCart(_ context.Context, identity domain.Identity) (port.PurgeResult, error) {
	s.purged = append(s.purged, identity)
	return s.result, s.err
}

func (s *recordingStore) DueCarts(context.Context, int, time.Time, int) ([]port.DueCart, error) {
	return nil, nil
}

func (s *recordingStore) DropIndexMember(context.Context, int, string) error { return nil }
func (s *recordingStore) Ping(context.Context) error                         { return nil }

type nopCatalog struct{}

func (nopCatalog) GetSku(context.Context, string) (domain.ProductSnapshot, error) {
	return domain.ProductSnapshot{}, nil
}

func (nopCatalog) GetBatchSkus(context.Context, []string) (map[string]domain.ProductSnapshot, error) {
	return nil, nil
}

func (nopCatalog) Ready(context.Context) error { return nil }

func newTestHandler(store *recordingStore) *Handler {
	cartService := service.New(store, nopCatalog{}, service.Config{ProductValidationTTL: time.Minute})
	return NewHandler(cartService, observability.NewMetrics(), zap.NewNop())
}

func message(body string) *sarama.ConsumerMessage {
	return &sarama.ConsumerMessage{Topic: "t.auth.events", Value: []byte(body)}
}

// The envelope is the one Auth documents for USER_DELETED on t.auth.events
// (contracts/events/auth/v1/asyncapi.yaml, userDeleted example).
const userDeletedEvent = `{
  "specversion": "1.0",
  "id": "0198e099-97e4-7d66-8a2c-997d792ae826",
  "type": "com.ecommerce.auth.user-deleted.v1",
  "source": "/services/auth",
  "subject": "accounts/0198e098-96f2-7ad3-91d8-570608021328",
  "time": "2026-08-27T12:30:00Z",
  "datacontenttype": "application/json",
  "dataschema": "/contracts/events/auth/v1/schemas/user-deleted.schema.json",
  "correlationid": "0198e098-96f2-7ad3-91d8-570608021328",
  "data": {
    "user_id": "0198e098-96f2-7ad3-91d8-570608021328",
    "deleted_at": "2026-08-27T12:30:00Z",
    "reason": "GDPR_REQUEST",
    "hard_delete_after": "2026-11-25T12:30:00Z"
  }
}`

func TestHandlerPurgesTheCartOfTheDeletedAccount(t *testing.T) {
	store := &recordingStore{result: port.PurgeResult{Purged: true}}

	if err := newTestHandler(store).Handle(context.Background(), message(userDeletedEvent)); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	if len(store.purged) != 1 {
		t.Fatalf("store was called %d times, want 1", len(store.purged))
	}
	// Cart derives the cart from user_id; it never reads a cart_id from the
	// event, so the routing rule lives in exactly one place.
	expected, err := domain.NewIdentity(testUserID)
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}
	got := store.purged[0]
	if got.CartID != expected.CartID || got.Bucket != expected.Bucket || got.UserToken != expected.UserToken {
		t.Fatalf("identity = %+v, want %+v", got, expected)
	}
}

// Kafka redelivers, and a deleted user may never have had a cart. Both are
// success: the purge is idempotent and there is nothing to retry.
func TestHandlerAcceptsAUserWithoutACart(t *testing.T) {
	store := &recordingStore{result: port.PurgeResult{Purged: false}}

	if err := newTestHandler(store).Handle(context.Background(), message(userDeletedEvent)); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(store.purged) != 1 {
		t.Fatal("the purge was not attempted")
	}
}

// t.auth.events also carries USER_VERIFIED_SUCCESS and, later, other identity
// facts. Cart must leave every one of them alone.
func TestHandlerIgnoresOtherAuthEvents(t *testing.T) {
	store := &recordingStore{}
	body := `{"specversion":"1.0","id":"0198e099-97e4-7d66-8a2c-997d792ae826","type":"com.ecommerce.auth.user-verified-success.v1","source":"/services/auth","data":{"user_id":"` + testUserID + `"}}`

	if err := newTestHandler(store).Handle(context.Background(), message(body)); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(store.purged) != 0 {
		t.Fatal("a cart was purged by a non-USER_DELETED event")
	}
}

// A message Cart cannot parse will never parse. Committing past it is the only
// way forward; blocking the partition forever is not.
func TestHandlerSkipsUnparseableMessages(t *testing.T) {
	store := &recordingStore{}

	if err := newTestHandler(store).Handle(context.Background(), message("{not json")); err != nil {
		t.Fatalf("an unparseable message must be skipped, got %v", err)
	}
	if len(store.purged) != 0 {
		t.Fatal("a cart was purged from an unparseable message")
	}
}

func TestHandlerSkipsAnEventThatIsNotACloudEventFromAuth(t *testing.T) {
	cases := map[string]string{
		"wrong source":  `{"specversion":"1.0","id":"e1","type":"com.ecommerce.auth.user-deleted.v1","source":"/services/other","data":{"user_id":"` + testUserID + `","deleted_at":"2026-08-27T12:30:00Z"}}`,
		"no id":         `{"specversion":"1.0","type":"com.ecommerce.auth.user-deleted.v1","source":"/services/auth","data":{"user_id":"` + testUserID + `","deleted_at":"2026-08-27T12:30:00Z"}}`,
		"bad spec":      `{"specversion":"0.3","id":"e1","type":"com.ecommerce.auth.user-deleted.v1","source":"/services/auth","data":{"user_id":"` + testUserID + `","deleted_at":"2026-08-27T12:30:00Z"}}`,
		"no user":       `{"specversion":"1.0","id":"e1","type":"com.ecommerce.auth.user-deleted.v1","source":"/services/auth","data":{"deleted_at":"2026-08-27T12:30:00Z"}}`,
		"user not uuid": `{"specversion":"1.0","id":"e1","type":"com.ecommerce.auth.user-deleted.v1","source":"/services/auth","data":{"user_id":"not-a-uuid","deleted_at":"2026-08-27T12:30:00Z"}}`,
		"no deleted_at": `{"specversion":"1.0","id":"e1","type":"com.ecommerce.auth.user-deleted.v1","source":"/services/auth","data":{"user_id":"` + testUserID + `"}}`,
	}

	for name, body := range cases {
		store := &recordingStore{}
		if err := newTestHandler(store).Handle(context.Background(), message(body)); err != nil {
			t.Fatalf("%s: an invalid event must be skipped, got %v", name, err)
		}
		if len(store.purged) != 0 {
			t.Fatalf("%s: a cart was purged from an invalid event", name)
		}
	}
}

// A Redis failure must leave the offset uncommitted: silently skipping a
// privacy deletion would leave the cart behind until its TTL.
func TestHandlerReturnsStoreFailuresSoTheMessageIsRedelivered(t *testing.T) {
	store := &recordingStore{err: errors.New("redis down")}

	if err := newTestHandler(store).Handle(context.Background(), message(userDeletedEvent)); err == nil {
		t.Fatal("a store failure was swallowed")
	}
}
