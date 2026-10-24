package com.platform.promotionservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.service.DiscoveryService;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoucherReadWarmupTest {
    @Test
    void queryFailureLeavesReadinessClosedAndRecordsFailure() {
        var discovery = mock(DiscoveryService.class);
        when(discovery.warmupOptions(any())).thenThrow(new IllegalStateException("database unavailable"));
        var meters = new SimpleMeterRegistry();
        var warmup = new VoucherReadWarmup(discovery, new ObjectMapper().findAndRegisterModules(), meters, new PromotionProperties(),
                Duration.ofSeconds(30), 1);
        assertThat(warmup.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        assertThatThrownBy(() -> warmup.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refusing startup traffic");
        assertThat(warmup.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        assertThat(meters.get("promotion.voucher.startup.warmup").tag("outcome", "failure").timer().count()).isEqualTo(1);
        verify(discovery, never()).options(any(CheckoutDtos.VoucherOptionRequest.class));
    }

    @Test
    void slowWarmupIsBoundedAndDoesNotDeclareReady() {
        var discovery = mock(DiscoveryService.class);
        when(discovery.warmupOptions(any())).thenAnswer(invocation -> {
            Thread.sleep(10000);
            return null;
        });
        var meters = new SimpleMeterRegistry();
        var warmup = new VoucherReadWarmup(discovery, new ObjectMapper().findAndRegisterModules(), meters, new PromotionProperties(),
                Duration.ofMillis(200), 1);
        assertThatThrownBy(() -> warmup.run(new DefaultApplicationArguments()))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(warmup.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
        assertThat(meters.get("promotion.voucher.startup.warmup").tag("outcome", "failure").timer().count()).isEqualTo(1);
    }
}
