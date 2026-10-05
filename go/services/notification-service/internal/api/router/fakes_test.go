package router_test

import (
	"context"
	"encoding/json"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"

	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/status"
)

// ---- inbox repository + counter (drive the real inbox service) ------------

type fakeInboxRepo struct {
	mu    sync.Mutex
	items map[uuid.UUID]*entity.InboxItem
}

func newFakeInboxRepo() *fakeInboxRepo {
	return &fakeInboxRepo{items: map[uuid.UUID]*entity.InboxItem{}}
}

func (r *fakeInboxRepo) Insert(_ context.Context, it *entity.InboxItem) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	cp := *it
	r.items[it.ID] = &cp
	return nil
}

func (r *fakeInboxRepo) Get(_ context.Context, userID, id uuid.UUID) (*entity.InboxItem, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	it, ok := r.items[id]
	if !ok || it.UserID != userID {
		return nil, apperr.ErrNotFound
	}
	cp := *it
	return &cp, nil
}

func (r *fakeInboxRepo) List(_ context.Context, userID uuid.UUID, cursor *entity.InboxCursor, limit int, unreadOnly bool) (*entity.InboxPage, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var all []entity.InboxItem
	for _, it := range r.items {
		if it.UserID != userID || it.ArchivedAt != nil {
			continue
		}
		if unreadOnly && it.ReadAt != nil {
			continue
		}
		all = append(all, *it)
	}
	sort.Slice(all, func(i, j int) bool {
		if !all[i].CreatedAt.Equal(all[j].CreatedAt) {
			return all[i].CreatedAt.After(all[j].CreatedAt)
		}
		return all[i].ID.String() > all[j].ID.String()
	})
	if cursor != nil {
		var rest []entity.InboxItem
		for _, it := range all {
			if it.CreatedAt.Before(cursor.CreatedAt) || (it.CreatedAt.Equal(cursor.CreatedAt) && it.ID.String() < cursor.ID.String()) {
				rest = append(rest, it)
			}
		}
		all = rest
	}
	page := &entity.InboxPage{}
	if len(all) > limit {
		page.Items = all[:limit]
		last := page.Items[len(page.Items)-1]
		page.NextCursor = &entity.InboxCursor{CreatedAt: last.CreatedAt, ID: last.ID}
	} else {
		page.Items = all
	}
	return page, nil
}

func (r *fakeInboxRepo) SetRead(_ context.Context, userID, id uuid.UUID, read bool, now time.Time) (*entity.InboxItem, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	it, ok := r.items[id]
	if !ok || it.UserID != userID || it.ArchivedAt != nil {
		return nil, false, apperr.ErrNotFound
	}
	changed := false
	if read && it.ReadAt == nil {
		t := now
		it.ReadAt = &t
		changed = true
	} else if !read && it.ReadAt != nil {
		it.ReadAt = nil
		changed = true
	}
	cp := *it
	return &cp, changed, nil
}

func (r *fakeInboxRepo) Archive(_ context.Context, userID, id uuid.UUID, now time.Time) (bool, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	it, ok := r.items[id]
	if !ok || it.UserID != userID {
		return false, false, apperr.ErrNotFound
	}
	if it.ArchivedAt != nil {
		return false, false, nil
	}
	wasUnread := it.ReadAt == nil
	t := now
	it.ArchivedAt = &t
	return wasUnread, true, nil
}

func (r *fakeInboxRepo) MarkAllRead(_ context.Context, userID uuid.UUID, now time.Time) (int64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var n int64
	for _, it := range r.items {
		if it.UserID == userID && it.ArchivedAt == nil && it.ReadAt == nil {
			t := now
			it.ReadAt = &t
			n++
		}
	}
	return n, nil
}

func (r *fakeInboxRepo) CountUnread(_ context.Context, userID uuid.UUID) (int64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var n int64
	for _, it := range r.items {
		if it.UserID == userID && it.ArchivedAt == nil && it.ReadAt == nil {
			n++
		}
	}
	return n, nil
}

func (r *fakeInboxRepo) DeleteByUser(context.Context, uuid.UUID) (int64, error) { return 0, nil }

type fakeUnread struct {
	mu     sync.Mutex
	values map[uuid.UUID]int64
	fail   bool
}

