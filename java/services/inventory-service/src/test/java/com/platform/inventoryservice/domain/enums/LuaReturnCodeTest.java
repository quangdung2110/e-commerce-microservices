package com.platform.inventoryservice.domain.enums;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuaReturnCodeTest {

    @Test
    void fromArray_applied_returnsApplied() {
        assertThat(LuaReturnCode.fromArray(List.of(1L, ""))).isEqualTo(LuaReturnCode.APPLIED);
    }

    @Test
    void fromArray_idempotent_returnsIdempotentHit() {
        assertThat(LuaReturnCode.fromArray(List.of(2L, ""))).isEqualTo(LuaReturnCode.IDEMPOTENT_HIT);
    }

    @Test
    void fromArray_outOfStock_returnsOutOfStock() {
        assertThat(LuaReturnCode.fromArray(List.of(0L, "OUT_OF_STOCK"))).isEqualTo(LuaReturnCode.OUT_OF_STOCK);
    }

    @Test
    void fromArray_invariantDrift_returnsInvariantDrift() {
        assertThat(LuaReturnCode.fromArray(List.of(-1L, "INVARIANT_DRIFT")))
                .isEqualTo(LuaReturnCode.INVARIANT_DRIFT);
    }

    @Test
    void fromArray_acceptsIntegerCode() {
        assertThat(LuaReturnCode.fromArray(List.of(0, "RELEASE_UNDERFLOW")))
                .isEqualTo(LuaReturnCode.RELEASE_UNDERFLOW);
    }

    @Test
    void fromArray_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> LuaReturnCode.fromArray(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromArray_wrongSize_throwsIllegalArgument() {
        assertThatThrownBy(() -> LuaReturnCode.fromArray(List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LuaReturnCode.fromArray(List.of(1L, "", "extra")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromArray_unknownCode_throwsIllegalState() {
        assertThatThrownBy(() -> LuaReturnCode.fromArray(List.of(99L, "UNKNOWN")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isSuccess_appliedAndIdempotent_true() {
        assertThat(LuaReturnCode.APPLIED.isSuccess()).isTrue();
        assertThat(LuaReturnCode.IDEMPOTENT_HIT.isSuccess()).isTrue();
    }

    @Test
    void isSuccess_outOfStock_false() {
        assertThat(LuaReturnCode.OUT_OF_STOCK.isSuccess()).isFalse();
    }

    @Test
    void isIdempotent_onlyIdempotentHit_true() {
        assertThat(LuaReturnCode.IDEMPOTENT_HIT.isIdempotent()).isTrue();
        assertThat(LuaReturnCode.APPLIED.isIdempotent()).isFalse();
    }

    @Test
    void isBusinessReject_zeroCode_true() {
        assertThat(LuaReturnCode.OUT_OF_STOCK.isBusinessReject()).isTrue();
        assertThat(LuaReturnCode.INSUFFICIENT_RESERVED.isBusinessReject()).isTrue();
        assertThat(LuaReturnCode.RELEASE_UNDERFLOW.isBusinessReject()).isTrue();
        assertThat(LuaReturnCode.SKU_SUSPENDED.isBusinessReject()).isTrue();
    }

    @Test
    void isBusinessReject_nonzeroCode_false() {
        assertThat(LuaReturnCode.APPLIED.isBusinessReject()).isFalse();
        assertThat(LuaReturnCode.IDEMPOTENT_HIT.isBusinessReject()).isFalse();
        assertThat(LuaReturnCode.INVARIANT_DRIFT.isBusinessReject()).isFalse();
    }

    @Test
    void isInvariantBreach_onlyInvariantDrift_true() {
        assertThat(LuaReturnCode.INVARIANT_DRIFT.isInvariantBreach()).isTrue();
        assertThat(LuaReturnCode.APPLIED.isInvariantBreach()).isFalse();
    }
}