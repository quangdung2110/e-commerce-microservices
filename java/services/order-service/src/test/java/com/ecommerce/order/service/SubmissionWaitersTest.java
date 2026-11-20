package com.ecommerce.order.service;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.ecommerce.order.common.RequestCorrelation;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.config.SubmissionWaiterProperties;
import com.ecommerce.order.store.SubmissionStore;
import com.ecommerce.order.store.SubmissionStore.CheckpointRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubmissionWaitersTest {
    private final SubmissionStore store = mock(SubmissionStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private SubmissionWaiters waiters;

    private SubmissionWaiters create(int limit, int threads, int batch) {
        OrderProperties properties = mock(OrderProperties.class);
        OrderProperties.Clients clients = mock(OrderProperties.Clients.class);
        when(properties.clients()).thenReturn(clients);
        when(clients.paymentRead()).thenReturn(Duration.ofSeconds(2));
        when(clients.connectTimeout()).thenReturn(Duration.ofSeconds(1));
        return waiters = new SubmissionWaiters(store, new SubmissionWaiterProperties(limit, threads, batch,
                Duration.ofHours(1), Duration.ofSeconds(1)), properties, meters);
    }

    @AfterEach
    void stop() {
        if (waiters != null) waiters.close();
        meters.close();
        RequestCorrelation.clear();
        MDC.clear();
    }

    private CheckpointRow row(UUID token, String state) {
        return new CheckpointRow(token, state, 42L, null, Instant.now());
    }

    private void completed(DeferredResult<?> result) {
        await().atMost(Duration.ofSeconds(3)).until(result::hasResult);
    }

    @Test
    void registrationReadsAlreadyCommittedResultWithoutNeedingANotification() {
        create(4, 1, 4);
        UUID token = UUID.randomUUID();
        when(store.checkpoint(token)).thenReturn(Optional.of(row(token, "PAYMENT_READY")));
        DeferredResult<?> result = waiters.await(token, Duration.ofSeconds(2), null,
                checkpoint -> ResponseEntity.status(200).body(checkpoint.state()));
        completed(result);
        assertThat(((ResponseEntity<?>) result.getResult()).getStatusCode().value()).isEqualTo(200);
        await().untilAsserted(() -> assertThat(meters.get("order.checkout.waiters").gauge().value()).isZero());
    }

    @Test
    void duplicateRequestsKeepTheirOwnCreationStatusAndCompleteOnlyOnce() {
        create(4, 2, 4);
        UUID token = UUID.randomUUID();
        AtomicReference<CheckpointRow> state = new AtomicReference<>(row(token, "PREPARING"));
        when(store.checkpoint(token)).thenAnswer(invocation -> Optional.of(state.get()));
        when(store.terminalTokens(any())).thenReturn(List.of(token));
        AtomicInteger renders = new AtomicInteger();
        DeferredResult<?> first = waiters.await(token, Duration.ofSeconds(2), null, checkpoint -> {
            renders.incrementAndGet();
            return ResponseEntity.status(201).body("created");
        });
        DeferredResult<?> replay = waiters.await(token, Duration.ofSeconds(2), null, checkpoint -> {
            renders.incrementAndGet();
            return ResponseEntity.ok("replay");
        });
        await().untilAsserted(() -> verify(store, atLeast(2)).checkpoint(token));
        state.set(row(token, "PAYMENT_READY"));
        waiters.signal(token);
        // Recovery also repairs a signal racing an already-in-flight initial DB read.
        waiters.reconcile();
        completed(first);
        completed(replay);
        waiters.signal(token);
        assertThat(((ResponseEntity<?>) first.getResult()).getStatusCode().value()).isEqualTo(201);
        assertThat(((ResponseEntity<?>) replay.getResult()).getStatusCode().value()).isEqualTo(200);
        assertThat(renders.get()).isEqualTo(2);
    }

    @Test
    void deadlineAndCapacityReturnRetryableInProgressAndReleaseSlots() {
        create(1, 1, 4);
        UUID token = UUID.randomUUID();
        AtomicReference<CheckpointRow> state = new AtomicReference<>(row(token, "PREPARING"));
        when(store.checkpoint(token)).thenAnswer(invocation -> Optional.of(state.get()));
        DeferredResult<?> accepted = waiters.await(token, Duration.ofMillis(150), null, row -> ResponseEntity.ok("x"));
        DeferredResult<?> rejected = waiters.await(token, Duration.ofSeconds(2), null, row -> ResponseEntity.ok("x"));
        assertThat(((ApiException) rejected.getResult()).code()).isEqualTo(ErrorCode.ORDER_UNAVAILABLE);
        assertThat(((ApiException) rejected.getResult()).retryAfterSeconds()).isEqualTo(2);
        completed(accepted);
        assertThat(((ApiException) accepted.getResult()).code()).isEqualTo(ErrorCode.SUBMISSION_IN_PROGRESS);
        verify(store, times(1)).checkpoint(token); // No per-request polling while PREPARING.
        await().untilAsserted(() -> assertThat(meters.get("order.checkout.waiters").gauge().value()).isZero());
        state.set(row(token, "FAILED"));
        DeferredResult<?> next = waiters.await(token, Duration.ofSeconds(2), null, row -> ResponseEntity.status(409).body("failed"));
        completed(next);
        assertThat(next.getResult()).isInstanceOf(ResponseEntity.class);
    }

    @Test
    void missedSignalsAreRepairedInBoundedBatches() {
        create(4, 2, 1);
        List<UUID> tokens = List.of(UUID.randomUUID(), UUID.randomUUID());
        AtomicReference<String> state = new AtomicReference<>("PREPARING");
        when(store.checkpoint(any())).thenAnswer(i -> Optional.of(row(i.getArgument(0), state.get())));
        when(store.terminalTokens(any())).thenAnswer(i -> List.copyOf(i.getArgument(0)));
        List<DeferredResult<ResponseEntity<Object>>> results = tokens.stream().map(token ->
                waiters.await(token, Duration.ofSeconds(2), null, row -> ResponseEntity.ok(row.state()))).toList();
        await().untilAsserted(() -> verify(store, atLeast(2)).checkpoint(any()));
        state.set("RECALCULATION_REQUIRED");
        waiters.reconcile();
        results.forEach(this::completed);
        verify(store, times(2)).terminalTokens(argThat(batch -> batch.size() == 1));
        results.forEach(result -> assertThat(((ResponseEntity<?>) result.getResult()).getBody())
                .isEqualTo("RECALCULATION_REQUIRED"));
    }

    @Test
    void restoresCallerContextForPaymentAndDoesNotLeakItToTheNextResponse() {
        create(4, 1, 4);
        when(store.checkpoint(any())).thenAnswer(i -> Optional.of(row(i.getArgument(0), "PAYMENT_READY")));
        String trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        RequestCorrelation context = RequestCorrelation.fromHeaders("checkout-request-context", trace, "vendor=a", "5000");
        DeferredResult<?> first = waiters.await(UUID.randomUUID(), Duration.ofSeconds(2), context, row -> {
            assertThat(RequestCorrelation.current()).isSameAs(context);
            assertThat(RequestCorrelation.effectiveTimeout(Duration.ofSeconds(10), Duration.ofMillis(500)))
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(MDC.get("request_id")).isEqualTo(context.requestId());
            return ResponseEntity.ok("first");
        });
        completed(first);
        assertThat(first.getResult()).isInstanceOf(ResponseEntity.class);
        DeferredResult<?> next = waiters.await(UUID.randomUUID(), Duration.ofSeconds(2), null, row -> {
            assertThat(RequestCorrelation.current()).isNull();
            assertThat(MDC.get("request_id")).isNull();
            return ResponseEntity.ok("next");
        });
        completed(next);
        assertThat(((ResponseEntity<?>) next.getResult()).getBody()).isEqualTo("next");
    }

    @Test
    void slowPaymentDoesNotBlockAnotherResponseOrTheNotificationCaller() throws Exception {
        create(4, 2, 4);
        when(store.checkpoint(any())).thenAnswer(i -> Optional.of(row(i.getArgument(0), "PAYMENT_READY")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            DeferredResult<?> slow = waiters.await(UUID.randomUUID(), Duration.ofSeconds(2), null, row -> {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ResponseEntity.ok("slow");
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            DeferredResult<?> fast = waiters.await(UUID.randomUUID(), Duration.ofSeconds(2), null,
                    row -> ResponseEntity.ok("fast"));
            completed(fast);
            assertThat(slow.hasResult()).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void releasesCapacityEvenWhenTheContainerCannotDispatchTheCompletedResponse() throws Exception {
        create(1, 1, 1);
        UUID token = UUID.randomUUID();
        when(store.checkpoint(token)).thenReturn(Optional.of(row(token, "PAYMENT_READY")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            DeferredResult<?> result = waiters.await(token, Duration.ofSeconds(2), null, row -> {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ResponseEntity.ok("ready");
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            result.setResultHandler(value -> { throw new IllegalStateException("connection already closed"); });
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(meters.get("order.checkout.waiters").gauge().value()).isZero());
        } finally { release.countDown(); }
    }

    @Test
    void callerDeadlineAlsoBoundsAResponseAlreadyFetchingPayment() throws Exception {
        create(2, 1, 2);
        UUID token = UUID.randomUUID();
        when(store.checkpoint(token)).thenReturn(Optional.of(row(token, "PAYMENT_READY")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            DeferredResult<?> result = waiters.await(token, Duration.ofSeconds(1),
                    RequestCorrelation.fromHeaders("checkout-deadline", null, null, "200"), row -> {
                        entered.countDown();
                        try { release.await(2, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return ResponseEntity.ok("too late");
                    });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            completed(result);
            assertThat(((ApiException) result.getResult()).code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
            release.countDown();
            assertThat(meters.get("order.checkout.waiters").gauge().value()).isZero();
        } finally { release.countDown(); }
    }
}
