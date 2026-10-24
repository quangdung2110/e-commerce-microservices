package com.platform.promotionservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.service.DiscoveryService;
import com.platform.promotionservice.web.dto.CheckoutDtos;
import com.platform.promotionservice.web.dto.MoneyDto;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.boot.web.context.WebServerApplicationContext;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One bounded startup read, not a periodic keep-alive. No customer or financial mutation. */
@Component("voucherReadWarmup")
public class VoucherReadWarmup implements ApplicationRunner, HealthIndicator, ApplicationContextAware {
    private static final Logger log = LoggerFactory.getLogger(VoucherReadWarmup.class);
    private final DiscoveryService discovery;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Duration budget;
    private final int rounds;
    private final PromotionProperties properties;
    private ApplicationContext applicationContext;
    private volatile boolean ready;

    public VoucherReadWarmup(DiscoveryService discovery, ObjectMapper mapper, MeterRegistry meters, PromotionProperties properties,
                            @Value("${promotion.voucher-warmup.timeout:30s}") Duration budget,
                            @Value("${promotion.voucher-warmup.rounds:3}") int rounds) {
        if (budget.isZero() || budget.isNegative() || rounds < 1 || rounds > 10) {
            throw new IllegalArgumentException("Voucher warm-up requires positive timeout and 1..10 rounds");
        }
        this.discovery = discovery;
        this.mapper = mapper;
        this.meters = meters;
        this.budget = budget;
        this.rounds = rounds;
        this.properties = properties;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.applicationContext = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ready = false;
        long started = System.nanoTime();
        long deadline = started + budget.toNanos();
        String outcome = "failure";
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "voucher-read-warmup");
            thread.setDaemon(true);
            return thread;
        });
        var work = executor.submit(() -> {
            UUID technicalUser = UUID.randomUUID();
            var context = new CheckoutDtos.CheckoutContext(MoneyDto.vnd(350000), "STRIPE", "CHECKOUT",
                    List.of(new CheckoutDtos.CheckoutItem(UUID.randomUUID(), "startup", 1,
                            MoneyDto.vnd(350000), List.of())), Map.of());
            var request = new CheckoutDtos.VoucherOptionRequest(technicalUser, null, context, true, true, 50);
            int port = applicationContext instanceof WebServerApplicationContext web && web.getWebServer() != null
                    ? web.getWebServer().getPort() : -1;
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int round = 0; round < rounds; round++) {
                // Use the application mapper and proxied transactional service, including ORM/cache/evaluation.
                var decoded = mapper.readValue(mapper.writeValueAsBytes(request), CheckoutDtos.VoucherOptionRequest.class);
                var response = discovery.warmupOptions(decoded);
                mapper.readValue(mapper.writeValueAsBytes(response), CheckoutDtos.VoucherOptionList.class);
                if (port > 0) {
                    // Prime the real servlet/controller/validation/message-converter path as well.
                    // This fresh technical UUID owns no wallet: lazy expiry updates zero rows.
                    String caller = properties.getInternal().getOptionsClients().stream().findFirst()
                            .orElseThrow(() -> new IllegalStateException("Voucher warm-up needs an allowed options caller"));
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new IllegalStateException("Voucher warm-up deadline exceeded");
                    var probe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/internal/v1/promotion-voucher-options"))
                            .header("Content-Type", "application/json")
                            .header(properties.getInternal().getIdentityHeader(), caller)
                            .timeout(Duration.ofNanos(remaining))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(request))).build();
                    var wire = http.send(probe, HttpResponse.BodyHandlers.ofByteArray());
                    if (wire.statusCode() != 200) throw new IllegalStateException("Voucher HTTP warm-up returned " + wire.statusCode());
                    mapper.readValue(wire.body(), CheckoutDtos.VoucherOptionList.class);
                }
            }
            }
            return null;
        });
        try {
            work.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            ready = true;
            outcome = "success";
            log.info("Voucher read-path warm-up completed in {} ms ({} rounds)",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), rounds);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Voucher read-path warm-up failed; refusing startup traffic", error.getCause());
        } finally {
            work.cancel(true);
            executor.shutdownNow();
            Timer.builder("promotion.voucher.startup.warmup").tag("outcome", outcome)
                    .register(meters).record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    @Override
    public Health health() {
        return ready ? Health.up().build()
                : Health.outOfService().withDetail("reason", "voucher read-path warm-up incomplete").build();
    }
}
