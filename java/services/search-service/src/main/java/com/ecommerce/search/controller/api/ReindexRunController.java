package com.ecommerce.search.controller.api;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.service.ReindexRunService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

/**
 * Admin reindex operations.
 *
 * <p>These live under {@code /api/v2/search/reindex-runs}, not under {@code /internal}:
 * {@code /internal/v1} is reserved for mTLS service-to-service traffic, while reindex is
 * an admin command that comes through the Gateway with a verified JWT
 * ({@code docs/search/domain.md §7}). The Gateway route for these two operations has a
 * higher priority than the public v2 route.
 *
 * <p>The audit actor comes from the trusted identity header, never from the request body.
 */
@RestController
public class ReindexRunController {

    private final ReindexRunService service;
    private final SearchProperties properties;

    public ReindexRunController(ReindexRunService service, SearchProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping(value = "/api/v2/search/reindex-runs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SearchDtos.ReindexRun> createReindexRun(
            @RequestBody SearchDtos.CreateReindexRunRequest request,
            HttpServletRequest httpRequest) throws IOException {

        String actor = requireAdmin(httpRequest);
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw SearchException.invalidParameter("reason", "must be between 1 and 500 characters");
        }

        SearchDtos.ReindexRun run = service.trigger(reason.trim(), actor);
        return ResponseEntity.accepted().location(URI.create(run.statusUrl())).body(run);
    }

    @GetMapping(value = "/api/v2/search/reindex-runs/{run_id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SearchDtos.ReindexRun> getReindexRun(@PathVariable("run_id") String runId,
                                                               HttpServletRequest httpRequest) throws IOException {
        requireAdmin(httpRequest);
        return ResponseEntity.status(HttpStatus.OK).body(service.status(runId));
    }

    /**
     * Authorises from the headers the Gateway injects after JWT verification. Traefik
     * strips any client-supplied {@code X-User-*} before ForwardAuth, so the presence of
     * these headers is itself the trust signal.
     */
    private String requireAdmin(HttpServletRequest request) {
        String userId = request.getHeader(properties.gateway().userIdHeader());
        String roles = request.getHeader(properties.gateway().rolesHeader());
        if (userId == null || userId.isBlank()) {
            throw new SearchException(ErrorCode.UNAUTHORIZED, "no trusted gateway identity on this request");
        }
        boolean isAdmin = roles != null && Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(role -> role.equalsIgnoreCase(properties.gateway().adminRole()));
        if (!isAdmin) {
            throw new SearchException(ErrorCode.FORBIDDEN, "reindex requires the ADMIN role");
        }
        return userId;
    }
}
