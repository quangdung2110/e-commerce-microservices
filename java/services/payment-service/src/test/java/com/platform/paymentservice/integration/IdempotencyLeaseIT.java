package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.IdempotencyClaim;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyLeaseIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void one_concurrent_owner_wins_and_expired_lease_is_taken_over_with_fencing() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<IdempotencyClaim>> tasks = List.of(
                    () -> claim("owner-a", NOW), () -> claim("owner-b", NOW));
            List<IdempotencyClaim> claims = pool.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); } catch (Exception error) { throw new RuntimeException(error); }
            }).toList();
            assertThat(claims.stream().filter(IdempotencyClaim.Acquired.class::isInstance).count()).isEqualTo(1);
            assertThat(claims.stream().filter(IdempotencyClaim.InProgress.class::isInstance).count()).isEqualTo(1);
            String firstOwner = claims.get(0) instanceof IdempotencyClaim.Acquired ? "owner-a" : "owner-b";
            String secondOwner = firstOwner.equals("owner-a") ? "owner-b" : "owner-a";
            IdempotencyClaim.Acquired takeover = (IdempotencyClaim.Acquired) claim(secondOwner, NOW.plusSeconds(46));
            assertThat(takeover.leaseToken()).isEqualTo(1);
            assertThat(idempotency.complete("1234567890123456", IdempotencyAction.INITIATE,
                    firstOwner, 0, new StoredHttpResponse("TXN_A", 200, "{}"), NOW.plusSeconds(47))).isFalse();
            assertThat(idempotency.complete("1234567890123456", IdempotencyAction.INITIATE,
                    secondOwner, 1, new StoredHttpResponse("TXN_A", 200, "{}"), NOW.plusSeconds(47))).isTrue();
            assertThat(claim("owner-c", NOW.plusSeconds(48))).isInstanceOf(IdempotencyClaim.Completed.class);
        } finally {
            pool.shutdownNow();
        }
    }

    private IdempotencyClaim claim(String owner, Instant now) {
        return idempotency.claim("1234567890123456", IdempotencyAction.INITIATE,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                owner, now, Duration.ofSeconds(45), NOW.plusSeconds(960));
    }
}
