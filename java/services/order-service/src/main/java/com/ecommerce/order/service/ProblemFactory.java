package com.ecommerce.order.service;

import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Sanitized RFC 9457 bodies persisted on submissions and operations. */
@Component
public class ProblemFactory {

    private final Json json;

    public ProblemFactory(Json json) {
        this.json = json;
    }

    public ObjectNode problem(ErrorCode code, String detail, String instance) {
        ObjectNode problem = json.object();
        problem.put("type", code.typeUri());
        problem.put("title", code.title());
        problem.put("status", code.status());
        problem.put("detail", detail == null || detail.isBlank() ? code.title() : truncate(detail));
        problem.put("instance", instance == null ? "/" : instance);
        problem.put("code", code.name());
        problem.put("retryable", code.retryable());
        return problem;
    }

    public ObjectNode submissionFailure(String errorCode) {
        ErrorCode code = codeOf(errorCode, ErrorCode.INVALID_ORDER_TRANSITION);
        return problem(code, code.title(), "/internal/v1/checkout-order-submissions");
    }

    /** PromotionRecalculationProblem of the internal OpenAPI (all-or-nothing reserve). */
    public ObjectNode promotionRecalculation(String promotionId, String reason) {
        ObjectNode problem = problem(ErrorCode.PROMOTION_RECALCULATION_NEEDED,
                "Promotion reservation failed; recall Preview", "/internal/v1/checkout-order-submissions");
        problem.put("action", "RECALL_PREVIEW");
        problem.put("failed_promotion_id", promotionId);
        problem.put("failed_reason", reason == null ? "UNKNOWN" : reason);
        problem.put("reserve_mode", "ALL_OR_NOTHING");
        problem.putArray("dropped_promotions").addObject().put("promotion_id", promotionId)
                .put("reason", reason == null ? "UNKNOWN" : reason);
        return problem;
    }

    public ObjectNode operationProblem(String errorCode, String detail) {
        return problem(codeOf(errorCode, ErrorCode.INVALID_ORDER_TRANSITION), detail, "/api/v1/order-operations");
    }

    private static ErrorCode codeOf(String value, ErrorCode fallback) {
        try {
            return ErrorCode.valueOf(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String truncate(String value) {
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
