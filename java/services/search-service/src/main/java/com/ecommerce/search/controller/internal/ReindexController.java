package com.ecommerce.search.controller.internal;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.ReindexRequest;
import com.ecommerce.search.dto.ReindexStatusResponse;
import com.ecommerce.search.dto.ReindexTriggerResponse;
import com.ecommerce.search.service.ReindexService;
import com.ecommerce.search.util.JwtClaimUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API controller for triggering and monitoring full Elasticsearch
 * reindex operations.
 *
 * <p>All endpoints require JWT with {@code role=ADMIN} (provided by the API
 * Gateway via the {@code X-User-Claims} header).
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /internal/v1/search/reindex} — trigger a new full reindex</li>
 *   <li>{@code GET /internal/v1/search/reindex/{runId}} — poll reindex status</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/search")
@RequiredArgsConstructor
public class ReindexController {

    private final ReindexService reindexService;
    private final JwtClaimUtil jwtClaimUtil;

    /**
     * Trigger a full reindex of the products Elasticsearch index.
     *
     * <p>Returns HTTP 202 Accepted immediately with a {@code run_id} that can
     * be used to poll the reindex status. The actual 3-phase reindex runs
     * asynchronously in a background thread.
     *
     * @param request  optional body specifying reason and trigger source
     * @param httpRequest the HTTP request (for JWT claims extraction)
     * @return 202 Accepted with the reindex job details
     * @throws SearchException with {@link ErrorCode#FORBIDDEN} if the caller
     *                         is not an ADMIN
     */
    @PostMapping("/reindex")
    public ResponseEntity<ReindexTriggerResponse> triggerReindex(
            @RequestBody(required = false) ReindexRequest request,
            HttpServletRequest httpRequest
    ) {
        // 1. Verify JWT role=ADMIN
        String role = jwtClaimUtil.getRole(httpRequest);
        if (!"ADMIN".equals(role)) {
            log.warn("Non-admin user attempted to trigger reindex: role={}", role);
            throw new SearchException(ErrorCode.FORBIDDEN, "JWT role phải là ADMIN");
        }

        // 2. Auto-populate MDC
        jwtClaimUtil.populateMdcContext(httpRequest);

        // 3. Trigger async reindex
        ReindexTriggerResponse response = reindexService.trigger(
            request != null ? request : new ReindexRequest("manual", "unknown")
        );

        log.info("Reindex triggered via API: runId={}", response.runId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Get the current status of a reindex job.
     *
     * @param runId       the run ID returned by {@link #triggerReindex}
     * @param httpRequest the HTTP request (for JWT claims extraction)
     * @return 200 OK with the current reindex status and progress
     * @throws SearchException with {@link ErrorCode#FORBIDDEN} if the caller
     *                         is not an ADMIN
     */
    @GetMapping("/reindex/{runId}")
    public ResponseEntity<ReindexStatusResponse> getReindexStatus(
            @PathVariable String runId,
            HttpServletRequest httpRequest
    ) {
        // 1. Verify JWT role=ADMIN
        String role = jwtClaimUtil.getRole(httpRequest);
        if (!"ADMIN".equals(role)) {
            log.warn("Non-admin user attempted to poll reindex status: role={}", role);
            throw new SearchException(ErrorCode.FORBIDDEN, "JWT role phải là ADMIN");
        }

        // 2. Auto-populate MDC
        jwtClaimUtil.populateMdcContext(httpRequest);

        // 3. Fetch status
        ReindexStatusResponse response = reindexService.getStatus(runId);
        return ResponseEntity.ok(response);
    }
}
