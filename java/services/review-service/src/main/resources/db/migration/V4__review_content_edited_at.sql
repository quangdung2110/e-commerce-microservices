-- Review Service — author edit time is independent of moderation and privacy updates.
-- doc_id: review-schema-0004
-- Apply transactionally (Flyway's PostgreSQL default). ALTER TABLE holds its lock until
-- commit, so no application write can bypass the temporarily disabled timestamp trigger.
SET search_path TO review, public;

ALTER TABLE reviews ADD COLUMN content_edited_at TIMESTAMPTZ;
ALTER TABLE reviews ADD CONSTRAINT ck_review_content_edited_at
    CHECK (content_edited_at IS NULL OR edit_count = 1);

-- An old updated_at is not author-edit evidence. Only the original, retained
-- REVIEW_UPDATED transaction can recover that fact. Missing/conflicting history stays
-- NULL; neither a moderation timestamp nor migration time is substituted.
ALTER TABLE reviews DISABLE TRIGGER trg_review_updated_at;
UPDATE reviews r
   SET content_edited_at = edits.edited_at
  FROM (
        SELECT aggregate_id, min(created_at) AS edited_at
          FROM review_outbox
         WHERE aggregate_type = 'REVIEW'
           AND event_type = 'com.ecommerce.review.updated.v1'
         GROUP BY aggregate_id
        HAVING count(DISTINCT created_at) = 1
       ) edits
 WHERE r.id::text = edits.aggregate_id
   AND r.edit_count = 1;
ALTER TABLE reviews ENABLE TRIGGER trg_review_updated_at;
