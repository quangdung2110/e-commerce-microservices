package com.ecommerce.checkout.config;

import com.ecommerce.checkout.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class CheckoutConfig {

    private static final Logger log = LoggerFactory.getLogger(CheckoutConfig.class);

    /** One clock for every timestamp Checkout writes, replaceable in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Load and execute the Preview read path before ACCEPTING_TRAFFIC. SCRIPT_LOAD alone does not
     * initialize Spring's reactive script executor, Lettuce EVALSHA or MULTI result decoding.
     */
    @Bean
    public ApplicationRunner redisReadWarmup(SessionStore store, MeterRegistry meters,
            @Value("${checkout.redis-warmup.timeout:30s}") Duration budget,
            @Value("${checkout.redis-warmup.rounds:3}") int rounds) {
        if (budget.isZero() || budget.isNegative() || rounds < 1 || rounds > 10) {
            throw new IllegalArgumentException("Redis warm-up requires positive timeout and 1..10 rounds");
        }
        return args -> {
            long started = System.nanoTime();
            String outcome = "failure";
            try {
                store.loadScripts().thenMany(Flux.range(0, rounds)
                                .concatMap(round -> store.warmupReadPath()))
                        .then().retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100))
                                .maxBackoff(Duration.ofMillis(500)))
                        .timeout(budget).block();
                outcome = "success";
                log.info("Redis read-path warm-up completed in {} ms ({} rounds)",
                        Duration.ofNanos(System.nanoTime() - started).toMillis(), rounds);
            } catch (RuntimeException error) {
                throw new IllegalStateException("Redis read-path warm-up failed; refusing startup traffic", error);
            } finally {
                Timer.builder("checkout.redis.startup.warmup").tag("outcome", outcome)
                        .register(meters).record(Duration.ofNanos(System.nanoTime() - started));
            }
        };
    }
}
