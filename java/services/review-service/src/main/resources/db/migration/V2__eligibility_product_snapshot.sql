-- Review Service — product display snapshot on the entitlement
-- doc_id: review-schema-0002
--
-- ORDER_COMPLETED items now carry the product name and thumbnail the buyer saw at
-- purchase time. Review stores them next to variant_name_snapshot so the buyer's
-- "to review" and "my reviews" screens can show what is being reviewed without any
-- synchronous call to Order or Product. Both columns are nullable: entitlements granted
-- before this migration, and events from producers that have no snapshot, carry none.

SET search_path TO review, public;

ALTER TABLE review_eligibilities
    ADD COLUMN product_name_snapshot      VARCHAR(255),
    ADD COLUMN product_image_url_snapshot VARCHAR(2048);
