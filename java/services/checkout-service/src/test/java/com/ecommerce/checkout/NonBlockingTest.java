package com.ecommerce.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request pipeline never calls blocking I/O on the event loop (ADR-013). BlockHound is installed
 * after a warm-up request, because first use loads classes from the jar; it records violations instead of
 * throwing, so it cannot affect other tests in the same JVM.
 */
class NonBlockingTest extends AbstractCheckoutTest {

    private static final List<String> BLOCKING_CALLS = new CopyOnWriteArrayList<>();
    /** A worker of a thread pool waiting for its next task: idle, not the pipeline blocking. */
    private static final String IDLE_WORKER_FRAME = "java.util.concurrent.ThreadPoolExecutor#getTask";
    private static volatile boolean installed;

    @Test
    @DisplayName("Preview, voucher options, Submit, replay and recovery never block the event loop")
    void pipelineNeverBlocksTheEventLoop() {
        String user = newUser();
        stubPromotionOptions(com.ecommerce.checkout.support.Fixtures.voucherOptions(List.of()));
        // Warm-up: first use loads classes and opens connections.
        String warm = previewOk(user).path("checkout_token").asText();
        voucherOptions(user, warm).expectStatus().isOk();
        submit(user, warm).expectStatus().isCreated();
        submit(user, warm).expectStatus().isOk();

        installBlockHound();
        BLOCKING_CALLS.clear();
        Mono.fromRunnable(NonBlockingTest::sleepBriefly).subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
        assertThat(BLOCKING_CALLS).as("control: BlockHound is really watching").isNotEmpty();
        BLOCKING_CALLS.clear();

        String token = previewOk(user).path("checkout_token").asText();
        voucherOptions(user, token).expectStatus().isOk();
        submit(user, token).expectStatus().isCreated();
        submit(user, token).expectStatus().isOk();
        status(user, token).expectStatus().isOk();
        stubOrderPost(problem(503, "UNAVAILABLE"));
        String second = previewOk(user).path("checkout_token").asText();
        submit(user, second).expectStatus().isAccepted();
        stubOrderGet("PAYMENT_READY");
        status(user, second).expectStatus().isOk();

        assertThat(BLOCKING_CALLS).isEmpty();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void installBlockHound() {
        if (installed) {
            return;
        }
        // builder() loads no integration: without them no thread counts as non-blocking and BlockHound is silent.
        BlockHound.Builder builder = BlockHound.builder();
        ServiceLoader.load(BlockHoundIntegration.class).forEach(builder::with);
        builder
                // An idle worker of a thread pool waiting for its next task is not the pipeline blocking.
                .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask")
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "writeBytes")
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "subAppend")
                .blockingMethodCallback(method -> {
                    List<String> callers = callers();
                    // The allowance above only covers entering getTask after installation; a worker that has
                    // been idle since before (created by an earlier test class) is still reported.
                    if (!callers.contains(IDLE_WORKER_FRAME)) {
                        BLOCKING_CALLS.add(method + " on " + Thread.currentThread().getName() + " via " + callers);
                    }
                })
                .install();
        installed = true;
    }

    private static List<String> callers() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .filter(name -> !name.startsWith("reactor.blockhound")
                        && !name.startsWith("com.ecommerce.checkout.NonBlockingTest"))
                .limit(40)
                .toList());
    }
}
