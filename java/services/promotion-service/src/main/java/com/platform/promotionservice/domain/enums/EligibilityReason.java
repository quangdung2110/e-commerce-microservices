package com.platform.promotionservice.domain.enums;

public enum EligibilityReason {
    ELIGIBLE,
    NO_VOUCHER_SELECTED,
    CONDITIONS_NOT_MET,
    MIN_ORDER_NOT_MET,
    VOUCHER_NOT_OWNED,
    VOUCHER_EXPIRED,
    ALREADY_USED_VOUCHER,
    CAMPAIGN_NOT_ACTIVE,
    PROMOTION_DISABLED,
    BUDGET_EXHAUSTED
}
