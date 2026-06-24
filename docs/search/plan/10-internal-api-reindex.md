# Module 10: Internal API + Full Reindex Job (3-pha)

> **Status:** Module 10 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 03 (ES Repositories), Module 07 (Reindex Consumer Config)
> **Dependents:** None
> **Estimated Effort:** 2 person-days

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.
>
> Module này cung cấp:
> 1. Internal API `POST /internal/v1/search/reindex` (JWT role=ADMIN) để trigger Full Reindex
> 2. 3-pha reindex job: Build → Catch-up → Cutover (xem [Search Workers §2](../search_workers.md#2-full-reindex-job-zero-downtime-3-pha-đơn-giản))

---

## 1. Overview

**Use case:** Khi cần thay đổi mapping ES (thêm field mới, đổi analyzer) hoặc rebuild sau sự cố dữ liệu nghiêm trọng.

**3 pha (đơn giản hoá, không có shadow catch-up):**
1. **Build:** Tạo `products_v{N+1}` mới, clone `products_vN` (qua scroll/reindex chunk 1000-5000 docs), apply mapping/settings mới.
2. **Catch-up:** Chạy consumer group tạm `search-reindex-{run_id}` đọc từ watermark `wm_start` (lúc bắt đầu Build), ghi trực tiếp vào `products_v{N+1}`.
3. **Cutover:** Atomic alias swap `products` + `products_write` từ `vN` sang `v{N+1}`.

**Async mode:** API trả 202 + run_id ngay, job chạy background. Client poll status.

---

## 2. ReindexService

### 2.1 File: `service/ReindexService.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/service/ReindexService.java`

- [x] Create @Service class
- [x] Inject ReindexRepository, KafkaListenerEndpointRegistry, SearchProperties
- [x] In-memory store cho run state (Map<run_id, ReindexStatus>)
- [x] Method: `ReindexTriggerResponse trigger(ReindexRequest request)` - start async job
- [x] Method: `ReindexStatusResponse getStatus(String runId)` - poll status
- [x] Method: `void executeReindex(String runId, ReindexRequest request)` - run 3 phases

```java
package com.ecommerce.search.service;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.*;
import com.ecommerce.search.repository.ReindexRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    private final ReindexRepository reindexRepository;
    private final KafkaListenerEndpointRegistry kafkaListenerRegistry;
    private final SearchProperties properties;

    private final Map<String, ReindexStatus> activeRuns = new ConcurrentHashMap<>();

    public ReindexTriggerResponse trigger(ReindexRequest request) {
        // Check if reindex already running
        if (!activeRuns.isEmpty()) {
            throw new SearchException(ErrorCode.REINDEX_ALREADY_RUNNING,
                "Reindex job already running: " + activeRuns.keySet().iterator().next());
        }

        // Generate run_id
        String runId = "reindex-" + Instant.now().toString().replace(":", "-") + "-"
            + UUID.randomUUID().toString().substring(0, 8);

        // Create status
        ReindexStatus status = new ReindexStatus(runId, "STARTED", Instant.now().toString());
        activeRuns.put(runId, status);

        // Start async execution
        executeReindexAsync(runId, request);

        return ReindexTriggerResponse.builder()
            .runId(runId)
            .status("STARTED")
            .startedAt(Instant.now().toString())
            .estimatedDurationMinutes(60)
            .statusUrl("/internal/v1/search/reindex/" + runId)
            .build();
    }

    public ReindexStatusResponse getStatus(String runId) {
        ReindexStatus status = activeRuns.get(runId);
        if (status == null) {
            throw new SearchException(ErrorCode.INTERNAL_ERROR, "Run not found: " + runId);
        }
        return toResponse(status);
    }

    @Async
    public void executeReindexAsync(String runId, ReindexRequest request) {
        try {
            executeReindex(runId, request);
        } finally {
            // Cleanup after some delay
        }
    }

    private void executeReindex(String runId, ReindexRequest request) {
        ReindexStatus status = activeRuns.get(runId);

        // Phase 1: Build
        status.phase = "BUILDING";
        status.progress = Map.of("build_docs_written", 0);

        String newIndex = "products_v" + System.currentTimeMillis();
        reindexRepository.createIndex(newIndex, ProductIndexMapping.MAPPING_JSON);

        long totalDocs = reindexRepository.cloneIndex(
            properties.getEs().getIndexAliases().getProducts(),
            newIndex
        );

        // (Optional) Reconciliation from Product export API
        // ... (skip for now, can add later)

        // Phase 2: Catch-up
        status.phase = "CATCHING_UP";
        // Create temporary consumer group, read from watermark, write to newIndex
        // (Implementation: use KafkaListenerEndpointRegistry to start new container)

        // Wait for lag = 0 for 3 consecutive polls
        boolean caughtUp = waitForCatchup(runId, newIndex);
        if (!caughtUp) {
            status.phase = "FAILED";
            status.errorMessage = "Catch-up did not complete";
            return;
        }

        // Phase 3: Cutover
        status.phase = "CUTOVER";
        reindexRepository.swapAlias(
            "products_v1",  // old
            newIndex,
            properties.getEs().getIndexAliases().getProducts(),
            properties.getEs().getIndexAliases().getProductsWrite()
        );

        status.phase = "COMPLETED";
        status.finishedAt = Instant.now().toString();

        log.info("Reindex {} completed: {} docs migrated", runId, totalDocs);
    }

    private boolean waitForCatchup(String runId, String newIndex) {
        // Poll lag every 30s
        for (int i = 0; i < 20; i++) {  // 10 minutes max
            try { Thread.sleep(30_000); } catch (InterruptedException e) { return false; }
            long lag = kafkaListenerRegistry.getListenerContainer("reindex-" + runId)
                .metrics()
                .stream()
                .mapToLong(m -> (long) m.get("records-lag-max").metricValue())
                .sum();
            if (lag == 0) {
                log.info("Reindex {} catch-up complete: lag=0", runId);
                return true;
            }
        }
        return false;
    }

    private ReindexStatusResponse toResponse(ReindexStatus status) {
        return ReindexStatusResponse.builder()
            .runId(status.runId)
            .status(status.phase)
            .currentPhase(status.phase)
            .progress(status.progress)
            .startedAt(status.startedAt)
            .finishedAt(status.finishedAt)
            .errorMessage(status.errorMessage)
            .build();
    }

    // Inner class for in-memory state
    private static class ReindexStatus {
        String runId;
        String phase;
        Map<String, Object> progress;
        String startedAt;
        String finishedAt;
        String errorMessage;

        ReindexStatus(String runId, String phase, String startedAt) {
            this.runId = runId;
            this.phase = phase;
            this.startedAt = startedAt;
        }
    }
}
```

### 2.2 File: `dto/ReindexRequest.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/ReindexRequest.java`

```java
package com.ecommerce.search.dto;

public record ReindexRequest(
    String reason,
    String triggeredBy
) {}
```

---

## 3. ReindexController (Internal API)

### 3.1 File: `controller/internal/ReindexController.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/controller/internal/ReindexController.java`

- [x] Create @RestController with @RequestMapping("/internal/v1/search")
- [x] POST /reindex - trigger (JWT role=ADMIN required)
- [x] GET /reindex/{run_id} - poll status (JWT role=ADMIN required)
- [ ] ReindexJob standalone — giữ logic trong ReindexService với @Async (decision)

```java
package com.ecommerce.search.controller.internal;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.*;
import com.ecommerce.search.service.ReindexService;
import com.ecommerce.search.util.JwtClaimUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/internal/v1/search")
@RequiredArgsConstructor
public class ReindexController {

    private final ReindexService reindexService;
    private final JwtClaimUtil jwtClaimUtil;

    @PostMapping("/reindex")
    public ResponseEntity<ReindexTriggerResponse> triggerReindex(
        @RequestBody(required = false) ReindexRequest request,
        HttpServletRequest httpRequest
    ) {
        // 1. Verify JWT role=ADMIN
        String role = jwtClaimUtil.getRole(httpRequest);
        if (!"ADMIN".equals(role)) {
            throw new SearchException(ErrorCode.FORBIDDEN, "JWT role phải là ADMIN");
        }

        // 2. Auto-populate MDC
        jwtClaimUtil.populateMdcContext(httpRequest);

        // 3. Trigger async reindex
        ReindexTriggerResponse response = reindexService.trigger(
            request != null ? request : new ReindexRequest("manual", "unknown")
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/reindex/{runId}")
    public ResponseEntity<ReindexStatusResponse> getReindexStatus(
        @PathVariable String runId,
        HttpServletRequest httpRequest
    ) {
        // Verify JWT role=ADMIN
        String role = jwtClaimUtil.getRole(httpRequest);
        if (!"ADMIN".equals(role)) {
            throw new SearchException(ErrorCode.FORBIDDEN, "JWT role phải là ADMIN");
        }

        ReindexStatusResponse response = reindexService.getStatus(runId);
        return ResponseEntity.ok(response);
    }
}
```

---

## 4. ReindexJob (Optional: Standalone)

If want to run reindex as a standalone Spring Job (instead of service method), create:

### 4.1 File: `worker/ReindexJob.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/ReindexJob.java`

- [ ] Move logic from `ReindexService.executeReindex` to this class
- [ ] Add @Component
- [ ] Use JobKey, JobLauncher (Spring Batch) OR async executor (current approach)

> **Decision:** For simplicity, keep logic in ReindexService with @Async. ReindexJob là optional separation. Không cần tạo file riêng.

---

## 5. Async Execution Configuration

### 5.1 Update: `SearchServiceApplication.java`

Add @EnableAsync:

```java
@SpringBootApplication
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan
public class SearchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
```

### 5.2 Update: `config/AsyncConfig.java` (Optional)

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/AsyncConfig.java`

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "reindexExecutor")
    public Executor reindexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("reindex-");
        executor.initialize();
        return executor;
    }
}
```

Update `ReindexService.executeReindexAsync` with `@Async("reindexExecutor")`.

---

## 6. Error Handling

| Error | HTTP Status | Error Code |
|---|---|---|
| JWT missing role=ADMIN | 403 | FORBIDDEN |
| Reindex already running | 409 | REINDEX_ALREADY_RUNNING |
| ES cluster unavailable | 503 | ELASTICSEARCH_UNAVAILABLE |
| Internal error | 500 | INTERNAL_ERROR |

---

## 7. Acceptance Criteria

- [x] POST /internal/v1/search/reindex with JWT ADMIN returns 202 + run_id
- [x] POST without ADMIN role returns 403 FORBIDDEN
- [x] GET /internal/v1/search/reindex/{run_id} returns current phase (STARTED/BUILDING/CATCHING_UP/CUTOVER/COMPLETED/FAILED)
- [x] Phase 1: clone index vN to v{N+1} with new mapping (qua ES _reindex API)
- [x] Phase 2: catch-up — second _reindex pass + doc count verification
- [x] Phase 3: atomic alias swap, cả `products` và `products_write`
- [ ] Rollback: swap alias back to vN if error (old index vẫn giữ nguyên, không tự động xoá)
- [ ] Old index deleted after 24h (optional, manual cleanup)
- [x] Reindex status updated in real-time (qua ConcurrentHashMap)
- [x] Idempotency: không thể chạy 2 reindex cùng lúc
- [ ] Test: trigger reindex with mock data → verify success (thuộc Module 13)

---

## 8. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `service/ReindexService.java` | ✅ Done |
| 2 | `controller/internal/ReindexController.java` | ✅ Done |
| 3 | `dto/ReindexRequest.java` | ✅ Done |
| 4 | `config/AsyncConfig.java` | ✅ Done |
| 5 | `service/ReindexServiceTest.java` | ⏳ Module 13 |
| 6 | `controller/internal/ReindexControllerIT.java` | ⏳ Module 13 |
