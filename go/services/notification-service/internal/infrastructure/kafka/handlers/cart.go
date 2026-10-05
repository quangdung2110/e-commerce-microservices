package handlers

import (
	"context"
	"strconv"
	"strings"

	"github.com/IBM/sarama"

	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
)

// TypeCartAbandoned is the one fact in the phase-1 mapping table
// (02-pipeline.md §2.2).
const TypeCartAbandoned = "com.ecommerce.cart.abandoned.v1"

type cartAbandonedData struct {
	CartID           string `json:"cart_id"`
	UserID           string `json:"user_id"`
	AbandonmentCycle int    `json:"abandonment_cycle"`
	OccurredAt       string `json:"occurred_at"`
}

// Cart is the fact consumer for t.cart.events. Facts with a catalog mapping
// (source_type) become an IngestInput with template, lane and dedup key from
// the mapping; every other fact is ingested with an empty template so the
// request row records IGNORED (unmapped_fact) — not an error, no alert.
func Cart(deps HandlerDeps) kafka.MessageHandler {
	return func(ctx context.Context, msg *sarama.ConsumerMessage) error {
		ctx, env, err := parse(ctx, deps, msg)
		if err != nil {
			return err
		}
		in := base(env, entity.InputFact)
		tpl, ok := deps.Catalog.ByFactType(env.Type)
		if !ok {
			logging.FromContext(ctx).Debug("fact has no template mapping")
			return runIngest(ctx, deps, in)
		}

		// Phase 1 has exactly one fact shape; a second fact type gets its own
		// extractor keyed by tpl.SourceType.
		var data cartAbandonedData
		if err := decodeData(env, &data); err != nil {
			return err
		}
		in.Lane = tpl.Lane
		in.Template = tpl.ID
		in.UserID = data.UserID
		cycle := strconv.Itoa(data.AbandonmentCycle)
		in.DedupKey = dedupKey(tpl, map[string]string{"cart_id": data.CartID, "abandonment_cycle": cycle})
		// cart_url is the one URL Notification assembles itself (07-templates §2).
		in.TemplateData = mustJSON(map[string]any{
			"cart_id":           data.CartID,
			"abandonment_cycle": data.AbandonmentCycle,
			"cart_url":          CartURL(deps.Frontend.BaseURL, deps.Frontend.CartPath),
		})
		return runIngest(ctx, deps, in)
	}
}

// CartURL joins the frontend base URL and cart path.
func CartURL(baseURL, cartPath string) string {
	if cartPath == "" {
		cartPath = "/cart"
	}
	return strings.TrimRight(baseURL, "/") + "/" + strings.TrimLeft(cartPath, "/")
}
