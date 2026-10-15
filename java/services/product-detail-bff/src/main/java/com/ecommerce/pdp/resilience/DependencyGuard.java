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
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Gói mọi lời gọi dependency trong đúng một chính sách (ADR-004, ADR-007): cờ bật/tắt,
 * kiểm tra deadline trước khi gọi, timeout {@code min(trần, deadline còn lại − dự phòng)},
 * circuit breaker và bulkhead riêng cho từng dependency, không retry.
 *
 * <p>Bulkhead bọc ngoài circuit breaker để request bị bulkhead từ chối không chạm vào
 * circuit. Chỉ {@code 400}/{@code 401}/{@code 403} bị circuit breaker bỏ qua hoàn toàn;
 * mọi lỗi khác, kể cả status lạ ngoài contract, tính là lỗi.
 * Kết quả luôn trả về dạng {@link CallResult}, không bao giờ là lỗi Reactor.
 */
@Component
public class DependencyGuard {

    /** Khóa Reactor context mang request ID đã chuẩn hóa, để dòng log gắn được vào request. */
    public static final String REQUEST_ID_KEY = "pdp.request-id";
    /** Reactor context key holding the {@link Duration} a dependency call runs under (contracts ADR-003). */
    public static final String EFFECTIVE_TIMEOUT_KEY = "pdp.effective-timeout";
    public static final String TRACE_ID_KEY = "pdp.trace-id";

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

    /**
     * Đúng {@code 400}/{@code 401}/{@code 403}: lỗi cấu hình, không nói gì về sức khỏe
     * provider. Status lạ khác ngoài contract (429, 408, 3xx...) vẫn tính là lỗi.
     */
    private static boolean ignoredByBreaker(Throwable error) {
        return error instanceof UpstreamStatusException status && status.isConfigurationError();
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

        return Mono.deferContextual(context -> {
            long startedAt = System.nanoTime();
            Object[] correlation = correlation(context);
            return Mono.defer(request)
                    .switchIfEmpty(Mono.error(() -> new ContractViolationException("empty response")))
                    .timeout(timeout)
                    .transformDeferred(CircuitBreakerOperator.of(guarded.breaker()))
                    .transformDeferred(BulkheadOperator.of(guarded.bulkhead()))
                    .map(CallResult::success)
                    .onErrorResume(error -> Mono.just(classify(dependency, guarded, error, correlation)))
                    // contracts ADR-003: clients read the timeout this call runs under and send it to the provider.
                    .contextWrite(c -> c.put(EFFECTIVE_TIMEOUT_KEY, timeout))
                    .map(result -> recorded(dependency, result, Duration.ofNanos(System.nanoTime() - startedAt)));
        });
    }

    /**
     * {@code request_id} và {@code trace_id} của request đang xử lý. Logger không đọc được
     * Reactor context nên mọi dòng log trong phạm vi một request gắn hai giá trị này bằng
     * structured argument, để tra chéo với log của provider (operations.md §Observability).
     */
    private static Object[] correlation(ContextView context) {
        return new Object[]{
                kv("request_id", context.getOrDefault(REQUEST_ID_KEY, "-")),
                kv("trace_id", context.getOrDefault(TRACE_ID_KEY, "-"))};
    }

    private <T> CallResult<T> classify(Dependency dependency, Guarded guarded, Throwable error,
                                       Object[] correlation) {
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
            if (status.isServerError()) {
                return CallResult.failure(Outcome.SERVER_ERROR);
            }
            if (status.isConfigurationError()) {
                log.warn("{} answered {}: identity, authorization or request configuration is wrong {} {}",
                        dependency.tag(), status.status(), correlation[0], correlation[1]);
                return CallResult.failure(Outcome.CLIENT_ERROR);
            }
            log.warn("{} answered {}, a status outside its contract {} {}",
                    dependency.tag(), status.status(), correlation[0], correlation[1]);
            return CallResult.failure(Outcome.CONTRACT_VIOLATION);
        }
        if (error instanceof ContractViolationException violation) {
            log.warn("{} response violates its contract: {} {} {}",
                    dependency.tag(), violation.getMessage(), correlation[0], correlation[1]);
            return CallResult.failure(Outcome.CONTRACT_VIOLATION);
        }
        if (!(error instanceof WebClientRequestException)) {
            log.warn("{} call failed unexpectedly {} {}", dependency.tag(), correlation[0], correlation[1], error);
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
