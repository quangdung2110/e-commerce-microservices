package lifecycle

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

type calls struct {
	steps  []string
	failAt string
}

func (c *calls) hit(name string) error {
	c.steps = append(c.steps, name)
	if c.failAt == name {
		return errors.New("boom at " + name)
	}
	return nil
}

type tx struct {
	port.Tx
	c *calls
}

type deliveries struct {
	port.DeliveryRepository
	c *calls
}

func (d deliveries) SkipPendingByUser(_ context.Context, _ uuid.UUID, reason string, _ time.Time) (int64, error) {
	if reason != entity.ReasonUserDeleted {
		return 0, errors.New("wrong reason " + reason)
	}
	return 2, d.c.hit("skip_pending")
}
func (d deliveries) AnonymizeByUser(context.Context, uuid.UUID) (int64, error) {
	return 5, d.c.hit("anon_deliveries")
}

type prefs struct {
	port.PreferenceRepository
	c *calls
}

func (p prefs) Delete(context.Context, uuid.UUID) error { return p.c.hit("delete_pref") }

type inbox struct {
	port.InboxRepository
	c *calls
}

func (i inbox) DeleteByUser(context.Context, uuid.UUID) (int64, error) {
	return 3, i.c.hit("delete_inbox")
}

type requests struct {
	port.RequestRepository
	c *calls
}

func (r requests) AnonymizeByUser(context.Context, uuid.UUID) (int64, error) {
	return 4, r.c.hit("anon_requests")
}

type supp struct {
	port.SuppressionRepository
	c *calls
}

func (s supp) DetachUser(context.Context, uuid.UUID) (int64, error) { return 1, s.c.hit("detach_supp") }

func (t tx) Deliveries() port.DeliveryRepository      { return deliveries{c: t.c} }
func (t tx) Preferences() port.PreferenceRepository   { return prefs{c: t.c} }
func (t tx) Inbox() port.InboxRepository              { return inbox{c: t.c} }
func (t tx) Requests() port.RequestRepository         { return requests{c: t.c} }
func (t tx) Suppressions() port.SuppressionRepository { return supp{c: t.c} }
func (t tx) Tombstones() port.TombstoneRepository     { return tombs{c: t.c} }

type tombs struct {
	port.TombstoneRepository
	c *calls
}

func (x tombs) Lock(context.Context, []pii.KeyedHash) error { return x.c.hit("lock_user") }
func (x tombs) Insert(_ context.Context, keys []pii.KeyedHash, _ time.Time) error {
	if len(keys) != 2 {
		return errors.New("tombstone must be written under every key version")
	}
	return x.c.hit("insert_tombstone")
}

func testKeyring() *pii.Keyring {
	k, err := pii.NewKeyring(2, []byte("lifecycle-test-key-v2-lifecycle-v2"), map[int16][]byte{1: []byte("lifecycle-test-key-v1-lifecycle-v1")})
	if err != nil {
		panic(err)
	}
	return k
}

type store struct {
	port.Store
	c         *calls
	committed bool
}

func (s *store) WithinTx(ctx context.Context, fn func(context.Context, port.Tx) error) error {
	if err := fn(ctx, tx{c: s.c}); err != nil {
		return err
	}
	s.committed = true
	return nil
}

type contacts struct {
	port.ContactResolver
	c *calls
}

func (x contacts) ForgetContact(context.Context, uuid.UUID) error { return x.c.hit("forget_contact") }

type freq struct{ c *calls }

func (f freq) Increment(context.Context, uuid.UUID, string) (int64, bool, error) { return 0, true, nil }
func (f freq) Forget(context.Context, uuid.UUID) error                           { return f.c.hit("forget_freq") }

type unread struct {
	port.UnreadCounter
	c *calls
}

func (u unread) Delete(context.Context, uuid.UUID) error { return u.c.hit("delete_unread") }

func TestHandleUserDeleted_AllStepsInOrder(t *testing.T) {
	c := &calls{}
	st := &store{c: c}
	svc := New(Deps{Store: st, Keyring: testKeyring(), Contacts: contacts{c: c}, FrequencyCap: freq{c: c}, UnreadCounter: unread{c: c}, Clock: clock.NewFake(time.Now())})
	r, err := svc.HandleUserDeleted(context.Background(), uuid.New())
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"lock_user", "insert_tombstone", "skip_pending", "delete_pref", "delete_inbox", "anon_requests", "anon_deliveries", "detach_supp", "forget_contact", "forget_freq", "delete_unread"}
	if len(c.steps) != len(want) {
		t.Fatalf("steps %v", c.steps)
	}
	for i := range want {
		if c.steps[i] != want[i] {
			t.Fatalf("step %d = %s, want %s (all: %v)", i, c.steps[i], want[i], c.steps)
		}
	}
	if !st.committed || r.DeliveriesSkipped != 2 || r.InboxDeleted != 3 || r.RequestsAnonymised != 4 || r.DeliveriesAnonymised != 5 || r.SuppressionsDetached != 1 {
		t.Fatalf("result %+v committed=%v", r, st.committed)
	}
}

func TestHandleUserDeleted_DBErrorAbortsBeforeRedis(t *testing.T) {
	c := &calls{failAt: "anon_requests"}
	st := &store{c: c}
	svc := New(Deps{Store: st, Keyring: testKeyring(), Contacts: contacts{c: c}, FrequencyCap: freq{c: c}, UnreadCounter: unread{c: c}, Clock: clock.NewFake(time.Now())})
	if _, err := svc.HandleUserDeleted(context.Background(), uuid.New()); err == nil {
		t.Fatal("expected error")
	}
	if st.committed {
		t.Fatal("must not commit")
	}
	for _, s := range c.steps {
		if s == "forget_contact" || s == "forget_freq" || s == "delete_unread" {
			t.Fatalf("redis step ran after a failed transaction: %v", c.steps)
		}
	}
}

func TestHandleUserDeleted_RedisErrorsToleratedAndNilPorts(t *testing.T) {
	c := &calls{failAt: "forget_freq"}
	st := &store{c: c}
	svc := New(Deps{Store: st, Keyring: testKeyring(), FrequencyCap: freq{c: c}, Clock: clock.NewFake(time.Now())})
	if _, err := svc.HandleUserDeleted(context.Background(), uuid.New()); err != nil {
		t.Fatalf("redis failure must not fail the event: %v", err)
	}
	if _, err := svc.HandleUserDeleted(context.Background(), uuid.Nil); err == nil {
		t.Fatal("nil user id must be rejected")
	}
}
