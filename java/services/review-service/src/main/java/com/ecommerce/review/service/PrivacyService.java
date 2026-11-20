package com.ecommerce.review.service;

import com.ecommerce.review.domain.PrivacyRequest;
import com.ecommerce.review.messaging.UserDeletedPayload;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.privacy.IdentityHmac;
import com.ecommerce.review.privacy.SubjectKey;
import com.ecommerce.review.repository.EligibilityRepository;
import com.ecommerce.review.repository.EventReceiptRepository;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.repository.PrivacyRepository;
import com.ecommerce.review.repository.ReportRepository;
import com.ecommerce.review.repository.ReviewRepository;
import com.ecommerce.review.repository.TransactionClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WF-REVIEW-010: anonymise immediately, hard-purge on the deadline.
 *
 * <p>Anonymising changes only the display name. It touches neither rating nor aggregate
 * and emits no Review event, because every service that holds personal data receives
 * {@code USER_DELETED} straight from Auth — a Review event would be a second, redundant
 * channel for the same fact.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    private final PrivacyRepository privacyRequests;
    private final EventReceiptRepository receipts;
    private final ReviewRepository reviews;
    private final EligibilityRepository eligibilities;
    private final MediaAssetRepository mediaAssets;
    private final ReportRepository reports;
    private final PrivacyGuardService privacyGuard;
    private final IdentityHmac identityHmac;
    private final TransactionClock clock;
    private final ReviewMetrics metrics;
    private final TransactionTemplate transactions;

    public PrivacyService(PrivacyRepository privacyRequests,
                          EventReceiptRepository receipts,
                          ReviewRepository reviews,
                          EligibilityRepository eligibilities,
                          MediaAssetRepository mediaAssets,
                          ReportRepository reports,
                          PrivacyGuardService privacyGuard,
                          IdentityHmac identityHmac,
                          TransactionClock clock,
                          ReviewMetrics metrics,
                          TransactionTemplate transactions) {
        this.privacyRequests = privacyRequests;
        this.receipts = receipts;
        this.reviews = reviews;
        this.eligibilities = eligibilities;
        this.mediaAssets = mediaAssets;
        this.reports = reports;
        this.privacyGuard = privacyGuard;
        this.identityHmac = identityHmac;
        this.clock = clock;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    /**
     * Consumes one {@code USER_DELETED}.
     *
     * <p>The Auth CloudEvents id is persisted unchanged in both the receipt and
     * {@code source_event_id}. A redelivery of the same id is a receipt conflict and does
     * nothing; a logically duplicate deletion carrying a <em>new</em> id commits its
     * receipt and still keeps exactly one guard, because {@code subject_match_key} is
     * unique.
     */
    @Transactional
    public void applyUserDeleted(UUID eventId, UserDeletedPayload payload,
                                 String topic, int partition, long offset) {
        SubjectKey subjectKey = identityHmac.subjectKey(payload.userId());
        privacyGuard.lock(subjectKey);

        if (!receipts.claim(EventReceiptRepository.PRIVACY_SYNC, eventId, topic, partition, offset)) {
            metrics.consumerDuplicate(EventReceiptRepository.PRIVACY_SYNC);
            return;
        }

        privacyRequests.insertIfAbsent(eventId, payload.userId(), subjectKey,
                payload.reason(), payload.deletedAt(), payload.hardDeleteAfter());

        Instant now = clock.now();
        int anonymized = reviews.anonymizeAuthorOfSubject(payload.userId(),
                IdentityHmac.anonymizedDisplayName(), now);
        privacyRequests.markAnonymized(subjectKey, now);

        log.info("privacy erasure anonymised {} review author names", anonymized);
    }

    /**
     * The hard purge of everything due, in pages of {@code batchSize} until none is left.
     *
     * <p>Each subject is one transaction under its own subject lock. Every erasable table
     * writes its identifier and its {@code *_erased_at} in the same statement: the CHECK
     * pairs refuse a half-written row, and that refusal is deterministic, so a purge that
     * cleared only the identifier would fail identically on every retry while the legal
     * deadline passed (WF-REVIEW-010).
     *
     * <p>A subject whose purge fails is marked {@code FAILED} and is due again on the next
     * pass, which is what turns a transient fault (a lock wait, a lost connection) into a
     * retry rather than a request that waits for a person. It stays counted by the overdue
     * alert until it reaches {@code PURGED}, so a failure that retrying cannot fix remains
     * visible. A page is selected by keyset, so a request that fails again within this pass
     * is not offered twice and cannot starve the requests behind it.
     *
     * @return how many subjects reached {@code PURGED}
     */
    public int purgeDue(int batchSize) {
        int purged = 0;
        Instant now = Instant.now();
        Instant afterHardDeleteAfter = null;
        UUID afterId = null;
        List<PrivacyRequest> due;
        do {
            due = privacyRequests.findDueForPurge(now, afterHardDeleteAfter, afterId, batchSize);
            for (PrivacyRequest request : due) {
                try {
                    // Explicit boundaries: a self-invoked @Transactional method would run
                    // with no transaction, and a half-applied purge is the one failure mode
                    // this whole workflow exists to prevent.
                    transactions.executeWithoutResult(status -> purgeOne(request));
                    purged++;
                } catch (RuntimeException e) {
                    // Never a silent dead-letter: an overdue privacy request is a critical
                    // alert and stays visible until it is PURGED.
                    log.error("privacy hard purge failed for request {}; it is retried on the next pass",
                            request.id(), e);
                    transactions.executeWithoutResult(status -> markFailed(request));
                }
            }
            if (!due.isEmpty()) {
                PrivacyRequest last = due.get(due.size() - 1);
                afterHardDeleteAfter = last.hardDeleteAfter();
                afterId = last.id();
            }
        } while (due.size() == batchSize);
        return purged;
    }

    private void purgeOne(PrivacyRequest request) {
        SubjectKey subjectKey = new SubjectKey(request.subjectMatchKey(), request.subjectKeyVersion());
        privacyGuard.lock(subjectKey);
        Instant now = clock.now();
        UUID subject = request.subjectUserId();
        if (subject == null) {
            return;
        }

        eligibilities.purgeOwner(subject, now);
        mediaAssets.purgeOwner(subject, now);
        reports.purgeReporter(subject, now);
        // The HMAC guard stays behind on purpose: without it, a late or redriven event
        // could write the subject's UUID straight back in (REVIEW-INV-012).
        privacyRequests.markPurged(subjectKey, now);
    }

    private void markFailed(PrivacyRequest request) {
        privacyRequests.markFailed(
                new SubjectKey(request.subjectMatchKey(), request.subjectKeyVersion()),
                "hard purge failed; see review-service logs for request " + request.id());
    }

    /** Feeds the critical overdue alert; it never resolves itself. */
    @Transactional(readOnly = true)
    public long countOverdue() {
        return privacyRequests.countOverdue(Instant.now());
    }
}
