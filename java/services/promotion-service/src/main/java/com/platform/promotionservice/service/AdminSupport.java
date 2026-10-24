package com.platform.promotionservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.entity.CampaignEntity;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/** Helper dùng chung cho admin service: If-Match, DRAFT guard, cursor, merge-patch, after-commit. */
public final class AdminSupport {

    private static final Pattern IF_MATCH = Pattern.compile("^\"([1-9][0-9]*)\"$");

    private AdminSupport() {
    }

    /** If-Match phải là strong ETag chứa revision hiện hành; lệch → 412 CAMPAIGN_REVISION_MISMATCH. */
    public static void requireIfMatch(CampaignEntity campaign, String ifMatch) {
        if (ifMatch == null) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "Header If-Match is required");
        }
        var matcher = IF_MATCH.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "If-Match must be a strong ETag like \"7\"");
        }
        long expected = Long.parseLong(matcher.group(1));
        if (expected != campaign.getRevision()) {
            throw new DomainException(ErrorCode.CAMPAIGN_REVISION_MISMATCH,
                    "If-Match " + expected + " does not equal campaign revision " + campaign.getRevision());
        }
    }

    /** Published definition là bất biến (PROMO-BR-009): mutation chỉ khi DRAFT. */
    public static void requireDraft(CampaignEntity campaign) {
        if (!campaign.isDraft()) {
            throw new DomainException(ErrorCode.CAMPAIGN_NOT_DRAFT,
                    "Campaign " + campaign.getId() + " is " + campaign.getPublicationStatus()
                            + "; clone it to change the definition");
        }
    }

    public static String encodeCursor(long lastId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("id:" + lastId).getBytes(StandardCharsets.UTF_8));
    }

    public static long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith("id:")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(decoded.substring(3));
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCode.INVALID_CURSOR, "Cursor is invalid");
        }
    }

    public static int limitOf(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1 || limit > 100) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "limit must be within [1,100]");
        }
        return limit;
    }

    /** merge-patch: field vắng → giữ nguyên; null → xóa (chỉ cho field nullable). */
    public static boolean present(JsonNode patch, String field) {
        return patch != null && patch.has(field);
    }

    public static String text(JsonNode patch, String field, int maxLength, boolean nullable) {
        JsonNode node = patch.get(field);
        if (node == null || node.isNull()) {
            if (nullable) {
                return null;
            }
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must not be null");
        }
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > maxLength) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must be a non-empty string up to " + maxLength);
        }
        return node.asText();
    }

    public static Instant instant(JsonNode patch, String field) {
        JsonNode node = patch.get(field);
        if (node == null || !node.isTextual()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must be an RFC 3339 date-time");
        }
        try {
            return Instant.parse(node.asText());
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " must be an RFC 3339 date-time");
        }
    }

    public static void requireNonEmptyPatch(JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "merge-patch body must contain at least one field");
        }
    }

    public static void requireWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "start_time must be before end_time",
                    java.util.List.of(new DomainException.FieldError("end_time", "must be after start_time")));
        }
    }

    /** Side effect Redis (schedule/gate/cache) chỉ chạy sau khi PostgreSQL đã commit. */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Chạy {@code action} khi transaction hiện tại bị rollback. Dùng để hoàn tác side effect Redis đã áp dụng
     * <em>trước</em> commit (đóng claim gate của pause/cancel). Không transaction thì không có gì để rollback.
     */
    public static void afterRollback(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        action.run();
                    }
                }
            });
        }
    }
}
