// Package handlers turns each source topic's CloudEvents into the one internal
// IngestInput (02-pipeline.md §2.1) or into the preference / lifecycle
// commands, and hands it to the domain. Envelope errors surface as
// cloudevents.ErrInvalidEnvelope (→ DLQ); data-shape errors as
// errors.Permanent (→ DLQ); producer contract errors (unknown template, bad
// user id …) are *not* errors here — Decide records them as FAILED rows.
package handlers

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/catalog"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/ingest"
	"github.com/ecommerce/notification-service/internal/domain/service/lifecycle"
	"github.com/ecommerce/notification-service/internal/domain/service/preference"
	"github.com/ecommerce/notification-service/internal/infrastructure/cloudevents"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
)

// Ingestor is the slice of ingest.Service the handlers use.
type Ingestor interface {
	Ingest(ctx context.Context, in entity.IngestInput) (ingest.Outcome, error)
}

// PreferenceApplier is the slice of preference.Service the handlers use.
type PreferenceApplier interface {
	Apply(ctx context.Context, u preference.Update) (bool, error)
}

// Lifecycle is the slice of lifecycle.Service the handlers use.
type Lifecycle interface {
	HandleUserDeleted(ctx context.Context, userID uuid.UUID) (lifecycle.Result, error)
}

// HandlerDeps is shared by every handler; only the fields a handler needs
// must be set.
type HandlerDeps struct {
	Ingest      Ingestor
	Preferences PreferenceApplier
	Lifecycle   Lifecycle
	Catalog     *catalog.Catalog
	Frontend    config.FrontendConfig
	Logger      *zap.Logger
	Clock       clock.Clock
}

func (d HandlerDeps) logger() *zap.Logger {
	if d.Logger == nil {
		return zap.NewNop()
	}
	return d.Logger
}

// Reason written to x-dlq-reason when the data block does not decode.
const ReasonInvalidPayload = "invalid_payload"

// parse decodes the envelope and attaches correlation ids to the context.
func parse(ctx context.Context, deps HandlerDeps, msg *sarama.ConsumerMessage) (context.Context, *cloudevents.Envelope, error) {
	env, err := cloudevents.Parse(msg.Value)
	if err != nil {
		return ctx, nil, err
	}
	if env.TraceParent == "" {
		if tp, ok := kafka.CopyHeaders(msg)[kafka.HeaderTraceParent]; ok {
			env.TraceParent = tp
		}
	}
	ctx = reqctx.WithRequestID(ctx, env.CorrelationID)
	ctx = reqctx.WithTraceParent(ctx, env.TraceParent)
	logger := deps.logger().With(zap.String("event_id", env.ID), zap.String("event_type", env.Type), zap.String("topic", msg.Topic))
	if env.CorrelationID != "" {
		logger = logger.With(zap.String("correlation_id", env.CorrelationID))
	}
	return logging.WithContext(ctx, logger), env, nil
}

// decodeData unmarshals the data block; a shape mismatch is a permanent
// failure (retrying decodes the same bytes).
func decodeData(env *cloudevents.Envelope, out any) error {
	if err := json.Unmarshal(env.Data, out); err != nil {
		return apperr.NewPermanent(ReasonInvalidPayload, fmt.Errorf("decode %s data: %w", env.Type, err))
	}
	return nil
}

// base fills the envelope-derived fields every IngestInput carries.
func base(env *cloudevents.Envelope, kind entity.InputKind) entity.IngestInput {
	return entity.IngestInput{
		Source:        env.Source,
		EventID:       env.ID,
		EventType:     env.Type,
		EventTime:     env.Time,
		Subject:       env.Subject,
		InputKind:     kind,
		CorrelationID: env.CorrelationID,
		TraceParent:   env.TraceParent,
		Sequence:      env.Sequence,
	}
}

// dedupKey renders a catalog formula such as "cart_abandoned:{cart_id}:{abandonment_cycle}".
func dedupKey(tpl catalog.Template, values map[string]string) string {
	if tpl.DedupMode == catalog.DedupNone || tpl.DedupFormula == "" {
		return ""
	}
	key := tpl.DedupFormula
	for k, v := range values {
		key = strings.ReplaceAll(key, "{"+k+"}", v)
	}
	return key
}

// mustJSON marshals a map that only holds strings/ints; it cannot fail.
func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

// runIngest calls the domain and logs the outcome (ids only).
func runIngest(ctx context.Context, deps HandlerDeps, in entity.IngestInput) error {
	out, err := deps.Ingest.Ingest(ctx, in)
	if err != nil {
		return err
	}
	logger := logging.FromContext(ctx)
	if out.Duplicate {
		logger.Debug("duplicate event acknowledged")
		return nil
	}
	fields := []zap.Field{
		zap.String("request_id", out.RequestID.String()),
		zap.String("template", in.Template),
		zap.String("lane", string(in.Lane)),
		zap.String("decision", string(out.Decision)),
	}
	if out.Reason != "" {
		fields = append(fields, zap.String("reason", out.Reason))
	}
	switch out.Decision {
	case entity.DecisionFailed:
		// Producer contract error: alert on first sight (08-decide.md §7).
		logger.Error("request rejected: producer contract error", fields...)
	default:
		logger.Info("request ingested", fields...)
	}
	return nil
}

// payloadDigest is the hex sha256 of the canonical JSON of a data block:
// object keys sorted, numbers kept verbatim, insignificant whitespace dropped.
// It identifies "the same payload" for the Order sequence ledger even when a
// producer re-serialises the same command (09-data-model.md §3). A block that
// is not JSON is hashed as raw bytes.
func payloadDigest(data json.RawMessage) string {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var v any
	canon := []byte(data)
	if err := dec.Decode(&v); err == nil {
		if b, err := json.Marshal(v); err == nil {
			canon = b
		}
	}
	sum := sha256.Sum256(canon)
	return hex.EncodeToString(sum[:])
}
