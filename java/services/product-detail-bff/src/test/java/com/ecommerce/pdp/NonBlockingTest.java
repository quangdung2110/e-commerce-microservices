package com.ecommerce.pdp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline request không được gọi blocking I/O trên event loop (ADR-006). BlockHound được
 * cài sau một request làm nóng — lần đầu nạp class đọc file jar, không phải hành vi của
 * request — và ghi lại vi phạm thay vì ném lỗi, để không ảnh hưởng test khác trong cùng JVM.
 */
class NonBlockingTest extends AbstractPdpTest {

    private static final List<String> BLOCKING_CALLS = new CopyOnWriteArrayList<>();
    /** Worker của một thread pool đứng chờ task kế tiếp: trạng thái rảnh, không phải chặn. */
    private static final String IDLE_WORKER_FRAME = "java.util.concurrent.ThreadPoolExecutor#getTask";
    private static volatile boolean installed;

    @Test
    @DisplayName("Một request PDP đầy đủ không có call blocking nào trên event loop")
    void requestPipelineNeverBlocksTheEventLoop() {
        stubHealthyUpstreams();
        getPdp("smart-watch").expectStatus().isOk();
        installBlockHound();
        // Đối chứng: BlockHound thật sự hoạt động, nên "không có vi phạm" bên dưới có nghĩa.
        BLOCKING_CALLS.clear();
        Mono.fromRunnable(NonBlockingTest::sleepBriefly).subscribeOn(Schedulers.parallel()).block();
        assertThat(BLOCKING_CALLS).isNotEmpty();
        BLOCKING_CALLS.clear();

        getPdp("smart-watch").expectStatus().isOk();
        stubReview(status(500));
        getPdp("smart-watch").expectStatus().isOk();

        assertThat(BLOCKING_CALLS).isEmpty();
    }

    /** Các frame gần nhất (bỏ BlockHound và chính test), đủ để biết ai đã chặn event loop. */
    private static List<String> callers() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .filter(name -> !name.startsWith("reactor.blockhound")
                        && !name.startsWith("com.ecommerce.pdp.NonBlockingTest"))
                .limit(14)
                .toList());
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
        // builder() không nạp integration nào: thiếu chúng thì không thread nào bị coi là
        // non-blocking (event loop của Netty, scheduler của Reactor) và BlockHound im lặng.
        BlockHound.Builder builder = BlockHound.builder();
        ServiceLoader.load(BlockHoundIntegration.class).forEach(builder::with);
        builder
                // Worker của Schedulers.parallel() (nơi Mono.timeout hẹn giờ) đứng chờ task kế
                // tiếp trong getTask -> take: đó là trạng thái rảnh của scheduler, không phải
                // pipeline request chặn event loop.
                .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask")
                // Ghi log ra console là chấp nhận được; mọi thứ khác là lỗi thiết kế.
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "writeBytes")
                .allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "subAppend")
                .blockingMethodCallback(method -> {
                    List<String> callers = callers();
                    // Allowance ở trên chỉ áp dụng cho lần vào getTask sau khi cài; worker
                    // đã đứng chờ từ trước (do test class chạy trước tạo ra) vẫn bị báo.
                    if (!callers.contains(IDLE_WORKER_FRAME)) {
                        BLOCKING_CALLS.add(method + " on " + Thread.currentThread().getName() + " via " + callers);
                    }
                })
                .install();
        installed = true;
    }
}
