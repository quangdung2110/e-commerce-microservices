package com.ecommerce.search.benchmark;

import com.ecommerce.search.SearchServiceApplication;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.worker.job.FullReindexJob;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-scope entry point, packaged separately by the benchmark. Never included in the service jar.
 * Resumes the REAL FullReindexJob at CUTOVER with already populated target indices. It measures
 * projection preparation and alias handoff, not upstream exports or Kafka catch-up. The absent
 * Inventory session deliberately makes post-cutover tail bookkeeping INCOMPLETE.
 */
public final class ReadModelBenchMain {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{SearchServiceApplication.class, Bridge.class}, args);
    }

    @RestController
    @Profile("read-model-benchmark")
    public static class Bridge {
        private final JobStateRepository runs;
        private final FullReindexJob job;

        public Bridge(JobStateRepository runs, FullReindexJob job) {
            this.runs = runs;
            this.job = job;
        }

        @PostMapping("/benchmark/cutover")
        public Map<String, String> cutover(@RequestBody Map<String, String> targets) throws IOException {
            String id = "bench-" + UUID.randomUUID();
            Instant now = Instant.now();
            LeaseLockDocument lease = runs.acquireLease(LeaseLockDocument.REINDEX_LOCK, id, now,
                    now.plusSeconds(120)).orElseThrow();
            var state = new ReindexRunDocument(ReindexRunDocument.DOC_KIND, id, "CUTOVER", "CUTOVER",
                    "benchmark", "benchmark", now, null, now, null, null, targets.get("products"),
                    Map.of(), List.of(), Map.of("stage", "CUTOVER", "products_index", targets.get("products"),
                    "categories_index", targets.get("categories")));
            runs.saveRun(state);
            job.run(id, "benchmark", "benchmark", lease, state);
            return Map.of("run_id", id);
        }

        @GetMapping("/benchmark/runs/{id}")
        public ReindexRunDocument state(@PathVariable String id) throws IOException {
            return runs.findRun(id).orElseThrow();
        }
    }
}
