package com.platform.promotionservice.domain.enums;

/** Trigger causal của markdown snapshot; nằm trong outbox dedup_key (PROMO-BR-020). */
public enum ScheduleTrigger {
    START, END, PUBLISH, PAUSE, CANCEL
}
