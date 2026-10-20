package com.ecommerce.order.benchmark;

import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.service.DispatchNudger;
import com.ecommerce.order.store.WorkflowOutboxStore;
import com.ecommerce.order.benchmark.baseline.LegacyWorkflowDispatcher;
import com.ecommerce.order.benchmark.baseline.LegacyWorkflowOutboxStore;
import com.ecommerce.order.worker.ClosedTargetResolver;
import com.ecommerce.order.worker.TemporalWorkflowGateway;
import com.ecommerce.order.worker.WorkflowDispatcher;
import com.ecommerce.order.worker.WorkflowGateway;
import com.ecommerce.order.workflow.WorkflowInput;
import com.ecommerce.order.workflow.WorkflowMessage;
import com.ecommerce.order.workflow.WorkflowNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.SmartLifecycle;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Opt-in measurement of the production dispatcher or frozen legacy dispatcher, real PostgreSQL and Temporal.
 * No Spring application, business worker, HTTP, fake RPC, injected delay or TestWorkflowEnvironment.
 * Run with scripts/ci/order-dispatch-bench.ps1; the compose databases are disposable and isolated. */
@EnabledIfEnvironmentVariable(named = "ORDER_DISPATCH_BENCH", matches = "true")
class WorkflowDispatcherBenchmarkTest {
    private static final int SECONDS = Integer.parseInt(env("ORDER_BENCH_SECONDS", "15"));
    private static final int BACKLOG = 1000;
    private static final String QUEUE = "order-dispatch-benchmark";
    private static final boolean LEGACY = env("ORDER_BENCH_VARIANT", "parallel").equals("legacy");
    private static final int THREADS = LEGACY ? 1 : 8;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Json json = new Json(mapper);
    private final Path output = Path.of(env("ORDER_BENCH_OUTPUT", "target/order-dispatch-bench"));
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TxSample> transactions = new ConcurrentLinkedQueue<>();
    private final Set<String> dispatchThreads = ConcurrentHashMap.newKeySet();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private final AtomicReference<Throwable> producerFailure = new AtomicReference<>();
    private final ThreadLocal<String> txKind = new ThreadLocal<>();
    private final ThreadLocal<Integer> txRows = ThreadLocal.withInitial(() -> 0);
    private final AtomicLong ackBatchSequence = new AtomicLong();
    private final List<String> targets = new ArrayList<>();
    private JdbcTemplate jdbc;
    private WorkflowOutboxStore rawStore;
    private TransactionTemplate producerTx;
    private TimedTx dispatchTx;
    private WorkflowGateway realGateway;
    private MeasuredStore measuredStore;
    private WorkflowServiceStubs stubs;
    private String scenario;
    private long origin;
    private Instant originUtc;

