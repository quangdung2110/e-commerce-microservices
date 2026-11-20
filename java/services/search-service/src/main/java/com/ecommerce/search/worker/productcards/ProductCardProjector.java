package com.ecommerce.search.worker.productcards;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.ecommerce.search.config.ProductIndexProperties;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.ProductCardIndex;
import com.ecommerce.search.repository.es.ProductCardStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** ADR-029: required Product read model. Each pass releases the shared lease so a reindex
 * can prepare its cards BEFORE moving all aliases. Every write pins a physical generation.
 */
@Component
public class ProductCardProjector implements SmartLifecycle {
    public static final String LEASE = "product-cards-projector";
    private static final Duration RECORD_EVERY = Duration.ofSeconds(15);
    private static final Logger log = LoggerFactory.getLogger(ProductCardProjector.class);
    private final ProductCardStore store;
    private final ProductCardIndex index;
    private final JobStateRepository jobs;
    private final ProductIndexProperties properties;
    private final Clock clock;
    private final String ownerId = "product-cards-" + UUID.randomUUID();
    private final Map<String, Long> written = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 200_000; }
    };
    private volatile boolean running;
    private Thread thread;
    private String feedSource;
    private String feedTarget;
    private Instant feedPosition;
    private Instant lastRecorded = Instant.EPOCH;
    private volatile Instant appliedThrough;

    @Autowired
    public ProductCardProjector(ProductCardStore store, ProductCardIndex index, JobStateRepository jobs,
                                ProductIndexProperties properties, MeterRegistry meters) {
        this(store, index, jobs, properties, meters, Clock.systemUTC());
    }
    ProductCardProjector(ProductCardStore store, ProductCardIndex index, JobStateRepository jobs,
                         ProductIndexProperties properties, MeterRegistry meters, Clock clock) {
        this.store = store; this.index = index; this.jobs = jobs; this.properties = properties; this.clock = clock;
        Gauge.builder("search.product_cards.lag", this, p -> p.appliedThrough == null ? Double.NaN
                : Duration.between(p.appliedThrough, clock.instant()).toMillis() / 1000.0)
                .description("Seconds since newest applied change; increases on an idle catalog too")
                .baseUnit("seconds").register(meters);
    }

    public synchronized void pollOnce() throws IOException {
        Optional<LeaseLockDocument> owned = acquire(ownerId);
        if (owned.isEmpty()) return;
        LeaseLockDocument lease = owned.get();
        try {
            Optional<String> source = index.currentSourceIndex();
            if (source.isEmpty()) return;
            if (!source.equals(index.mirroredSourceIndex())) {
                try (PreparedGeneration prepared = build(source.get(), lease, () -> {})) {
                    prepared.checkOwnership();
                    if (!index.currentSourceIndex().equals(source)) throw new IOException("SKU alias moved during card build");
                    index.publish(prepared.index());
                    prepared.published();
                    written.clear(); feedSource = null; appliedThrough = clock.instant();
                }
                return;
            }
            String target = index.currentGeneration().orElseThrow(() -> new IOException("card alias disappeared"));
            if (!source.get().equals(feedSource) || !target.equals(feedTarget)) {
                feedSource = source.get(); feedTarget = target;
                feedPosition = index.recordedProgress().orElse(clock.instant().minus(properties.lookback()));
                written.clear();
            }
            applyChanges(source.get(), target, lease);
        } finally { jobs.releaseLease(lease); }
    }

    /** Caller keeps this handle through checkpoint persistence and alias swap. Target SKUs
     * are private at CUTOVER; post-swap tail changes are covered by the saved feed position.
     */
    public PreparedGeneration prepareForCutover(String source, Runnable checkpoint) throws IOException {
        Optional<LeaseLockDocument> lease;
        do {
            checkpoint.run();
            lease = acquire(ownerId + "-cutover-" + UUID.randomUUID());
            if (lease.isEmpty()) {
                try { Thread.sleep(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("cutover interrupted", e); }
            }
        } while (lease.isEmpty());
        try { return build(source, lease.get(), checkpoint); }
        catch (IOException | RuntimeException e) { jobs.releaseLease(lease.get()); throw e; }
    }
    private Optional<LeaseLockDocument> acquire(String owner) throws IOException {
        Instant now = clock.instant();
        return jobs.acquireLease(LEASE, owner, now, now.plus(properties.leaseDuration()));
    }
    private void renew(LeaseLockDocument lease) throws IOException {
        Instant now = clock.instant();
        if (!jobs.renewLease(lease, now, now.plus(properties.leaseDuration())))
            throw new IOException("lost the " + LEASE + " lease");
    }
    private PreparedGeneration build(String source, LeaseLockDocument lease, Runnable checkpoint) throws IOException {
        Instant started = clock.instant();
        String target = index.createGeneration(source);
        PreparedGeneration prepared = new PreparedGeneration(target, lease, checkpoint);
        try {
            List<ProductCard> batch = new ArrayList<>(properties.rebuildBatch());
            long[] count = {0};
            store.scanAllProducts(source, card -> {
                if (!card.deleted()) { batch.add(card); count[0]++; }
                if (batch.size() >= properties.rebuildBatch()) {
                    try { flush(target, batch, prepared); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                }
            }, () -> {
                try { prepared.checkOwnership(); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
            flush(target, batch, prepared);
            index.recordProgress(target, source, started.minus(properties.lookback()));
            index.prepare(target);
            prepared.checkOwnership();
            log.info("product cards {} prepared: {} products from {} in {} ms", target, count[0], source,
                    Duration.between(started, clock.instant()).toMillis());
            return prepared;
        } catch (IOException | RuntimeException e) {
            index.discard(target);
            if (e instanceof UncheckedIOException io) throw io.getCause();
            throw e;
        }
    }
    private void flush(String target, List<ProductCard> batch, PreparedGeneration prepared) throws IOException {
        prepared.checkOwnership();
        if (!batch.isEmpty()) store.write(target, List.copyOf(batch));
        batch.clear(); prepared.checkOwnership();
    }
    public final class PreparedGeneration implements AutoCloseable {
        private final String target;
        private final LeaseLockDocument lease;
        private final Runnable checkpoint;
        private boolean published;
        private PreparedGeneration(String target, LeaseLockDocument lease, Runnable checkpoint) {
            this.target = target; this.lease = lease; this.checkpoint = checkpoint;
        }
        public String index() { return target; }
        public void checkOwnership() throws IOException { checkpoint.run(); renew(lease); }
        public void published() { published = true; }
        @Override public void close() throws IOException {
            // discard also checks the live alias: an unknown swap response cannot delete serving data.
            try { if (!published) index.discard(target); }
            finally { jobs.releaseLease(lease); }
        }
    }
    private void applyChanges(String source, String target, LeaseLockDocument lease) throws IOException {
        Instant since = feedPosition.minus(properties.lookback());
        Instant newest = feedPosition;
        List<FieldValue> after = null;
        while (true) {
            renew(lease);
            List<ProductCardStore.Change> changes = store.changes(source, since, after);
            Set<String> stale = new LinkedHashSet<>();
            for (var change : changes) {
                Instant at = Instant.ofEpochMilli(change.indexedAtMillis());
                if (at.isAfter(newest)) newest = at;
                if (change.indexedAtMillis() > written.getOrDefault(change.productId(), -1L)) stale.add(change.productId());
            }
            List<String> ids = new ArrayList<>(stale);
            for (int from = 0; from < ids.size(); from += properties.rebuildBatch()) {
                renew(lease);
                List<ProductCard> cards = store.loadProducts(source,
                        ids.subList(from, Math.min(ids.size(), from + properties.rebuildBatch())));
                store.write(target, cards);
                cards.forEach(card -> written.put(card.productId(), card.version()));
            }
            if (changes.size() < properties.scanPageSize()) break;
            after = changes.get(changes.size() - 1).sort();
        }
        feedPosition = newest; appliedThrough = newest;
        if (Duration.between(lastRecorded, clock.instant()).compareTo(RECORD_EVERY) >= 0) {
            renew(lease); index.recordProgress(target, source, feedPosition); lastRecorded = clock.instant();
        }
    }
    @Override public void start() { running = properties.enabled(); }
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!running || thread != null) return;
        thread = new Thread(this::loop, "product-card-projector"); thread.setDaemon(true); thread.start();
    }
    private void loop() {
        while (running) {
            try { Thread.sleep(properties.pollInterval().toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try { pollOnce(); }
            catch (IOException | RuntimeException e) { log.warn("product card projection pass failed: {}", e.toString()); }
        }
    }
    @Override public void stop() { running = false; if (thread != null) thread.interrupt(); }
    @Override public boolean isRunning() { return running; }
}
