package com.ecommerce.pdp.resilience;

import com.ecommerce.pdp.config.PdpProperties;
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

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Gói mọi lời gọi dependency trong đúng một chính sách (ADR-004, ADR-007): cờ bật/tắt,
 * kiểm tra deadline trước khi gọi, timeout {@code min(trần, deadline còn lại − dự phòng)},
 * circuit breaker và bulkhead riêng cho từng dependency, không retry.
 *
 * <p>Bulkhead bọc ngoài circuit breaker để request bị bulkhead từ chối không chạm vào
 * circuit. {@code 4xx} bị circuit breaker bỏ qua hoàn toàn; mọi lỗi khác tính là lỗi.
 * Kết quả luôn trả về dạng {@link CallResult}, không bao giờ là lỗi Reactor.
 */
@Component
public class DependencyGuard {

    private static final Logger log = LoggerFactory.getLogger(DependencyGuard.class);

    private final PdpProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<Dependency, Guarded> guards = new EnumMap<>(Dependency.class);

    private record Guarded(PdpProperties.Upstream settings,
                           CircuitBreaker breaker,
                           Bulkhead bulkhead,
                           AtomicLong openedAtNanos) {
    }

    public DependencyGuard(PdpProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        guards.put(Dependency.PRODUCT, guarded(registry, Dependency.PRODUCT, properties.product()));
        guards.put(Dependency.INVENTORY, guarded(registry, Dependency.INVENTORY, properties.inventory()));
        guards.put(Dependency.REVIEW, guarded(registry, Dependency.REVIEW, properties.review().asUpstream()));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    }

    private Guarded guarded(CircuitBreakerRegistry registry, Dependency dependency, PdpProperties.Upstream settings) {
        PdpProperties.Breaker breaker = properties.circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize((int) breaker.slidingWindow().toSeconds())
                .minimumNumberOfCalls(breaker.minimumCalls())
                .failureRateThreshold(breaker.failureRateThreshold())
                .slowCallRateThreshold(breaker.slowCallRateThreshold())
                .slowCallDurationThreshold(settings.slowCallThreshold())
                .waitDurationInOpenState(breaker.openDuration())
                .permittedNumberOfCallsInHalfOpenState(breaker.halfOpenCalls())
                // Không tự chuyển half-open: circuit chỉ thử lại khi có request thật.
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
        Bulkhead bulkhead = Bulkhead.of(dependency.tag(), BulkheadConfig.custom()
                .maxConcurrentCalls(settings.bulkhead())
                .maxWaitDuration(Duration.ZERO)
                .build());
        return new Guarded(settings, circuitBreaker, bulkhead, openedAt);
    }

    /** {@code 400}/{@code 401}/{@code 403}: lỗi cấu hình, không nói gì về sức khỏe provider. */
    private static boolean ignoredByBreaker(Throwable error) {
        return error instanceof UpstreamStatusException status && !status.isServerError();
    }

    /**
     * Gọi {@code request} dưới chính sách của {@code dependency}. {@code request} phải phát
     * đúng một phần tử khi thành công; Mono rỗng được coi là vi phạm contract.
     */
    public <T> Mono<CallResult<T>> call(Dependency dependency, Deadline deadline, Supplier<Mono<T>> request) {
        Guarded guarded = guards.get(dependency);
        if (!guarded.settings().enabled()) {
            return Mono.just(recorded(dependency, CallResult.failure(Outcome.DISABLED), Duration.ZERO));
        }
        Duration remaining = deadline.remaining();
        if (remaining.compareTo(properties.phaseMinRemaining()) < 0) {
            return Mono.just(recorded(dependency, CallResult.failure(Outcome.BUDGET_SKIPPED), Duration.ZERO));
        }
        Duration budget = remaining.minus(properties.composeReserve());
        Duration timeout = budget.compareTo(guarded.settings().timeout()) < 0 ? budget : guarded.settings().timeout();

        return Mono.defer(() -> {
            long startedAt = System.nanoTime();
            return Mono.defer(request)
                    .switchIfEmpty(Mono.error(() -> new ContractViolationException("empty response")))
                    .timeout(timeout)
                    .transformDeferred(CircuitBreakerOperator.of(guarded.breaker()))
                    .transformDeferred(BulkheadOperator.of(guarded.bulkhead()))
                    .map(CallResult::success)
                    .onErrorResume(error -> Mono.just(classify(dependency, guarded, error)))
                    .map(result -> recorded(dependency, result, Duration.ofNanos(System.nanoTime() - startedAt)));
        });
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
            if (!status.isServerError()) {
                log.warn("{} answered {}: identity, authorization or request configuration is wrong",
                        dependency.tag(), status.status());
                return CallResult.failure(Outcome.CLIENT_ERROR);
            }
            return CallResult.failure(Outcome.SERVER_ERROR);
        }
        if (error instanceof ContractViolationException violation) {
            log.warn("{} response violates its contract: {}", dependency.tag(), violation.getMessage());
            return CallResult.failure(Outcome.CONTRACT_VIOLATION);
        }
        if (!(error instanceof WebClientRequestException)) {
            log.warn("{} call failed unexpectedly", dependency.tag(), error);
        }
        return CallResult.failure(Outcome.CONNECT_ERROR);
    }

    /** Thời gian mở còn lại, làm tròn lên giây, tối thiểu 1 (interfaces.md, Retry-After). */
    private int retryAfterSeconds(Guarded guarded) {
        long openFor = properties.circuitBreaker().openDuration().toNanos();
        long elapsed = System.nanoTime() - guarded.openedAtNanos().get();
        long remainingNanos = Math.max(0, openFor - elapsed);
        long seconds = (remainingNanos + 999_999_999L) / 1_000_000_000L;
        return (int) Math.max(1, seconds);
    }

    private <T> CallResult<T> recorded(Dependency dependency, CallResult<T> result, Duration elapsed) {
        Timer.builder("pdp.dependency.calls")
                .description("Dependency calls of the PDP by outcome; skipped calls record zero duration")
                .tag("dependency", dependency.tag())
                .tag("outcome", result.outcome().tag())
                .register(meterRegistry)
                .record(elapsed);
        return result;
    }

    /** Cho test và chẩn đoán; không dùng để né circuit breaker. */
    public CircuitBreaker breaker(Dependency dependency) {
        return guards.get(dependency).breaker();
    }
}
