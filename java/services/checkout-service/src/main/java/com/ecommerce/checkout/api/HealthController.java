package com.ecommerce.checkout.api;

import com.ecommerce.checkout.store.SessionStore;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Probes (operations.md, "Health va degradation"). Liveness is the process only. Readiness needs Redis
 * to answer, every Lua script loaded, and the application accepting traffic (it turns false the moment a
 * SIGTERM starts the graceful shutdown). Required configuration was validated at startup, so a
 * mis-configured instance never gets here. Readiness never fans out to a downstream service, and an open
 * circuit does not make Checkout unready.
 */
@RestController
public class HealthController {

    private final SessionStore store;
    private final ApplicationAvailability availability;

    public HealthController(SessionStore store, ApplicationAvailability availability) {
        this.store = store;
        this.availability = availability;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/health/ready")
    public Mono<ResponseEntity<Map<String, String>>> ready() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return Mono.just(answer(false));
        }
        return store.ready()
                .flatMap(ok -> ok ? Mono.just(true)
                        // A Redis restart drops the script cache: load again before declaring the instance unready.
                        : store.loadScripts().then(store.ready()).onErrorReturn(false))
                .map(HealthController::answer);
    }

    private static ResponseEntity<Map<String, String>> answer(boolean ready) {
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", ready ? "UP" : "DOWN"));
    }
}