func newFakeUnread() *fakeUnread { return &fakeUnread{values: map[uuid.UUID]int64{}} }

func (u *fakeUnread) Get(_ context.Context, userID uuid.UUID) (int64, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.fail {
		return 0, apperr.ErrCacheMiss
	}
	v, ok := u.values[userID]
	if !ok {
		return 0, apperr.ErrCacheMiss
	}
	return v, nil
}

func (u *fakeUnread) Set(_ context.Context, userID uuid.UUID, count int64) error {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.values[userID] = count
	return nil
}

func (u *fakeUnread) Add(_ context.Context, userID uuid.UUID, delta int64) error {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.values[userID] += delta
	return nil
}

func (u *fakeUnread) Delete(_ context.Context, userID uuid.UUID) error {
	u.mu.Lock()
	defer u.mu.Unlock()
	delete(u.values, userID)
	return nil
}

// ---- ops repository (idempotency + audit) ---------------------------------

type fakeOpsRepo struct {
	mu       sync.Mutex
	controls map[string][]byte
	idem     map[string]*entity.IdempotencyRecord
	audits   []entity.AuditEntry
}

func newFakeOpsRepo() *fakeOpsRepo {
	return &fakeOpsRepo{controls: map[string][]byte{}, idem: map[string]*entity.IdempotencyRecord{}}
}

func (o *fakeOpsRepo) GetControl(_ context.Context, key string) ([]byte, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	v, ok := o.controls[key]
	if !ok {
		return nil, apperr.ErrNotFound
	}
	return v, nil
}

func (o *fakeOpsRepo) SetControl(_ context.Context, key string, value []byte, _ string, _ time.Time) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.controls[key] = value
	return nil
}

func (o *fakeOpsRepo) ListControls(_ context.Context, prefix string) (map[string][]byte, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	out := map[string][]byte{}
	for k, v := range o.controls {
		if len(k) >= len(prefix) && k[:len(prefix)] == prefix {
			out[k] = v
		}
	}
	return out, nil
}

func (o *fakeOpsRepo) BeginIdempotent(_ context.Context, key, hash string, now time.Time) (*entity.IdempotencyRecord, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if rec, ok := o.idem[key]; ok {
		cp := *rec
		return &cp, nil
	}
	o.idem[key] = &entity.IdempotencyRecord{Key: key, RequestHash: hash, CreatedAt: now}
	return nil, nil
}

func (o *fakeOpsRepo) CompleteIdempotent(_ context.Context, key string, status int, response []byte) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	rec, ok := o.idem[key]
	if !ok {
		return apperr.ErrNotFound
	}
	rec.Status = status
	rec.Response = json.RawMessage(append([]byte(nil), response...))
	return nil
}

func (o *fakeOpsRepo) PurgeIdempotent(context.Context, time.Time) (int64, error) { return 0, nil }

func (o *fakeOpsRepo) InsertAudit(_ context.Context, e *entity.AuditEntry) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.audits = append(o.audits, *e)
	return nil
}

func (o *fakeOpsRepo) auditCount() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return len(o.audits)
}

// ---- lane control ----------------------------------------------------------

type fakeLaneControl struct {
	mu     sync.Mutex
	lanes  map[entity.Lane]entity.LaneState
	replay entity.ReplayMode
}

func newFakeLaneControl() *fakeLaneControl {
	return &fakeLaneControl{lanes: map[entity.Lane]entity.LaneState{}}
}

func (l *fakeLaneControl) LaneState(_ context.Context, lane entity.Lane) (entity.LaneState, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	s, ok := l.lanes[lane]
	if !ok {
		return entity.LaneState{}, apperr.ErrNotFound
	}
	return s, nil
}

func (l *fakeLaneControl) SetLaneState(_ context.Context, s entity.LaneState) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lanes[s.Lane] = s
	return nil
}

func (l *fakeLaneControl) ReplayMode(context.Context) (entity.ReplayMode, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.replay, nil
}

func (l *fakeLaneControl) SetReplayMode(_ context.Context, m entity.ReplayMode, _ string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.replay = m
	return nil
}

// ---- suppressions ------------------------------------------------------------

