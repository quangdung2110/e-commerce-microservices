package handlers

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"github.com/IBM/sarama"

	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
)

// Review message types on t.review.events that Notification maps 1-1
// (contracts/events/review/v1, catalog source_message).
const (
	TypeReviewReplyRequested   = "com.ecommerce.review.reply-notification-requested.v1"
	TypeReviewRemovalRequested = "com.ecommerce.review.removal-notification-requested.v1"

	reviewMessageReply   = "reviewReplyNotificationRequested"
	reviewMessageRemoval = "reviewRemovalNotificationRequested"
)

type reviewData struct {
	ReviewID        string `json:"review_id"`
	ProductID       string `json:"product_id"`
	RecipientUserID string `json:"recipient_user_id"`
}

// Review maps the two Review notification requests to REVIEW_REPLIED /
// REVIEW_REMOVED. The Review contract carries no URL although the template
// schema requires review_url, so Notification assembles it from the frontend
// base URL (same exception as CART_ABANDONED — flagged as a doc item).
func Review(deps HandlerDeps) kafka.MessageHandler {
	return func(ctx context.Context, msg *sarama.ConsumerMessage) error {
		ctx, env, err := parse(ctx, deps, msg)
		if err != nil {
			return err
		}
		var message string
		switch env.Type {
		case TypeReviewReplyRequested:
			message = reviewMessageReply
		case TypeReviewRemovalRequested:
			message = reviewMessageRemoval
		default:
			logging.FromContext(ctx).Debug("ignoring a review event this consumer does not map")
			return nil
		}
		var data reviewData
		if err := decodeData(env, &data); err != nil {
			return err
		}
		tpl, ok := deps.Catalog.ByReviewMessage(message)
		if !ok {
			return fmt.Errorf("handlers: catalog has no template for review message %s", message)
		}

		in := base(env, entity.InputRequest)
		in.Lane = tpl.Lane
		in.Template = tpl.ID
		in.UserID = data.RecipientUserID
		in.DedupKey = dedupKey(tpl, map[string]string{"review_id": data.ReviewID})
		td := map[string]string{"review_id": data.ReviewID, "product_id": data.ProductID}
		if message == reviewMessageReply {
			td["review_url"] = ReviewURL(deps.Frontend.BaseURL, data.ProductID, data.ReviewID)
		}
		in.TemplateData = mustJSON(td)
		return runIngest(ctx, deps, in)
	}
}

// ReviewURL is the frontend deep link to a review on its product page.
func ReviewURL(baseURL, productID, reviewID string) string {
	return strings.TrimRight(baseURL, "/") + "/products/" + url.PathEscape(productID) + "?review=" + url.QueryEscape(reviewID)
}
