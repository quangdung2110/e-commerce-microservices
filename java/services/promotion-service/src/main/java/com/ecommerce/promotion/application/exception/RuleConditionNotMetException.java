package com.ecommerce.promotion.application.exception;

/**
 * Ném khi điều kiện Rule Engine không thỏa mãn.
 * Theo thiết kế promotion_workflow.md - Luồng 3: In-memory Rule Engine.
 * Maps to HTTP 422 Unprocessable Entity.
 */
public class RuleConditionNotMetException extends CoreBusinessException {

    public RuleConditionNotMetException(Long promotionId, String reason) {
        super(
                String.format("Conditions not met for promotion %d: %s", promotionId, reason),
                "RULE_CONDITION_NOT_MET");
    }
}
