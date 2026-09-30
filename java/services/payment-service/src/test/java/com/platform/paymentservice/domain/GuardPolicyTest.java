package com.platform.paymentservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardPolicyTest {
    @Test
    void classifies_declines_exactly_as_documented() {
        DeclinePolicy policy = new DeclinePolicy(5);
        assertThat(policy.mustTerminate("stolen_card", null, 1)).isTrue();
        assertThat(policy.mustTerminate("generic_decline", "blocked", 1)).isTrue();
        assertThat(policy.mustTerminate("insufficient_funds", null, 5)).isFalse();
        assertThat(policy.mustTerminate("unknown_future_code", null, 6)).isTrue();
    }

    @Test
    void orders_provider_events_with_tie_retrieval() {
        Instant now = Instant.parse("2026-09-20T10:00:00Z");
        ProviderCheckpoint checkpoint = new ProviderCheckpoint(now, "evt_2");
        assertThat(checkpoint.decide(now.minusSeconds(1), "evt_1")).isEqualTo(CheckpointDecision.STALE);
        assertThat(checkpoint.decide(now, "evt_2")).isEqualTo(CheckpointDecision.DUPLICATE);
        assertThat(checkpoint.decide(now, "evt_3")).isEqualTo(CheckpointDecision.TIE_RETRIEVE_REQUIRED);
        assertThat(checkpoint.decide(now.plusSeconds(1), "evt_4")).isEqualTo(CheckpointDecision.APPLY);
    }

    @Test
    void accepts_failed_inspection_but_rejects_negative_counters() {
        assertThat(new ReturnScopeInspectionEvidence("scope", 2, 2, 1, 1).failed()).isEqualTo(1);
        assertThatThrownBy(() -> new ReturnScopeInspectionEvidence("scope", -1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
