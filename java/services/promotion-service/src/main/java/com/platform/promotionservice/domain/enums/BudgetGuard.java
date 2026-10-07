package com.platform.promotionservice.domain.enums;

/** Read-time budget guard của discovery: best-effort, fail-open có marker DEGRADED. */
public enum BudgetGuard {
    AVAILABLE, EXHAUSTED, DEGRADED, NOT_CHECKED
}