type fakeSuppressions struct {
	mu   sync.Mutex
	rows map[uuid.UUID]entity.Suppression
}

func newFakeSuppressions() *fakeSuppressions {
	return &fakeSuppressions{rows: map[uuid.UUID]entity.Suppression{}}
}

func (s *fakeSuppressions) Insert(_ context.Context, row *entity.Suppression) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.rows[row.ID] = *row
	return nil
}

func (s *fakeSuppressions) GetByID(_ context.Context, id uuid.UUID) (*entity.Suppression, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	row, ok := s.rows[id]
	if !ok {
		return nil, apperr.ErrNotFound
	}
	return &row, nil
}

func (s *fakeSuppressions) ActiveByUser(context.Context, uuid.UUID) ([]entity.Suppression, error) {
	return nil, nil
}
func (s *fakeSuppressions) ActiveByHash(context.Context, string) ([]entity.Suppression, error) {
	return nil, nil
}
func (s *fakeSuppressions) MarkSuperseded(context.Context, uuid.UUID, time.Time) error { return nil }
func (s *fakeSuppressions) Delete(_ context.Context, id uuid.UUID) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.rows, id)
	return nil
}
func (s *fakeSuppressions) DetachUser(context.Context, uuid.UUID) (int64, error) { return 0, nil }

// ---- deliveries (only what ops/status use) -----------------------------------

type fakeDeliveries struct {
	mu   sync.Mutex
	rows map[uuid.UUID]entity.Delivery
}

func newFakeDeliveries() *fakeDeliveries {
	return &fakeDeliveries{rows: map[uuid.UUID]entity.Delivery{}}
}

func (d *fakeDeliveries) Insert(_ context.Context, row *entity.Delivery) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.rows[row.ID] = *row
	return nil
}

func (d *fakeDeliveries) GetByID(_ context.Context, id uuid.UUID) (*entity.Delivery, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	row, ok := d.rows[id]
	if !ok {
		return nil, apperr.ErrNotFound
	}
	return &row, nil
}

func (d *fakeDeliveries) ListByRequest(_ context.Context, requestID uuid.UUID) ([]entity.Delivery, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	var out []entity.Delivery
	for _, row := range d.rows {
		if row.RequestID == requestID {
			out = append(out, row)
		}
	}
	return out, nil
}

func (d *fakeDeliveries) ListBySubject(_ context.Context, subject, template string, limit int) ([]entity.Delivery, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	var out []entity.Delivery
	for _, row := range d.rows {
		if row.Subject != nil && *row.Subject == subject && (template == "" || row.Template == template) {
			out = append(out, row)
		}
		if len(out) == limit {
			break
		}
	}
	return out, nil
}

func (d *fakeDeliveries) FindByProviderMessage(context.Context, entity.Provider, string) (*entity.Delivery, error) {
	return nil, apperr.ErrNotFound
}
func (d *fakeDeliveries) Claim(context.Context, entity.Lane, entity.Channel, string, int, time.Duration) ([]entity.Delivery, error) {
	return nil, nil
}
func (d *fakeDeliveries) ExtendLease(context.Context, []uuid.UUID, string, time.Duration) error {
	return nil
}
func (d *fakeDeliveries) Release(context.Context, uuid.UUID, string, time.Time, string) (bool, error) {
	return false, nil
}
func (d *fakeDeliveries) MarkSent(context.Context, uuid.UUID, string, entity.Provider, string, string, time.Time, bool) (bool, error) {
	return false, nil
}
func (d *fakeDeliveries) MarkRetry(context.Context, uuid.UUID, string, time.Time, string, string) (bool, error) {
	return false, nil
}
func (d *fakeDeliveries) MarkFinal(context.Context, uuid.UUID, string, entity.DeliveryStatus, string, string, time.Time) (bool, error) {
	return false, nil
}
func (d *fakeDeliveries) MarkDeliveredInApp(context.Context, uuid.UUID, string, string, time.Time) (bool, error) {
	return false, nil
}
func (d *fakeDeliveries) ReapExpiredLeases(context.Context, time.Time) (int64, error) { return 0, nil }
func (d *fakeDeliveries) TransitionTracked(context.Context, uuid.UUID, entity.DeliveryStatus, time.Time, string) (bool, error) {
	return false, nil
}

