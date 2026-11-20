-- Review Service — the privacy hard-purge sweep also covers FAILED requests
-- doc_id: review-schema-0003
--
-- A purge that fails marks its request FAILED, and the next daily pass retries it
-- (WF-REVIEW-010, ADR-013). The sweep index of 0001 was partial on status = 'ANONYMIZED',
-- so a FAILED request could not be found through it. It is rebuilt over both states and
-- ordered by the same keyset the sweep pages on, (hard_delete_after, id).

SET search_path TO review, public;

DROP INDEX IF EXISTS ix_privacy_hard_delete_sweep;

CREATE INDEX ix_privacy_hard_delete_sweep
    ON privacy_erasure_requests (hard_delete_after, id)
    WHERE status IN ('ANONYMIZED', 'FAILED');
