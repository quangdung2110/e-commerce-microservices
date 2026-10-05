package ingest

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// applySequence runs the Order sequence ledger (05-ordering.md §4,
// 09-data-model.md §3) inside the consumer transaction:
//
//	missing    — no sequence >= 1 (or no subject to key it by)
//	advanced   — higher than anything seen: stored, Decide runs
//	duplicate  — same sequence, same event id and payload digest (a replay
//	             that outlived ingest_dedup): no delivery
//	collision  — same sequence, different event or payload: producer bug
//	superseded — lower than the highest: timeline only, deliveries SKIPPED
//
// Every key version is locked and read (dual-read); the active version always
// ends up holding the highest values, which folds rows written under an older
// key into the current one the first time the subject is touched again.
func (s *Service) applySequence(ctx context.Context, tx port.Tx, in entity.IngestInput) (entity.SequenceOutcome, error) {
	if in.Sequence == nil || *in.Sequence < 1 || in.Subject == "" {
		return entity.SequenceMissing, nil
	}
	keys := s.keyring.SubjectHashes(in.Source, in.Subject)
	active := keys[0]
	repo := tx.Ordering()
	if err := repo.LockSubject(ctx, in.Source, keys); err != nil {
		return "", fmt.Errorf("ingest: lock subject sequence: %w", err)
	}
	rows, err := repo.SubjectSequences(ctx, in.Source, keys)
	if err != nil {
		return "", fmt.Errorf("ingest: read subject sequence: %w", err)
	}
	var top, current *entity.SubjectSequence
	for i := range rows {
		r := &rows[i]
		if top == nil || r.HighestSequence > top.HighestSequence {
			top = r
		}
		if r.KeyVersion == active.Version && r.KeyHash == active.Hash {
			current = r
		}
	}

	seq := *in.Sequence
	var outcome entity.SequenceOutcome
	next := entity.SubjectSequence{Source: in.Source, KeyVersion: active.Version, KeyHash: active.Hash}
	switch {
	case top == nil || seq > top.HighestSequence:
		outcome = entity.SequenceAdvanced
		next.HighestSequence, next.HighestEventID, next.HighestPayloadSHA256 = seq, in.EventID, in.PayloadSHA256
	case seq == top.HighestSequence:
		outcome = entity.SequenceCollision
		if top.HighestEventID == in.EventID && top.HighestPayloadSHA256 == in.PayloadSHA256 {
			outcome = entity.SequenceDuplicate
		}
	default:
		outcome = entity.SequenceSuperseded
	}
	if outcome != entity.SequenceAdvanced {
		next.HighestSequence, next.HighestEventID, next.HighestPayloadSHA256 = top.HighestSequence, top.HighestEventID, top.HighestPayloadSHA256
	}
	if current == nil || current.HighestSequence != next.HighestSequence ||
		current.HighestEventID != next.HighestEventID || current.HighestPayloadSHA256 != next.HighestPayloadSHA256 {
		next.UpdatedAt = s.clock.Now()
		if err := repo.SaveSubjectSequence(ctx, &next); err != nil {
			return "", fmt.Errorf("ingest: save subject sequence: %w", err)
		}
	}
	return outcome, nil
}

// tombstoned reports whether the recipient was deleted (any key version).
func (s *Service) tombstoned(ctx context.Context, tx port.Tx, in entity.IngestInput) (bool, error) {
	id, err := uuid.Parse(in.UserID)
	if err != nil || id == uuid.Nil {
		return false, nil
	}
	dead, err := tx.Tombstones().Exists(ctx, s.keyring.UserHashes(id.String()))
	if err != nil {
		return false, fmt.Errorf("ingest: check tombstone: %w", err)
	}
	return dead, nil
}

// sanitizedRequest records a request for a deleted user without anything that
// identifies the person or the order: no user, subject, template data, dedup
// key (it embeds the order id) or producer reference.
func sanitizedRequest(in entity.IngestInput, now time.Time) *entity.Request {
	req := newRequest(in, now)
	req.UserID, req.Subject, req.TemplateData, req.DedupKey, req.SourceRef = nil, nil, nil, nil, nil
	req.Decision = entity.DecisionSkipped
	req.DecisionReason = strPtr(entity.ReasonUserDeleted)
	return req
}

// conclude ends a request before Decide without any delivery row.
func (s *Service) conclude(ctx context.Context, tx port.Tx, req *entity.Request, out *Outcome, decision entity.Decision, reason string) error {
	req.Decision = decision
	req.DecisionReason = strPtr(reason)
	if err := tx.Requests().UpdateDecision(ctx, req); err != nil {
		return fmt.Errorf("ingest: update decision: %w", err)
	}
	out.Decision, out.Reason = decision, reason
	return nil
}

// supersede keeps a lower-sequence Order request on the timeline: the request
// is SKIPPED (superseded) and every channel of its template gets a SKIPPED
// (superseded) delivery row. Decide does not run, so the dedup ledger and the
// frequency cap are not consumed by a notification that will never be sent.
func (s *Service) supersede(ctx context.Context, tx port.Tx, req *entity.Request, in entity.IngestInput, now time.Time, out *Outcome) error {
	tpl, ok := s.catalog.Get(in.Template)
	expires := now
	if ok {
		if tpl.MaxAge > 0 {
			expires = now.Add(tpl.MaxAge)
		}
		if in.ExpiresAt != nil && in.ExpiresAt.Before(expires) {
			expires = *in.ExpiresAt
		}
		req.ExpiresAt = &expires
	}
	if err := s.conclude(ctx, tx, req, out, entity.DecisionSkipped, entity.ReasonSuperseded); err != nil {
		return err
	}
	if !ok {
		return nil
	}
	res := entity.DecideResult{EffectiveExpiresAt: expires}
	if id, err := uuid.Parse(in.UserID); err == nil {
		res.UserID = id
	}
	for _, ch := range tpl.Channels {
		cd := entity.ChannelDecision{Channel: ch, Keep: false, SkipReason: entity.ReasonSuperseded, NextAttemptAt: now}
		d := newDelivery(req, in, res, cd, now)
		if res.UserID == uuid.Nil {
			d.UserID = nil
		}
		if err := tx.Deliveries().Insert(ctx, d); err != nil {
			return fmt.Errorf("ingest: insert superseded delivery %s: %w", ch, err)
		}
	}
	return nil
}
