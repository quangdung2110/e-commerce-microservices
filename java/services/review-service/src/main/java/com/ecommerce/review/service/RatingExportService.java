package com.ecommerce.review.service;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.domain.ExportSession;
import com.ecommerce.review.domain.PartitionOffset;
import com.ecommerce.review.domain.RatingAggregate;
import com.ecommerce.review.dto.InternalDtos;
import com.ecommerce.review.messaging.KafkaEndOffsets;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.repository.ExportSessionRepository;
import com.ecommerce.review.repository.RatingAggregateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WF-REVIEW-009: durable export sessions for a Search rating rebuild.
 *
 * <p>Creating a session writes state, which is why it is a {@code POST}. The offsets are
 * captured before any aggregate is scanned and every page echoes exactly them, so Search
 * can page without Review holding a transaction across HTTP calls: a mutation seen mid
 * scan is deduplicated by {@code review_revision}, and one missed by the scan has an
 * event at or after the captured offset (ADR-006).
 */
@Service
public class RatingExportService {

    private static final Logger log = LoggerFactory.getLogger(RatingExportService.class);

    private final ExportSessionRepository sessions;
    private final RatingAggregateRepository aggregates;
    private final KafkaEndOffsets endOffsets;
    private final ReviewAssembler assembler;
    private final ReviewProperties properties;
    private final ReviewMetrics metrics;
    private final TransactionTemplate transactions;

    public RatingExportService(ExportSessionRepository sessions,
                               RatingAggregateRepository aggregates,
                               KafkaEndOffsets endOffsets,
                               ReviewAssembler assembler,
                               ReviewProperties properties,
                               ReviewMetrics metrics,
                               TransactionTemplate transactions) {
        this.sessions = sessions;
        this.aggregates = aggregates;
        this.endOffsets = endOffsets;
        this.assembler = assembler;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    /**
     * Captures the watermark and persists the session.
     *
     * <p>The Kafka call happens before the transaction: a session must never commit with
     * offsets Review could not actually read, so a cluster failure answers
     * {@code EXPORT_SESSION_UNAVAILABLE} and Search retries the whole run.
     */
    public InternalDtos.RatingAggregateExportSession createSession(String requestedBy) {
        List<PartitionOffset> offsets;
        try {
            offsets = endOffsets.endOffsets(properties.topics().review());
        } catch (RuntimeException e) {
            metrics.exportSession("create_unavailable");
            log.error("could not capture {} end offsets for a rating export session",
                    properties.topics().review(), e);
            throw new ReviewException(ErrorCode.EXPORT_SESSION_UNAVAILABLE,
                    "Review cannot capture the event offsets this export needs", e);
        }

        ExportSession session = transactions.execute(status -> {
            ExportSession created = sessions.createSession(requestedBy,
                    Instant.now().plus(properties.exportSessionTtl()));
            sessions.saveOffsets(created.id(), offsets);
            return created;
        });

        metrics.exportSession("created");
        return new InternalDtos.RatingAggregateExportSession(session.id(), session.expiresAt(),
                toReplayOffsets(offsets));
    }

    /**
     * One keyset page of a session.
     *
     * <p>An unknown session is 404 and an expired one is 410; both discard the whole run,
     * because every page imported so far was interpreted against a watermark that is now
     * gone.
     */
    @Transactional(readOnly = true)
    public InternalDtos.RatingAggregateExportPage page(UUID sessionId, String afterProductId, int limit) {
        ExportSession session = sessions.findSession(sessionId)
                .orElseThrow(() -> {
                    metrics.exportSession("page_not_found");
                    return new ReviewException(ErrorCode.EXPORT_SESSION_NOT_FOUND,
                            "Export session does not exist");
                });
        if (session.expired(Instant.now())) {
            metrics.exportSession("page_expired");
            throw new ReviewException(ErrorCode.EXPORT_SESSION_EXPIRED,
                    "Export session expired; start a new export");
        }

        List<RatingAggregate> rows = aggregates.exportPage(afterProductId, limit);
        List<InternalDtos.RatingAggregateSnapshot> items = rows.stream()
                .map(assembler::toExportSnapshot)
                .toList();
        String next = rows.size() < limit ? null : rows.get(rows.size() - 1).productId();

        metrics.exportSession("page_served");
        return new InternalDtos.RatingAggregateExportPage(session.id(), items, next,
                toReplayOffsets(sessions.findOffsets(session.id())));
    }

    private static List<InternalDtos.ReplayOffset> toReplayOffsets(List<PartitionOffset> offsets) {
        return offsets.stream()
                .map(offset -> new InternalDtos.ReplayOffset(offset.topic(), offset.partition(),
                        offset.replayFromOffset()))
                .toList();
    }
}
