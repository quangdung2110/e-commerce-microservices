package ingest

import (
	"context"
	"strconv"
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"

	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// ---- Order sequence ledger + tombstone fakes --------------------------------

func seqPtr(v int64) *int64 { return &v }

func ledgerKey(source string, v int16, hash string) string {
	return source + "|" + strconv.Itoa(int(v)) + "|" + hash
}

type fakeOrdering struct {
	port.OrderingRepository
	tx *fakeTx
}

func (r fakeOrdering) LockSubject(context.Context, string, []pii.KeyedHash) error {
	r.tx.locks++
	return nil
}

func (r fakeOrdering) SubjectSequences(_ context.Context, source string, keys []pii.KeyedHash) ([]entity.SubjectSequence, error) {
	var out []entity.SubjectSequence
	for _, k := range keys {
		key := ledgerKey(source, k.Version, k.Hash)
		if v, ok := r.tx.pLedger[key]; ok {
			out = append(out, v)
		} else if v, ok := r.tx.ledger[key]; ok {
			out = append(out, v)
		}
	}
	return out, nil
}

func (r fakeOrdering) SaveSubjectSequence(_ context.Context, s *entity.SubjectSequence) error {
	r.tx.pLedger[ledgerKey(s.Source, s.KeyVersion, s.KeyHash)] = *s
	return nil
}

type fakeTombstones struct {
	port.TombstoneRepository
	tx *fakeTx
}

func (r fakeTombstones) Exists(_ context.Context, keys []pii.KeyedHash) (bool, error) {
	for _, k := range keys {
		if r.tx.tombstones[ledgerKey("user", k.Version, k.Hash)] {
			return true, nil
		}
	}
	return false, nil
}

func orderWith(seq int64, eventID, digest string) entity.IngestInput {
	in := orderConfirmed()
	in.Sequence, in.EventID, in.PayloadSHA256 = seqPtr(seq), eventID, digest
	return in
}

func mustIngest(t *testing.T, e *env, in entity.IngestInput) Outcome {
	t.Helper()
	out, err := e.svc.Ingest(context.Background(), in)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func (e *env) activeLedger(t *testing.T) (entity.SubjectSequence, bool) {
	t.Helper()
	active := e.keyring.SubjectHashes(entity.SourceOrder, "orders/1000234")[0]
	row, ok := e.st.t.ledger[ledgerKey(entity.SourceOrder, active.Version, active.Hash)]
	return row, ok
}

// ---- Order sequence (05-ordering.md §4; IT-CTR-07…09, IT-ORD-03) ------------

func TestIngest_OrderAdvancedCarriesOrderingKey(t *testing.T) {
	e := newEnv(t)
	out := mustIngest(t, e, orderWith(7, "evt-7", "d7"))
	if out.Decision != entity.DecisionDecided || out.Sequence != entity.SequenceAdvanced {
		t.Fatalf("outcome %+v", out)
	}
	if e.st.t.locks != 1 {
		t.Fatalf("subject must be locked once, got %d", e.st.t.locks)
	}
	wantKey := e.keyring.OrderingKey(entity.SourceOrder, "orders/1000234")
	for _, d := range e.st.t.deliveries {
		if d.OrderingKey == nil || *d.OrderingKey != wantKey {
			t.Fatalf("ordering key %v, want %s", d.OrderingKey, wantKey)
		}
		if d.Source != entity.SourceOrder || d.SourceSequence == nil || *d.SourceSequence != 7 {
			t.Fatalf("delivery source/sequence %+v", d)
		}
	}
	if r := e.st.t.requests[0]; r.SourceSequence == nil || *r.SourceSequence != 7 {
		t.Fatalf("request sequence %+v", r.SourceSequence)
	}
	row, ok := e.activeLedger(t)
	if !ok || row.HighestSequence != 7 || row.HighestEventID != "evt-7" || row.HighestPayloadSHA256 != "d7" {
		t.Fatalf("ledger %+v %v", row, ok)
	}
	if got := metricValue(t, e.metrics.SubjectSequenceEvents.WithLabelValues(entity.SourceOrder, "advanced")); got != 1 {
		t.Fatalf("metric advanced = %v", got)
	}
}

func TestIngest_OrderMissingSequence(t *testing.T) {
	for name, mutate := range map[string]func(*entity.IngestInput){
		"absent":     func(in *entity.IngestInput) { in.Sequence = nil },
		"zero":       func(in *entity.IngestInput) { in.Sequence = seqPtr(0) },
		"no subject": func(in *entity.IngestInput) { in.Subject = "" },
	} {
		t.Run(name, func(t *testing.T) {
			e := newEnv(t)
			in := orderConfirmed()
			mutate(&in)
			out := mustIngest(t, e, in)
			if out.Decision != entity.DecisionFailed || out.Reason != entity.ReasonMissingSequence || out.Sequence != entity.SequenceMissing {
				t.Fatalf("outcome %+v", out)
			}
			if len(e.st.t.deliveries) != 0 || len(e.st.t.ledger) != 0 || len(e.st.t.dedupKeys) != 0 {
				t.Fatal("a request without sequence must not create deliveries, ledger rows or dedup keys")
			}
			if got := metricValue(t, e.metrics.SubjectSequenceEvents.WithLabelValues(entity.SourceOrder, "missing")); got != 1 {
				t.Fatalf("metric missing = %v", got)
			}
		})
	}
}

func TestIngest_OrderCollision(t *testing.T) {
	e := newEnv(t)
	mustIngest(t, e, orderWith(7, "evt-7", "d7"))
	for name, in := range map[string]entity.IngestInput{
		"other event":   orderWith(7, "evt-7b", "d7"),
		"other payload": orderWith(7, "evt-7c", "d7-changed"),
	} {
		out := mustIngest(t, e, in)
		if out.Decision != entity.DecisionFailed || out.Reason != entity.ReasonSequenceCollision || out.Sequence != entity.SequenceCollision {
			t.Fatalf("%s: outcome %+v", name, out)
		}
	}
	if len(e.st.t.deliveries) != 2 {
		t.Fatalf("only the first request has deliveries, got %d", len(e.st.t.deliveries))
	}
	if row, _ := e.activeLedger(t); row.HighestEventID != "evt-7" {
		t.Fatalf("a collision must not overwrite the ledger: %+v", row)
	}
}

// IT-CTR-09 at unit level: ingest_dedup and delivery_dedup purged, the
// highest event replays → durable duplicate, no new delivery.
func TestIngest_OrderReplayAfterDedupPurgeIsDuplicate(t *testing.T) {
	e := newEnv(t)
	mustIngest(t, e, orderWith(7, "evt-7", "d7"))
	e.st.t.ingestKeys = map[string]bool{}
	e.st.t.dedupKeys = map[string]bool{}
	out := mustIngest(t, e, orderWith(7, "evt-7", "d7"))
	if out.Duplicate || out.Decision != entity.DecisionSkipped || out.Reason != entity.ReasonDuplicate || out.Sequence != entity.SequenceDuplicate {
		t.Fatalf("outcome %+v", out)
	}
	if len(e.st.t.deliveries) != 2 || len(e.st.t.requests) != 2 {
		t.Fatalf("timeline row only: requests=%d deliveries=%d", len(e.st.t.requests), len(e.st.t.deliveries))
	}
}

// IT-ORD-03 at unit level: 8 then 7 (new event id) → 7 kept on the timeline,
// every delivery SKIPPED (superseded), Decide not run (no dedup key taken).
func TestIngest_OrderLowerSequenceIsSuperseded(t *testing.T) {
	e := newEnv(t)
	mustIngest(t, e, orderWith(8, "evt-8", "d8"))
	dedupBefore := len(e.st.t.dedupKeys)
	old := orderWith(7, "evt-7", "d7")
	old.Template, old.DedupKey = "ORDER_PAYMENT_FAILED", "order_payment_failed:1000234:1"
	out := mustIngest(t, e, old)
	if out.Decision != entity.DecisionSkipped || out.Reason != entity.ReasonSuperseded || out.Sequence != entity.SequenceSuperseded || len(out.Kept) != 0 {
		t.Fatalf("outcome %+v", out)
	}
	if len(e.st.t.dedupKeys) != dedupBefore {
		t.Fatal("a superseded request must not consume delivery_dedup")
	}
	skipped := 0
	for _, d := range e.st.t.deliveries[2:] {
		if d.Status != entity.StatusSkipped || d.SkipReason == nil || *d.SkipReason != entity.ReasonSuperseded || d.FinishedAt == nil {
			t.Fatalf("superseded delivery %+v", d)
		}
		if d.Template != "ORDER_PAYMENT_FAILED" || d.SourceSequence == nil || *d.SourceSequence != 7 {
			t.Fatalf("superseded delivery identity %+v", d)
		}
		skipped++
	}
	if skipped != 2 {
		t.Fatalf("one SKIPPED row per template channel, got %d", skipped)
	}
	if req := e.st.t.requests[1]; req.ExpiresAt == nil || !req.ExpiresAt.After(now) {
		t.Fatalf("superseded request keeps an expiry: %v", req.ExpiresAt)
	}
	if row, _ := e.activeLedger(t); row.HighestSequence != 8 {
		t.Fatalf("ledger must keep 8, got %d", row.HighestSequence)
	}
}

// Key rotation: a row written under the previous key version is read
// (dual-read) and folded into the active version on the next touch.
func TestIngest_OrderLedgerLazyMergeAcrossKeyVersions(t *testing.T) {
	e := newEnv(t)
	keys := e.keyring.SubjectHashes(entity.SourceOrder, "orders/1000234")
	old := keys[1]
	e.st.t.ledger[ledgerKey(entity.SourceOrder, old.Version, old.Hash)] = entity.SubjectSequence{
		Source: entity.SourceOrder, KeyVersion: old.Version, KeyHash: old.Hash,
		HighestSequence: 5, HighestEventID: "evt-5", HighestPayloadSHA256: "d5"}

	if out := mustIngest(t, e, orderWith(5, "evt-5", "d5")); out.Sequence != entity.SequenceDuplicate {
		t.Fatalf("old-version highest must be honoured: %+v", out)
	}
	merged, ok := e.activeLedger(t)
	if !ok || merged.HighestSequence != 5 || merged.HighestEventID != "evt-5" {
		t.Fatalf("active version not folded in: %+v %v", merged, ok)
	}
	if out := mustIngest(t, e, orderWith(4, "evt-4", "d4")); out.Sequence != entity.SequenceSuperseded {
		t.Fatalf("4 < 5: %+v", out)
	}
	if out := mustIngest(t, e, orderWith(6, "evt-6", "d6")); out.Sequence != entity.SequenceAdvanced {
		t.Fatalf("6 > 5: %+v", out)
	}
}

func TestIngest_OtherSourcesIgnoreTheLedger(t *testing.T) {
	e := newEnv(t)
	out := mustIngest(t, e, emailChanged())
	if out.Decision != entity.DecisionDecided || out.Sequence != "" || e.st.t.locks != 0 {
		t.Fatalf("auth request must not touch the ledger: %+v locks=%d", out, e.st.t.locks)
	}
	for _, d := range e.st.t.deliveries {
		if d.OrderingKey != nil || d.Source != "/services/auth" {
			t.Fatalf("non-Order delivery %+v", d)
		}
	}
}

// ---- deleted-user tombstone (09-data-model.md §10, IT-DEL-02) ---------------

func TestIngest_TombstonedUserIsSanitisedAndSkipped(t *testing.T) {
	e := newEnv(t)
	for _, k := range e.keyring.UserHashes(userID.String())[1:] { // old key version only
		e.st.t.tombstones[ledgerKey("user", k.Version, k.Hash)] = true
	}
	out := mustIngest(t, e, orderWith(9, "evt-9", "d9"))
	if out.Decision != entity.DecisionSkipped || out.Reason != entity.ReasonUserDeleted {
		t.Fatalf("outcome %+v", out)
	}
	if len(e.st.t.deliveries) != 0 || len(e.st.t.ledger) != 0 || e.st.t.locks != 0 {
		t.Fatal("tombstoned request: no delivery, no ledger write")
	}
	req := e.st.t.requests[0]
	if req.UserID != nil || req.Subject != nil || req.TemplateData != nil || req.DedupKey != nil || req.SourceRef != nil {
		t.Fatalf("request keeps identifying data: %+v", req)
	}
	if req.Decision != entity.DecisionSkipped || req.DecisionReason == nil || *req.DecisionReason != entity.ReasonUserDeleted {
		t.Fatalf("request decision %+v", req)
	}
}

// metricValue reads a counter or gauge without prometheus/testutil (which
// would pull an unpinned module into go.mod).
func metricValue(t *testing.T, m prometheus.Metric) float64 {
	t.Helper()
	var out dto.Metric
	if err := m.Write(&out); err != nil {
		t.Fatalf("write metric: %v", err)
	}
	if out.Counter != nil {
		return out.Counter.GetValue()
	}
	return out.Gauge.GetValue()
}
