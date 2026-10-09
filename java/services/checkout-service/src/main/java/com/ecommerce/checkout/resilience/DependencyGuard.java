package com.ecommerce.checkout.resilience;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.observability.Spans;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Wraps every dependency call in exactly one policy (ADR-014): deadline check before the call,
 * timeout {@code min(ceiling, remaining - reserve)} that is always shorter than the mesh timeout,
 * a circuit breaker and a bulkhead per dependency, and no retry.
 *
 * <p>The bulkhead sits outside the circuit breaker so a request rejected by the bulkhead never
 * touches the circuit. {@code 400/401/403/429} are ignored by the breaker; every other failure
 * counts. A result is always a {@link CallResult}, never a Reactor error.
 */
@Component
public class DependencyGuard {

    private static final Logger log = LoggerFactory.getLogger(DependencyGuard.class);

    private final CheckoutProperties properties;
    private final MeterRegistry meterRegistry;
    private final Spans spans;
    private final Map<Dependency, Guarded> guards = new EnumMap<>(Dependency.class);

    private record Guarded(Duration ceiling,
                           Duration reserve,
                           CircuitBreaker breaker,
                           Bulkhead bulkhead,
                           AtomicLong openedAtNanos) {
    }

    public DependencyGuard(CheckoutProperties properties, MeterRegistry meterRegistry, Spans spans) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.spans = spans;
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CheckoutProperties.Timeouts timeouts = properties.timeouts();
        CheckoutProperties.Bulkheads bulkheads = properties.bulkheads();
        register(registry, Dependency.CART, timeouts.cart(), bulkheads.cart(), properties.dependencyReserve());
        register(registry, Dependency.USER, timeouts.user(), bulkheads.user(), properties.dependencyReserve());
        register(registry, Dependency.PRODUCT, timeouts.product(), bulkheads.product(), properties.dependencyReserve());
        register(registry, Dependency.INVENTORY, timeouts.inventory(), bulkheads.inventory(),
                properties.dependencyReserve());
        register(registry, Dependency.PROMOTION_CALCULATION, timeouts.promotionCalculation(),
                bulkheads.promotionCalculation(), properties.dependencyReserve());
        register(registry, Dependency.PROMOTION_OPTIONS, timeouts.promotionOptions(), bulkheads.promotionOptions(),
                properties.dependencyReserve());
        register(registry, Dependency.ORDER_POST, timeouts.orderPost(), bulkheads.orderPost(),
                properties.orderReserve());
        register(registry, Dependency.ORDER_GET, timeouts.orderGet(), bulkheads.orderGet(),
                properties.dependencyReserve());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    }

    private void register(CircuitBreakerRegistry registry, Dependency dependency, Duration ceiling, int bulkhead,
                          Duration reserve) {
        CheckoutProperties.Breaker settings = properties.circuitBreaker();
        Duration slow = Duration.ofMillis((long) (ceiling.toMillis() * settings.slowCallTimeoutRatio()));
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize((int) settings.slidingWindow().toSeconds())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .slowCallRateThreshold(settings.slowCallRateThreshold())
                .slowCallDurationThreshold(slow)
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                // No automatic half-open: the circuit only probes when a real request arrives.
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .ignoreException(DependencyGuard::ignoredByBreaker)
                .recordException(error -> !ignoredByBreaker(error))
                .build();
        CircuitBreaker circuitBreaker = registry.circuitBreaker(dependency.tag(), config);
        AtomicLong openedAt = new AtomicLong();
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                openedAt.set(System.nanoTime());
            }
            log.warn("Circuit breaker {} transitioned {}", dependency.tag(), event.getStateTransition());
        });
        Bulkhead semaphore = Bulkhead.of(dependency.tag(), BulkheadConfig.custom()
                .maxConcurrentCalls(bulkhead)
                .maxWaitDuration(Duration.ZERO)
                .build());
        guards.put(dependency, new Guarded(ceiling, reserve, circuitBreaker, semaphore, openedAt));
    }

    /** 400/401/403/429 say nothing about provider health (operations.md). */
    private static boolean ignoredByBreaker(Throwable error) {
        return error instanceof UpstreamStatusException status && !status.isServerError();
    }

    /**
     * Calls {@code request} under the policy of {@code dependency}. The request must emit exactly
     * one element on success; an empty Mono is a contract violation.
     */
    public <T> Mono<CallResult<T>> call(Dependency dependency, Deadline deadline, Supplier<Mono<T>> request) {
        return call(dependency, deadline, request, value -> false);
    }

    /**
     * @param businessReject marks a successfully received value as a business result (REJECT_ALL,
     *                       eligible=false); only the metric label changes, the breaker still sees a success
     */
    public <T> Mono<CallResult<T>> call(Dependency dependency, Deadline deadline, Supplier<Mono<T>> request,
                                        Predicate<T> businessReject) {
        Guarded guarded = guards.get(dependency);
        Duration remaining = deadline.remaining();
        if (remaining.compareTo(properties.phaseMinRemaining()) < 0) {
            return Mono.just(recorded(dependency, CallResult.failure(Outcome.BUDGET_SKIPPED), Duration.ZERO));
        }
        Duration budget = remaining.minus(guarded.reserve());
        Duration timeout = budget.compareTo(guarded.ceiling()) < 0 ? budget : guarded.ceiling();
        if (timeout.isNegative() || timeout.isZero()) {
            return Mono.just(recorded(dependency, CallResult.failure(Outcome.BUDGET_SKIPPED), Duration.ZERO));
        }

        Mono<CallResult<T>> guardedCall = Mono.defer(() -> {
            long startedAt = System.nanoTime();
            return Mono.defer(request)
                    .switchIfEmpty(Mono.error(() -> new ContractViolationException("empty response")))
                    .timeout(timeout)
                    .transformDeferred(CircuitBreakerOperator.of(guarded.breaker()))
                    .transformDeferred(BulkheadOperator.of(guarded.bulkhead()))
                    .map(value -> businessReject.test(value)
                            ? new CallResult<>(Outcome.BUSINESS_REJECT, value, null, null)
                            : CallResult.success(value))
                    .onErrorResume(error -> Mono.just(classify(dependency, guarded, error)))
                    .map(result -> recorded(dependency, result, Duration.ofNanos(System.nanoTime() - startedAt)));
        });
        // A call that is not sent (budget, above) has no span; the metric records it with zero duration.
        return spans.in("checkout.dependency",
                span -> guardedCall.doOnNext(result -> span.lowCardinalityKeyValue("outcome", result.outcome().tag())),
                "dependency", dependency.tag());
    }

    private <T> CallResult<T> classify(Dependency dependency, Guarded guarded, Throwable error) {
        if (error instanceof CallNotPermittedException) {
            return CallResult.circuitOpen(retryAfterSeconds(guarded));
        }
        if (error instanceof BulkheadFullException) {
            return CallResult.failure(Outcome.BULKHEAD_REJECTED);
        }
        if (error instanceof TimeoutException) {
            return CallResult.failure(Outcome.TIMEOUT);
        }
        if (error instanceof UpstreamStatusException status) {
            if (status.isRateLimited()) {
                log.warn("{} rate limited the call (429)", dependency.tag());
                return CallResult.failure(Outcome.CLIENT_ERROR, status.code());
            }
            if (!status.isServerError()) {
                log.warn("{} answered {}: identity, authorization or request configuration is wrong",
                        dependency.tag(), status.status());
                return CallResult.failure(Outcome.CLIENT_ERROR, status.code());
            }
            return CallResult.failure(Outcome.SERVER_ERROR, status.code());
        }
        if (error instanceof ContractViolationException violation) {
            log.warn("{} response violates its contract: {}", dependency.tag(), violation.getMessage());
            return CallResult.failure(Outcome.CONTRACT_VIOLATION);
        }
        if (!(error instanceof WebClientRequestException) && !(error instanceof IOException)) {
            log.warn("{} call failed unexpectedly", dependency.tag(), error);
        }
        return CallResult.failure(Outcome.CONNECT_ERROR);
    }

    /** Remaining open time rounded up to a second, at least 1 (interfaces.md, Retry-After). */
    private int retryAfterSeconds(Guarded guarded) {
        long openFor = properties.circuitBreaker().openDuration().toNanos();
        long elapsed = System.nanoTime() - guarded.openedAtNanos().get();
        long remainingNanos = Math.max(0, openFor - elapsed);
        long seconds = (remainingNanos + 999_999_999L) / 1_000_000_000L;
        return (int) Math.max(1, seconds);
    }

    private <T> CallResult<T> recorded(Dependency dependency, CallResult<T> result, Duration elapsed) {
        Timer.builder("checkout.dependency.calls")
                .description("Dependency calls of Checkout by outcome; calls not sent record zero duration")
                .tag("dependency", dependency.tag())
                .tag("outcome", result.outcome().tag())
                .register(meterRegistry)
                .record(elapsed);
        return result;
    }

    /**
     * {@code true} while the circuit is open and its wait time has not elapsed: the next call would
     * be rejected. Used to refuse a Submit before it claims the token (architecture.md, step 0).
     */
    public boolean isOpen(Dependency dependency) {
        Guarded guarded = guards.get(dependency);
        CircuitBreaker.State state = guarded.breaker().getState();
        if (state == CircuitBreaker.State.FORCED_OPEN) {
            return true;
        }
        if (state != CircuitBreaker.State.OPEN) {
            return false;
        }
        long elapsed = System.nanoTime() - guarded.openedAtNanos().get();
        return elapsed < properties.circuitBreaker().openDuration().toNanos();
    }

    /** Seconds until an open circuit may probe again; at least 1. */
    public int openRetryAfterSeconds(Dependency dependency) {
        return retryAfterSeconds(guards.get(dependency));
    }

    /** For tests and diagnostics; not a way around the circuit breaker. */
    public CircuitBreaker breaker(Dependency dependency) {
        return guards.get(dependency).breaker();
    }
}