func (d *fakeDeliveries) Redrive(_ context.Context, id uuid.UUID, newExpiresAt *time.Time, now time.Time) (*entity.Delivery, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	row, ok := d.rows[id]
	if !ok {
		return nil, apperr.ErrNotFound
	}
	if row.Status != entity.StatusFailed && row.Status != entity.StatusExpired {
		return nil, apperr.ErrStateConflict
	}
	if !row.ExpiresAt.After(now) && newExpiresAt == nil {
		return nil, apperr.ErrValidation
	}
	if newExpiresAt != nil {
		row.ExpiresAt = *newExpiresAt
	}
	row.Status = entity.StatusPending
	row.Attempt = 0
	row.NextAttemptAt = now
	d.rows[id] = row
	return &row, nil
}

func (d *fakeDeliveries) CountRedrivable(_ context.Context, f entity.RedriveFilter) (int64, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	var n int64
	for _, row := range d.rows {
		if redrivable(row, f) {
			n++
		}
	}
	return n, nil
}

func (d *fakeDeliveries) RedriveBatch(_ context.Context, f entity.RedriveFilter, now time.Time) (int64, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	var n int64
	for id, row := range d.rows {
		if redrivable(row, f) {
			row.Status = entity.StatusPending
			row.Attempt = 0
			row.NextAttemptAt = now
			d.rows[id] = row
			n++
		}
	}
	return n, nil
}

func (d *fakeDeliveries) SkipPendingByUser(context.Context, uuid.UUID, string, time.Time) (int64, error) {
	return 0, nil
}
func (d *fakeDeliveries) AnonymizeByUser(context.Context, uuid.UUID) (int64, error) { return 0, nil }
func (d *fakeDeliveries) PendingStats(context.Context, time.Time) ([]entity.PendingStat, error) {
	return nil, nil
}

// ---- requests ------------------------------------------------------------------

type fakeRequests struct {
	mu   sync.Mutex
	rows map[uuid.UUID]entity.Request
}

func newFakeRequests() *fakeRequests { return &fakeRequests{rows: map[uuid.UUID]entity.Request{}} }

func (r *fakeRequests) Insert(_ context.Context, row *entity.Request) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rows[row.ID] = *row
	return nil
}
func (r *fakeRequests) UpdateDecision(context.Context, *entity.Request) error { return nil }
func (r *fakeRequests) GetByID(_ context.Context, id uuid.UUID) (*entity.Request, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	row, ok := r.rows[id]
	if !ok {
		return nil, apperr.ErrNotFound
	}
	return &row, nil
}
func (r *fakeRequests) AnonymizeByUser(context.Context, uuid.UUID) (int64, error) { return 0, nil }

// ---- contact resolver ------------------------------------------------------------

type fakeContacts struct {
	known map[uuid.UUID]string
}

func (f *fakeContacts) Contact(_ context.Context, _ entity.Lane, userID uuid.UUID) (*entity.Contact, error) {
	raw, ok := f.known[userID]
	if !ok {
		return nil, apperr.NewPermanent(entity.ReasonNoRecipient, apperr.ErrNoRecipient)
	}
	return &entity.Contact{UserID: userID, Email: pii.MustEmail(raw), EmailVerified: true}, nil
}
func (f *fakeContacts) Content(context.Context, entity.Lane, uuid.UUID) (*entity.AuthContent, error) {
	return nil, apperr.ErrNotFound
}
func (f *fakeContacts) ForgetContact(context.Context, uuid.UUID) error { return nil }

// compile-time check the status Timeline type is what the handler expects.
var _ = status.Timeline{}

// redrivable mirrors the repository rule: FAILED rows of the lane, optional
// reason filter, never a producer-contract failure (R-04).
func redrivable(row entity.Delivery, f entity.RedriveFilter) bool {
	if row.Lane != f.Lane || row.Status != entity.StatusFailed || row.LastErrorCode == nil {
		return false
	}
	if entity.ProducerContractReasons[*row.LastErrorCode] {
		return false
	}
	return f.ReasonCode == "" || *row.LastErrorCode == f.ReasonCode
}
