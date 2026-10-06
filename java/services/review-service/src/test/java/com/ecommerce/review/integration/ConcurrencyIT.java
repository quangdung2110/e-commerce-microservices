package com.ecommerce.review.integration;

import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.domain.MediaType;
import com.ecommerce.review.domain.ReportReason;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.privacy.IdentityHmac;
import com.ecommerce.review.privacy.SubjectKey;
import com.ecommerce.review.repository.MediaAssetRepository;
import com.ecommerce.review.repository.PrivacyRepository;
import com.ecommerce.review.repository.SubjectLockRepository;
import com.ecommerce.review.service.AdminReplyService;
import com.ecommerce.review.service.MediaAssetService;
import com.ecommerce.review.service.ModerationService;
import com.ecommerce.review.service.ReviewCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The races the design names, run as races.
 *
 * <p>Every one of these has a sequential test elsewhere that proves the outcome. These
 * prove the mechanism: the conditional claim, the {@code FOR UPDATE} row lock and the
 * per-subject advisory lock are what make the outcome hold when two transactions arrive
 * at the same moment, and nothing else in the code would fail if they were removed.
 */
class ConcurrencyIT extends AbstractReviewIT {

    private static final byte[] IMAGE = "concurrency-fixture-bytes".getBytes();

    @Autowired
    private ReviewCommandService reviewCommands;

    @Autowired
    private ModerationService moderation;

    @Autowired
    private AdminReplyService adminReplies;

    @Autowired
    private MediaAssetService mediaAssetService;

    @Autowired
    private MediaAssetRepository mediaAssets;

    @Autowired
    private PrivacyRepository privacyRequests;

    @Autowired
    private SubjectLockRepository subjectLocks;

    @Autowired
    private IdentityHmac identityHmac;

    @Autowired
    private TransactionTemplate transactions;