    @Test
    void benchmarkDispatcher() throws Exception {
        Files.createDirectories(output);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env("ORDER_BENCH_DB_URL", "jdbc:postgresql://localhost:25432/order_dispatch_bench"));
        config.setUsername("bench");
        config.setPassword("bench");
        config.setMaximumPoolSize(24);
        config.setMinimumIdle(4);
        config.setConnectionTimeout(3000);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        try (HikariDataSource ds = new HikariDataSource(config)) {
            jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("order_dispatch_bench");
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            rawStore = new WorkflowOutboxStore(new NamedParameterJdbcTemplate(ds));
            measuredStore = new MeasuredStore(new NamedParameterJdbcTemplate(ds));
            var manager = new DataSourceTransactionManager(ds);
            producerTx = new TransactionTemplate(manager);
            dispatchTx = new TimedTx(manager);
            stubs = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                    .setTarget(env("ORDER_BENCH_TEMPORAL_TARGET", "localhost:27233")).build());
            try {
                stubs.connect(Duration.ofSeconds(30));
                realGateway = new TemporalWorkflowGateway(WorkflowClient.newInstance(stubs));
                for (int i = 0; i < 100; i++) {
                    String id = "bench-message-target-" + UUID.randomUUID();
                    assertThat(realGateway.start(id, WorkflowNames.CHECKOUT_TYPE, QUEUE,
                            WorkflowInput.of(Map.of("checkout_token", UUID.randomUUID().toString()))))
                            .isEqualTo(WorkflowGateway.Result.STARTED);
                    targets.add(id);
                }
                writeEnvironment();
                runBurst("warmup", 500, false);
                runBurst("burst-50", 50, true);
                runBurst("burst-500", 500, true);
                double[] rates = new double[3];
                for (int i = 0; i < rates.length; i++) {
                    rates[i] = runSaturated("saturated-starts-" + (i + 1), false);
                }
                Arrays.sort(rates);
                int below = Math.max(10, (int) Math.round(rates[1] * 0.70 / 10) * 10);
                int above = Math.max(below + 10, (int) Math.round(rates[1] * 1.30 / 10) * 10);
                for (int rate : new LinkedHashSet<>(List.of(50, 100, 40, 70, below, above))) {
                    runArrivals("arrival-" + rate, rate);
                }
                runSaturated("saturated-mixed-50-50", true);
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), results);
            } finally {
                stubs.shutdown();
                stubs.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    private void reset(String name) {
        scenario = name;
        jdbc.execute("TRUNCATE order_workflow_dispatches, order_workflow_messages");
        entries.clear(); transactions.clear(); dispatchThreads.clear();
        completed.set(0); maxActive.set(0); producerFailure.set(null);
        ackBatchSequence.set(0);
        origin = System.nanoTime();
        originUtc = Instant.now();
    }

    private SmartLifecycle dispatcher() { return dispatcher(new DispatchNudger()); }

    private SmartLifecycle dispatcher(DispatchNudger nudger) {
        var beans = new StaticListableBeanFactory(Map.of("gateway", new MeasuredGateway()));
        var workers = new OrderProperties.Workers(true, "bench-single-thread", Duration.ofMillis(200), 50,
                Duration.ofSeconds(30), Duration.ofSeconds(30));
        var props = new OrderProperties(null, null, null, null, null, null, null, null, null, workers, null, null);
        return LEGACY ? new LegacyWorkflowDispatcher(measuredStore, beans.getBeanProvider(WorkflowGateway.class),
                mock(ClosedTargetResolver.class), nudger, dispatchTx, json, props, new SimpleMeterRegistry())
                : new WorkflowDispatcher(measuredStore, beans.getBeanProvider(WorkflowGateway.class),
                mock(ClosedTargetResolver.class), nudger, dispatchTx, json, props, new SimpleMeterRegistry());
    }

    private void runBurst(String name, int size, boolean report) throws Exception {
        reset(name);
        insert(size, false, 0, null);
        SmartLifecycle dispatcher = dispatcher();
        long begin = System.nanoTime();
        dispatcher.start();
        try {
            drain();
            long end = entries.values().stream().mapToLong(e -> e.acked).max().orElseThrow();
            if (report) finish(begin, end, end, 0, 0);
        } finally { dispatcher.stop(); awaitStopped(); }
    }

    private double runSaturated(String name, boolean mixed) throws Exception {
        reset(name);
        insert(BACKLOG, mixed, 0, null);
        SmartLifecycle dispatcher = dispatcher();
        long begin = System.nanoTime();
        long until = begin + Duration.ofSeconds(SECONDS).toNanos();
        Thread producer = Thread.ofPlatform().name("bench-backlog-producer").start(() -> {
            try {
                while (System.nanoTime() < until) {
                    if (entries.size() - completed.get() < BACKLOG) insert(100, mixed, 0, null);
                    else LockSupport.parkNanos(2_000_000);
                }
            } catch (Throwable e) { producerFailure.set(e); }
        });
        long cpuBegin = cpuNanos();
        dispatcher.start();
        try {
            producer.join();
            assertThat(producerFailure.get()).isNull();
            long cpuEnd = cpuNanos();
            drain();
            return finish(begin, until, System.nanoTime(), 0, cpuEnd - cpuBegin);
        } finally { dispatcher.stop(); awaitStopped(); }
    }

    private void runArrivals(String name, int rate) throws Exception {
        reset(name);
        DispatchNudger nudger = new DispatchNudger();
        SmartLifecycle dispatcher = dispatcher(nudger);
        dispatcher.start();
        long begin = System.nanoTime();
        long until = begin + Duration.ofSeconds(SECONDS).toNanos();
        long cpuBegin = cpuNanos();
        try {
            for (int i = 0; i < rate * SECONDS; i++) {
                long scheduled = begin + (long) i * 1_000_000_000L / rate;
                while (System.nanoTime() < scheduled) LockSupport.parkNanos(scheduled - System.nanoTime());
                insert(1, false, scheduled, nudger);
            }
            while (System.nanoTime() < until) LockSupport.parkNanos(until - System.nanoTime());
            long producerEnd = System.nanoTime();
            long cpuEnd = cpuNanos();
            drain();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("producer_elapsed_s", (producerEnd - begin) / 1e9);
            extra.put("configured_arrival_rate_s", rate);
            extra.put("enqueue_lateness_ms", stats(entries.values().stream()
                    .mapToDouble(e -> (e.committed - e.scheduled) / 1e6).toArray()));
            extra.put("intended_arrival_to_ack_ms", stats(entries.values().stream()
                    .mapToDouble(e -> (e.acked - e.scheduled) / 1e6).toArray()));
            finish(begin, until, System.nanoTime(), rate, cpuEnd - cpuBegin);
            results.getLast().putAll(extra);
        } finally { dispatcher.stop(); awaitStopped(); }
    }

    private void insert(int count, boolean mixed, long scheduled, DispatchNudger nudger) {
        var batch = new ArrayList<Entry>();
        producerTx.executeWithoutResult(s -> {
            for (int i = 0; i < count; i++) {
                UUID id = UUID.randomUUID();
                boolean message = mixed && i % 2 == 1;
                String key = message ? "bench-" + id : "order-checkout-" + id;
                Entry e = new Entry(id, key, message ? "message" : "start", scheduled);
                entries.put(key, e); batch.add(e);
                String input = json.write(Map.of("checkout_token", id.toString()));
                String digest = json.digest(Map.of("checkout_token", id.toString()));
                if (message) {
                    rawStore.insertMessage(key, null, targets.get(Math.floorMod(id.hashCode(), targets.size())), WorkflowNames.CHECKOUT_TYPE,
                            QUEUE, input, "BENCHMARK", "{}", json.digest(Map.of()));
                } else {
                    rawStore.insertDispatch(id, null, key, WorkflowNames.CHECKOUT_TYPE, QUEUE, input, digest);
                }
            }
            afterCommit(() -> {
                long now = System.nanoTime();
                batch.forEach(e -> e.committed = now);
                if (nudger != null) nudger.nudge();
            });
        });
    }

    private void drain() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (completed.get() < entries.size() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(producerFailure.get()).isNull();
        assertThat(completed.get()).isEqualTo(entries.size());
    }

    private void awaitStopped() throws InterruptedException {
        // stop() interrupts the production thread; allow its last pass to unwind before clearing maps.
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive()
                && t.getName().startsWith("order-workflow-")) && System.nanoTime() < end) Thread.sleep(5);
        assertThat(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                && t.getName().startsWith("order-workflow-"))).isTrue();
    }

    private double finish(long begin, long end, long drained, int arrivalRate, long cpu) throws Exception {
        List<Entry> all = entries.values().stream().sorted(Comparator.comparingLong(e -> e.acked)).toList();
        List<Entry> window = all.stream().filter(e -> e.acked >= begin && e.acked <= end).toList();
        List<Entry> starts = window.stream().filter(e -> e.kind.equals("start")).toList();
        List<Entry> messages = window.stream().filter(e -> e.kind.equals("message")).toList();
        long retries = jdbc.queryForObject("SELECT COUNT(*) FROM order_workflow_dispatches WHERE status <> 'STARTED' OR attempts <> 1", Long.class)
                + jdbc.queryForObject("SELECT COUNT(*) FROM order_workflow_messages WHERE status <> 'DELIVERED' OR attempts <> 1", Long.class);
        assertThat(retries).isZero();
        assertThat(maxActive.get()).isBetween(1, THREADS);
        if (LEGACY) assertThat(dispatchThreads).containsExactly("order-workflow-dispatcher");
        else assertThat(dispatchThreads).allMatch(name -> name.startsWith("order-workflow-start-")
                || name.equals("order-workflow-dispatcher"));
        if (!LEGACY && scenario.startsWith("saturated-starts-")) assertThat(maxActive.get()).isEqualTo(THREADS);
        assertThat(all).allMatch(e -> e.committed > 0 && e.claimed >= e.committed
                && e.rpcBegin >= e.claimed && e.rpcEnd >= e.rpcBegin && e.acked >= e.rpcEnd);
        long dbCount = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM order_workflow_dispatches) + (SELECT COUNT(*) FROM order_workflow_messages)", Long.class);
        assertThat(dbCount).isEqualTo(all.size());
        double seconds = (end - begin) / 1e9;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", scenario); row.put("window_s", seconds);
        row.put("variant", LEGACY ? "legacy" : "parallel"); row.put("dispatcher_threads", THREADS);
        row.put("window_begin_ns", begin - origin); row.put("window_end_ns", end - origin);
        row.put("drained_ns", drained - origin);
        row.put("window_start_utc", originUtc.plusNanos(begin - origin).toString());
        row.put("arrival_rate_s", arrivalRate); row.put("enqueued_total", all.size());
        row.put("completed_in_window", window.size()); row.put("starts_in_window", starts.size());
        long committedInWindow = all.stream().filter(e -> e.committed >= begin && e.committed <= end).count();
        row.put("committed_in_window", committedInWindow); row.put("committed_per_s", committedInWindow / seconds);
        row.put("messages_in_window", messages.size()); row.put("throughput_s", window.size() / seconds);
        row.put("starts_per_s", starts.size() / seconds); row.put("messages_per_s", messages.size() / seconds);
        row.put("backlog_at_window_end", all.stream().filter(e -> e.committed <= end && e.acked > end).count());
        row.put("drain_after_window_s", (drained - end) / 1e9);
        row.put("completed_after_drain", completed.get()); row.put("nonterminal_or_retried", retries);
        row.put("max_concurrent_gateway_calls", maxActive.get()); row.put("gateway_threads", dispatchThreads);
        row.put("jvm_cpu_seconds_in_window", cpu / 1e9);
        row.put("window", entryStats(window)); row.put("window_starts", entryStats(starts));
        row.put("window_messages", entryStats(messages)); row.put("all_including_drain", entryStats(all));
        Map<String, Object> txStats = new LinkedHashMap<>();
        for (String kind : List.of("claim_start", "claim_message", "ack_start", "ack_message", "renew_start")) {
            txStats.put(kind, stats(transactions.stream().filter(t -> t.kind.equals(kind)
                    && t.end >= begin && t.end <= end).mapToDouble(t -> t.nanos / 1e6).toArray()));
        }
        row.put("transaction_ms", txStats); results.add(row);
        long ackTransactions = transactions.stream().filter(t -> "ack_start".equals(t.kind)).count();
        long ackRows = transactions.stream().filter(t -> "ack_start".equals(t.kind)).mapToLong(t -> t.rows).sum();
        row.put("ack_start_transactions_total", ackTransactions); row.put("ack_start_rows_total", ackRows);
        row.put("ack_start_batch_mean", ackTransactions == 0 ? 0 : (double) ackRows / ackTransactions);
        writeSamples(all);
        try (var writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(output.resolve(scenario + "-transactions.csv.gz"))), StandardCharsets.UTF_8))) {
            writer.write("kind,duration_ns,end_ns,rows\n");
            for (TxSample sample : transactions) writer.write(sample.kind + "," + sample.nanos + ","
                    + (sample.end - origin) + "," + sample.rows + "\n");
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), results);
        System.out.printf("BENCH|%s|%.2f ops/s|%d/%d completed in %.3fs|backlog=%s%n",
                scenario, window.size() / seconds, window.size(), all.size(), seconds, row.get("backlog_at_window_end"));
        return window.size() / seconds;
    }

    private Map<String, Object> entryStats(List<Entry> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", data.size());
        result.put("queue_to_rpc_ms", stats(data.stream().mapToDouble(e -> (e.rpcBegin - e.committed) / 1e6).toArray()));
        result.put("claim_to_rpc_ms", stats(data.stream().mapToDouble(e -> (e.rpcBegin - e.claimed) / 1e6).toArray()));
        result.put("gateway_ms", stats(data.stream().mapToDouble(e -> (e.rpcEnd - e.rpcBegin) / 1e6).toArray()));
        result.put("commit_to_ack_ms", stats(data.stream().mapToDouble(e -> (e.acked - e.committed) / 1e6).toArray()));
        return result;
    }

    private static Map<String, Object> stats(double[] values) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("n", values.length);
        if (values.length == 0) return out;
        Arrays.sort(values);
        out.put("mean", Arrays.stream(values).average().orElseThrow());
        for (int p : new int[]{50, 95, 99}) out.put("p" + p, values[(int) Math.ceil(p / 100.0 * values.length) - 1]);
        out.put("max", values[values.length - 1]); return out;
    }

    private void writeSamples(List<Entry> all) throws Exception {
        try (var writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(output.resolve(scenario + ".csv.gz"))), StandardCharsets.UTF_8))) {
            writer.write("id,kind,scheduled_ns,committed_ns,claimed_ns,rpc_begin_ns,rpc_end_ns,acked_ns,result,ack_batch\n");
            for (Entry e : all) writer.write(String.join(",", e.key, e.kind, relative(e.scheduled), relative(e.committed),
                    relative(e.claimed), relative(e.rpcBegin), relative(e.rpcEnd), relative(e.acked), e.result,
                    Long.toString(e.ackBatch)) + "\n");
        }
    }

    private String relative(long value) { return value == 0 ? "" : Long.toString(value - origin); }

    private void writeEnvironment() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("utc", Instant.now().toString()); data.put("java", System.getProperty("java.runtime.version"));
        data.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        data.put("available_processors", Runtime.getRuntime().availableProcessors());
        data.put("jvm_max_heap_bytes", Runtime.getRuntime().maxMemory()); data.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
        for (String setting : List.of("fsync", "synchronous_commit", "full_page_writes", "shared_buffers"))
            data.put(setting, jdbc.queryForObject("SHOW " + setting, String.class));
        data.put("variant", LEGACY ? "legacy" : "parallel");
        data.put("dispatcher_threads", THREADS); data.put("dispatch_batch_size", 50); data.put("lease_s", 30);
        data.put("ack_batch_size", LEGACY ? 1 : 50); data.put("ack_flush_ms", LEGACY ? 0 : 20);
        data.put("dispatch_interval_ms", 200); data.put("hikari_max", 24); data.put("hikari_min_idle", 4);
        data.put("window_seconds", SECONDS); data.put("saturated_backlog_target", BACKLOG);
        data.put("temporal_workers", 0); data.put("temporal_sdk", "1.25.1");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("environment.json").toFile(), data);
    }

    private static long cpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }

    private static class Entry {
        final UUID id; final String key; final String kind; final long scheduled;
        volatile long committed, claimed, rpcBegin, rpcEnd, acked, ackBatch;
        volatile String result;
        Entry(UUID id, String key, String kind, long scheduled) {
            this.id = id; this.key = key; this.kind = kind; this.scheduled = scheduled;
        }
    }

    private record TxSample(String kind, long nanos, long end, int rows) { }

    private class TimedTx extends TransactionTemplate {
        TimedTx(DataSourceTransactionManager manager) { super(manager); }
        @Override public <T> T execute(TransactionCallback<T> callback) {
            txKind.remove(); txRows.set(0); long begin = System.nanoTime();
            try { return super.execute(callback); }
            finally {
                long end = System.nanoTime();
                transactions.add(new TxSample(txKind.get(), end - begin, end, txRows.get()));
                txKind.remove(); txRows.remove();
            }
        }
    }

    private class MeasuredStore extends LegacyWorkflowOutboxStore {
        MeasuredStore(NamedParameterJdbcTemplate jdbc) { super(jdbc); }
        @Override public List<DispatchRow> claimDispatches(String owner, int limit, Duration lease) {
            txKind.set("claim_start"); var rows = super.claimDispatches(owner, limit, lease);
            afterCommit(() -> { long now = System.nanoTime(); rows.forEach(r -> entries.get(r.workflowId()).claimed = now); });
            return rows;
        }
        @Override public List<MessageRow> claimMessages(String owner, int limit, Duration lease) {
            txKind.set("claim_message"); var rows = super.claimMessages(owner, limit, lease);
            afterCommit(() -> { long now = System.nanoTime(); rows.forEach(r -> entries.get(r.messageId()).claimed = now); });
            return rows;
        }
        @Override public void dispatchStarted(UUID id) {
            txKind.set("ack_start"); txRows.set(1); super.dispatchStarted(id);
            ack(entries.get("order-checkout-" + id));
        }
        @Override public List<UUID> completeDispatches(String owner, List<DispatchCompletion> batch) {
            txKind.set("ack_start");
            List<UUID> applied = super.completeDispatches(owner, batch); txRows.set(applied.size());
            afterCommit(() -> {
                long now = System.nanoTime(); long batchId = ackBatchSequence.incrementAndGet();
                for (UUID id : applied) {
                    Entry entry = entries.get("order-checkout-" + id);
                    entry.ackBatch = batchId; entry.acked = now; completed.incrementAndGet();
                }
            });
            return applied;
        }
        @Override public List<UUID> renewDispatches(String owner, List<DispatchRow> rows, Duration lease) {
            txKind.set("renew_start"); return super.renewDispatches(owner, rows, lease);
        }
        @Override public void messageDelivered(String id, String outcome, String replacement) {
            txKind.set("ack_message"); txRows.set(1); super.messageDelivered(id, outcome, replacement); ack(entries.get(id));
        }
        private void ack(Entry entry) {
            afterCommit(() -> { entry.ackBatch = ackBatchSequence.incrementAndGet();
                entry.acked = System.nanoTime(); completed.incrementAndGet(); });
        }
    }

    private class MeasuredGateway implements WorkflowGateway {
        @Override public Result start(String id, String type, String queue, WorkflowInput input) {
            Entry e = entries.get(id); before(e);
            try { Result r = realGateway.start(id, type, queue, input); e.result = r.name(); return r; }
            finally { after(e); }
        }
        @Override public Result signalWithStart(String id, String type, String queue, WorkflowInput input, WorkflowMessage message) {
            Entry e = entries.get(message.messageId()); before(e);
            try { Result r = realGateway.signalWithStart(id, type, queue, input, message); e.result = r.name(); return r; }
            finally { after(e); }
        }
        private void before(Entry e) {
            dispatchThreads.add(Thread.currentThread().getName());
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max); e.rpcBegin = System.nanoTime();
        }
        private void after(Entry e) { e.rpcEnd = System.nanoTime(); active.decrementAndGet(); }
    }
}
