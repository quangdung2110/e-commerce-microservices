package com.ecommerce.review.service;

import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.privacy.IdentityHmac;
import com.ecommerce.review.privacy.SubjectKey;
import com.ecommerce.review.repository.PrivacyRepository;
import com.ecommerce.review.repository.SubjectLockRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The one place a deletion guard is taken and checked.
 *
 * <p>Both steps always happen together and in this order: take the subject's
 * transaction lock, then read the guard. Checking without the lock leaves the race the
 * lock exists to close — a write could commit between the check and the insert, just
 * after a privacy sweep looked (ADR-007).
 *
 * <p>The three outcomes are different on purpose. Customer commands are rejected. The
 * Order consumer commits its receipt and suppresses the entitlement. Admin reply and
 * takedown are never rejected: only their notification outbox row is suppressed.
 */
@Service
public class PrivacyGuardService {

    private final SubjectLockRepository subjectLocks;
    private final PrivacyRepository privacyRequests;
    private final IdentityHmac identityHmac;
    private final ReviewMetrics metrics;

    public PrivacyGuardService(SubjectLockRepository subjectLocks,
                               PrivacyRepository privacyRequests,
                               IdentityHmac identityHmac,
                               ReviewMetrics metrics) {
        this.subjectLocks = subjectLocks;
        this.privacyRequests = privacyRequests;
        this.identityHmac = identityHmac;
        this.metrics = metrics;
    }

    public SubjectKey subjectKey(UUID userId) {
        return identityHmac.subjectKey(userId);
    }

    /**
     * Takes the subject lock without reading the guard yet.
     *
     * <p>Used where a step has to run between the lock and the guard check — the Order
     * consumer records its receipt there, so a redelivered event is a no-op before the
     * suppression metric could count it twice.
     */
    public void lock(SubjectKey key) {
        subjectLocks.lock(key);
    }

    /** Reads the guard. The caller must already hold {@link #lock(SubjectKey)}. */
    public boolean isGuarded(SubjectKey key, String operation) {
        boolean guarded = privacyRequests.guardExists(key);
        if (guarded) {
            metrics.privacyGuardBlocked(operation);
        }
        return guarded;
    }

    /**
     * Takes the subject lock and answers whether a guard is committed.
     *
     * <p>Must be called inside the transaction that is about to write.
     */
    public boolean lockAndCheck(UUID userId, String operation) {
        SubjectKey key = identityHmac.subjectKey(userId);
        lock(key);
        return isGuarded(key, operation);
    }

    /**
     * Takes the subject lock and refuses a guarded customer write.
     *
     * <p>The rejection is the generic authorization failure. A dedicated
     * {@code ACCOUNT_DELETED} code would turn the API into an oracle for account
     * deletion, which is the opposite of what the erasure is for (WF-REVIEW-010).
     */
    public void lockAndRequireUnguarded(UUID userId, String operation) {
        if (lockAndCheck(userId, operation)) {
            throw ReviewException.guarded(operation);
        }
    }
}