    private final UUID buyer = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    /**
     * REVIEW-INV-002. The conditional {@code UPDATE ... WHERE status='PENDING'} is the
     * claim, so only one of two simultaneous creates can match a row; the unique index on
     * {@code eligibility_id} is the net underneath it.
     */
    @Test
    void twoSimultaneousCreatesLeaveOneReview() throws Exception {
        UUID eligibilityId = grantEntitlement("order-1", "p-1", "sku-1", buyer);

        List<Outcome> outcomes = runTogether(2, () -> {
            reviewCommands.create(buyer,
                    new CommandDtos.CreateReviewRequest(eligibilityId, 5, "Đồng thời", null));
            return null;
        });

        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM reviews").query(Long.class).single()).isEqualTo(1);
        assertThat(outboxCount(ReviewEventType.REVIEW_CREATED.type())).isEqualTo(1);
        // The loser is told what actually happened, not given a generic failure.
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).findFirst().orElseThrow().failure())
                .isInstanceOf(ReviewException.class);
    }

    /**
     * ADR-008. Counting and hiding happen inside one transaction under the review's row
     * lock, so simultaneous reports around the threshold produce exactly one hide and
     * exactly one {@code REVIEW_HIDDEN}.
     */
    @Test
    void simultaneousReportsAtTheThresholdHideTheReviewOnce() throws Exception {
        UUID eligibilityId = grantEntitlement("order-2", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();

        int reporters = properties.reportThreshold() + 4;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < reporters; i++) {
            ids.add(UUID.randomUUID());
        }
        AtomicInteger next = new AtomicInteger();

        runTogether(reporters, () -> {
            moderation.report(ids.get(next.getAndIncrement()), reviewId,
                    new CommandDtos.CreateReviewReportRequest(ReportReason.SPAM, null));
            return null;
        });

        assertThat(jdbc.sql("SELECT status FROM reviews WHERE id = ?")
                .param(reviewId).query(String.class).single()).isEqualTo("HIDDEN_BY_REPORT");
        assertThat(outboxCount(ReviewEventType.REVIEW_HIDDEN.type())).isEqualTo(1);
        // Reports that arrived after the hide are refused, so the counter cannot run past
        // the threshold and hide the review a second time.
        assertThat(jdbc.sql("SELECT active_report_count FROM reviews WHERE id = ?")
                .param(reviewId).query(Integer.class).single())
                .isEqualTo(properties.reportThreshold());
    }

    /**
     * ADR-011. Attach and the expiry sweep lock the same asset row, and the sweep skips a
     * locked one, so an asset being attached is never swept out from under the attach.
     */
    @Test
    void anAttachInFlightIsNeverSweptByTheExpiryPass() throws Exception {
        UUID eligibilityId = grantEntitlement("order-3", "p-1", "sku-1", buyer);
        CommandDtos.IssuedMediaAsset issued = mediaAssetService.issueUploadInstruction(buyer,
                new CommandDtos.IssueMediaUploadRequest(MediaType.IMAGE, "image/jpeg",
                        (long) IMAGE.length, null));
        upload(issued, IMAGE);
        // Past its deadline, so the sweep would take it if the lock did not stop it.
        jdbc.sql("UPDATE review_media_assets SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(issued.assetId()).update();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch sweepDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The attaching transaction: it locks the asset row and completes the real
            // transition, exactly as create does, while the sweep runs alongside it.
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                mediaAssets.findByIdForUpdate(issued.assetId());
                locked.countDown();
                await(sweepDone);
                mediaAssets.markVerifiedAndAttached(issued.assetId(), "image/jpeg",
                        IMAGE.length, null, Instant.now());
            }));
            Future<Integer> sweep = pool.submit(() -> {
                await(locked);
                int swept = mediaAssets.sweepExpiredUnattached(Instant.now(), 100);
                sweepDone.countDown();
                return swept;
            });

            assertThat(sweep.get(30, TimeUnit.SECONDS)).isZero();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // The attach won: the asset is attached, never queued for cleanup, and its bytes
        // are still there for the review that is about to reference them.
        assertThat(jdbc.sql("SELECT upload_state FROM review_media_assets WHERE id = ?")
                .param(issued.assetId()).query(String.class).single()).isEqualTo("ATTACHED");
        assertThat(jdbc.sql("SELECT deletion_reason FROM review_media_assets WHERE id = ?")
                .param(issued.assetId()).query(String.class).optional()).isEmpty();
    }

    /**
     * REVIEW-INV-012, the case the subject lock exists for.
     *
     * <p>A privacy transaction holds the subject lock while it commits the guard. An
     * admin reply arriving at that moment blocks on the same lock, and once it proceeds it
     * sees the committed guard: the reply is still recorded, and the notification outbox
     * row that would carry the recipient identifier is not written.
     */
    @Test
    void anAdminReplyThatRacesAPrivacyGuardStillCommitsButDoesNotNotify() throws Exception {
        UUID eligibilityId = grantEntitlement("order-4", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();

        SubjectKey subjectKey = identityHmac.subjectKey(buyer);
        CountDownLatch guardHeld = new CountDownLatch(1);
        CountDownLatch replyStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> privacy = pool.submit(() -> transactions.executeWithoutResult(status -> {
                subjectLocks.lock(subjectKey);
                privacyRequests.insertIfAbsent(UUID.randomUUID(), buyer, subjectKey, "GDPR_REQUEST",
                        Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(30, ChronoUnit.DAYS));
                guardHeld.countDown();
                // The reply is now blocked on the subject lock; this transaction commits
                // the guard before releasing it.
                await(replyStarted);
                sleep(300);
            }));
            Future<?> reply = pool.submit(() -> {
                await(guardHeld);
                replyStarted.countDown();
                adminReplies.putReply(admin, reviewId,
                        new CommandDtos.PutAdminReplyRequest("Cảm ơn bạn"));
                return null;
            });

            privacy.get(30, TimeUnit.SECONDS);
            reply.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.sql("SELECT content FROM review_admin_replies WHERE review_id = ?")
                .param(reviewId).query(String.class).single()).isEqualTo("Cảm ơn bạn");
        assertThat(outboxCount(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.type())).isZero();
    }

    /**
     * The other order of the same race. The admin command committed first, so there was
     * no guard to see and the notification is legitimately written; the deletion then
     * proceeds normally.
     */
    @Test
    void anAdminReplyThatWinsTheRaceDoesNotify() throws Exception {
        UUID eligibilityId = grantEntitlement("order-5", "p-1", "sku-1", buyer);
        UUID reviewId = reviewCommands.create(buyer,
                new CommandDtos.CreateReviewRequest(eligibilityId, 4, "Nội dung", null)).review().id();

        adminReplies.putReply(admin, reviewId, new CommandDtos.PutAdminReplyRequest("Cảm ơn bạn"));

        SubjectKey subjectKey = identityHmac.subjectKey(buyer);
        transactions.executeWithoutResult(status -> {
            subjectLocks.lock(subjectKey);
            privacyRequests.insertIfAbsent(UUID.randomUUID(), buyer, subjectKey, "GDPR_REQUEST",
                    Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(30, ChronoUnit.DAYS));
        });

        assertThat(outboxCount(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.type())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private record Outcome(boolean succeeded, Throwable failure) {
    }

    /** Releases every task at once, so the calls really overlap. */
    private List<Outcome> runTogether(int threads, Callable<Void> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    try {
                        action.call();
                        return new Outcome(true, null);
                    } catch (Throwable failure) {
                        return new Outcome(false, failure);
                    }
                }));
            }
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
