package com.ecommerce.checkout.config;

import com.ecommerce.checkout.store.SessionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisReadWarmupTest {
    @Test
    void transientColdConnectionFailureIsRetriedWithinStartupBudget() throws Exception {
        var store = mock(SessionStore.class);
        var attempts = new AtomicInteger();
        when(store.loadScripts()).thenReturn(Mono.defer(() -> attempts.incrementAndGet() == 1
                ? Mono.error(new IllegalStateException("cold handshake")) : Mono.empty()));
        when(store.warmupReadPath()).thenReturn(Mono.empty());
        var meters = new SimpleMeterRegistry();
        new CheckoutConfig().redisReadWarmup(store, meters, Duration.ofSeconds(5), 3)
                .run(new DefaultApplicationArguments());
        assertThat(attempts.get()).isEqualTo(2);
        verify(store, times(3)).warmupReadPath();
        assertThat(meters.get("checkout.redis.startup.warmup").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void hungStartupReadIsBoundedAndCannotReportSuccess() {
        var store = mock(SessionStore.class);
        when(store.loadScripts()).thenReturn(Mono.never());
        var meters = new SimpleMeterRegistry();
        assertThatThrownBy(() -> new CheckoutConfig().redisReadWarmup(store, meters, Duration.ofMillis(200), 3)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refusing startup traffic");
        assertThat(meters.get("checkout.redis.startup.warmup").tag("outcome", "failure").timer().count()).isEqualTo(1);
        verify(store, never()).warmupReadPath();
    }
}
