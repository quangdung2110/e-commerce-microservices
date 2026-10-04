package com.ecommerce.inventory.web;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.web.dto.AdminDtos;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Shape rules the OpenAPI expresses with {@code oneOf}/{@code if-then}/{@code uniqueItems}
 * and Bean Validation cannot: all are {@code 400 INVALID_REQUEST} before a durable command
 * exists.
 */
public final class RequestRules {

    private static final Set<String> HOLD_REASONS =
            Set.of("AWAITING_DISPOSITION", "SUPPLIER_CLAIM", "SAFETY_REVIEW", "EVIDENCE_PRESERVATION");
    private static final Set<String> DISPOSE_REASONS = Set.of("UNSAFE", "UNREPAIRABLE", "EXPIRED", "POLICY_REQUIRED");
    private static final String RETURN_TO_SUPPLIER_REASON = "SUPPLIER_RETURN_AUTHORIZED";

    private RequestRules() {
    }

    public static <T> void unique(String field, Collection<T> items, Function<T, String> key) {
        Set<String> seen = new HashSet<>();
        for (T item : items) {
            if (!seen.add(key.apply(item))) {
                throw ApiException.invalid(field, "must contain unique values");
            }
        }
    }

    public static void opaqueId(String field, String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            throw ApiException.invalid(field, "must be 1-128 characters");
        }
    }

    public static int limit(Integer limit, int defaultValue, int max) {
        if (limit == null) {
            return defaultValue;
        }
        if (limit < 1 || limit > max) {
            throw ApiException.invalid("limit", "must be between 1 and " + max);
        }
        return limit;
    }

    public static void requireCorrelation() {
        RequestContext context = RequestContext.current();
        if (context == null || !context.hasValidCorrelationId()) {
            throw ApiException.invalid("X-Correlation-ID", "is required (8-128 characters)");
        }
    }

    public static void requireCausation() {
        RequestContext context = RequestContext.current();
        if (context == null || !RequestContext.validId(context.causationId())) {
            throw ApiException.invalid("X-Causation-ID", "is required (8-128 characters)");
        }
    }

    public static void receipt(AdminDtos.StockReceiptRequest request) {
        boolean initial = request.reason() == AdminDtos.ReceiptReason.INITIAL_STOCK_LOAD;
        if (initial && (request.baselineBatchId() == null || request.goodsReceiptReference() != null)) {
            throw ApiException.invalid("baseline_batch_id",
                    "INITIAL_STOCK_LOAD requires baseline_batch_id and no goods_receipt_reference");
        }
        if (!initial && (request.goodsReceiptReference() == null || request.baselineBatchId() != null)) {
            throw ApiException.invalid("goods_receipt_reference",
                    "SUPPLIER_RECEIPT requires goods_receipt_reference and no baseline_batch_id");
        }
    }

    public static void adjustment(AdminDtos.StockAdjustmentRequest request) {
        AdminDtos.AdjustmentDirection required = switch (request.reason()) {
            case COUNT_GAIN -> AdminDtos.AdjustmentDirection.INCREASE;
            case COUNT_LOSS, DAMAGE_REMOVAL, EXPIRY_REMOVAL -> AdminDtos.AdjustmentDirection.DECREASE;
            case DATA_CORRECTION -> null;
        };
        if (required != null && request.direction() != required) {
            throw ApiException.invalid("direction", request.reason() + " requires " + required);
        }
        if (request.reason() == AdminDtos.AdjustmentReason.DATA_CORRECTION && request.reasonNote() == null) {
            throw ApiException.invalid("reason_note", "DATA_CORRECTION requires reason_note");
        }
    }

    public static void warehouseUpdate(AdminDtos.UpdateWarehouseRequest request) {
        if (!request.changesSomething()) {
            throw ApiException.invalid("body",
                    "at least one of name, address, is_active, operational_state or fulfillment_priority is required");
        }
    }

    public static void inspection(AdminDtos.InspectionRequest request) {
        boolean fail = request.result() == AdminDtos.InspectionResult.FAIL;
        if (fail != (request.failReason() != null)) {
            throw ApiException.invalid("fail_reason", "is required for FAIL and not allowed for PASS");
        }
    }

    public static void disposition(AdminDtos.QuarantineDispositionRequest request) {
        boolean hold = request.disposition() == AdminDtos.Disposition.HOLD;
        Set<String> allowed = switch (request.disposition()) {
            case HOLD -> HOLD_REASONS;
            case DISPOSE -> DISPOSE_REASONS;
            case RETURN_TO_SUPPLIER -> Set.of(RETURN_TO_SUPPLIER_REASON);
        };
        if (!allowed.contains(request.reason())) {
            throw ApiException.invalid("reason", "is not a valid reason for " + request.disposition());
        }
        boolean departure = request.physicalDepartureReference() != null || request.departedAt() != null;
        if (hold && departure) {
            throw ApiException.invalid("physical_departure_reference", "is not allowed for HOLD");
        }
        if (!hold && (request.physicalDepartureReference() == null || request.departedAt() == null)) {
            throw ApiException.invalid("physical_departure_reference",
                    "physical_departure_reference and departed_at are required for " + request.disposition());
        }
    }
}
