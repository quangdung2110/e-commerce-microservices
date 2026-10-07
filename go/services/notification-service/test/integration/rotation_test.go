//go:build integration

package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/decide"
	"github.com/ecommerce/notification-service/internal/domain/service/ingest"
	"github.com/ecommerce/notification-service/internal/domain/service/lifecycle"
)

// IT-DEL-03: rotating the privacy HMAC key (v1 → v2 with v1 kept for
// dual-read) under traffic neither splits an order's sequence stream nor loses
// a tombstone written under the old key (09-data-model.md §3, §10).
func TestPostgresPrivacyKeyRotation(t *testing.T) {
	ctx := context.Background()
	cat, err := catalog.Load()
	require.NoError(t, err)
	v1Key, v2Key := []byte("rotation-key-v1-rotation-key-v1-xx"), []byte("rotation-key-v2-rotation-key-v2-xx")
	k1, err := pii.NewKeyring(1, v1Key, nil)
	require.NoError(t, err)
	k2, err := pii.NewKeyring(2, v2Key, map[int16][]byte{1: v1Key})
	require.NoError(t, err)

	clk := clock.System{}
	dec := decide.New(decide.Deps{Catalog: cat, Clock: clk})
	before := ingest.New(testStore, dec, cat, k1, nil, clk, nil, nil)
	after := ingest.New(testStore, dec, cat, k2, nil, clk, nil, nil)

	user := uuid.New()
	orderID := fmt.Sprint(4000000 + time.Now().UnixNano()%899999)
	subject := "orders/" + orderID
	in := func(seq int64) entity.IngestInput {
		data, _ := json.Marshal(orderTemplateData(orderID, "Áo"))
		id := uuid.NewString()
		return entity.IngestInput{
			Source: entity.SourceOrder, EventID: id, EventType: typeRequested, EventTime: time.Now().UTC(),
			Subject: subject, InputKind: entity.InputRequest, Lane: entity.LaneTransactional, Template: "ORDER_CONFIRMED",
			UserID: user.String(), DedupKey: fmt.Sprintf("order_confirmed:%s:%d", orderID, seq), TemplateData: data,
			Sequence: &seq, PayloadSHA256: "digest-" + id,
		}
	}
	ingestOK := func(svc *ingest.Service, seq int64) ingest.Outcome {
		out, err := svc.Ingest(ctx, in(seq))
		require.NoError(t, err)
		return out
	}

	assert.Equal(t, entity.SequenceAdvanced, ingestOK(before, 5).Sequence, "written under v1")
	// Rolling deploy: the new key is active, v1 is still read.
	assert.Equal(t, entity.SequenceSuperseded, ingestOK(after, 4).Sequence, "v1 high-watermark honoured after rotation")
	assert.Equal(t, entity.SequenceAdvanced, ingestOK(after, 6).Sequence)
	// A process still on the v1-only ring cannot read v2 rows: the new ring
	// must reach every process before v2 becomes active anywhere.
	rows, err := testStore.Ordering().SubjectSequences(ctx, entity.SourceOrder, k2.SubjectHashes(entity.SourceOrder, subject))
	require.NoError(t, err)
	require.Len(t, rows, 2)
	assert.EqualValues(t, 2, rows[0].KeyVersion)
	assert.EqualValues(t, 6, rows[0].HighestSequence, "active version holds the merged highest")
	assert.EqualValues(t, 5, rows[1].HighestSequence, "old version row left as it was")

	// A tombstone written by a v1-only process still blocks v2 ingest.
	lc := lifecycle.New(lifecycle.Deps{Store: testStore, Keyring: k1, Clock: clk})
	_, err = lc.HandleUserDeleted(ctx, user)
	require.NoError(t, err)
	out := ingestOK(after, 7)
	assert.Equal(t, entity.DecisionSkipped, out.Decision)
	assert.Equal(t, entity.ReasonUserDeleted, out.Reason)

	// And one written by a v2 process is visible to v1 readers too (dual-write).
	user2 := uuid.New()
	lc2 := lifecycle.New(lifecycle.Deps{Store: testStore, Keyring: k2, Clock: clk})
	_, err = lc2.HandleUserDeleted(ctx, user2)
	require.NoError(t, err)
	found, err := testStore.Tombstones().Exists(ctx, k1.UserHashes(user2.String()))
	require.NoError(t, err)
	assert.True(t, found, "tombstones are written under every configured key version")
}
