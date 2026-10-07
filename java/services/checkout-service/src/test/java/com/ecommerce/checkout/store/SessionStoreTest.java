package com.ecommerce.checkout.store;

import com.ecommerce.checkout.AbstractCheckoutTest;
import com.ecommerce.checkout.crypto.SecureIds;
import com.ecommerce.checkout.domain.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lua state machine against a real Redis, without HTTP: the guarantees the services above rely on
 * (data.md, "Atomic operations").
 */
class SessionStoreTest extends AbstractCheckoutTest {

    @Autowired
    private SessionStore store;

    private static final Duration WAIT = Duration.ofSeconds(5);

    private record Quote(String user, String token, String ref) {
    }

    private Quote quote() {
        String user = newUser();
        String token = SecureIds.uuid();
        long now = clock.millis();
        SessionStore.Created created = store.createPreview(user, SecureIds.uuid(), "fingerprint", null, token,
                "snapshot-cipher", "response-cipher", now, now + 20 * 60_000, "v1").block(WAIT);
        assertThat(created).isInstanceOf(SessionStore.Created.Done.class);
        return new Quote(user, token, store.findControl(token).block(WAIT).orElseThrow().ref());
    }

    private SessionStore.Claim claim(Quote quote, String attempt) {
        return store.claim(quote.ref(), quote.user(), attempt, "payload-cipher", clock.millis()).block(WAIT);
    }

    private SessionState state(Quote quote) {
        return store.findControl(quote.token()).block(WAIT).orElseThrow().state();
    }

    @Test
    @DisplayName("Preview writes the session, the active pointer and the replay with the documented TTLs")
    void previewTtls() {
        Quote quote = quote();

        long sessionTtl = redis.getExpire("checkout:session:" + quote.ref()).block(WAIT).toMillis();
        long pointerTtl = redis.getExpire("checkout:active:" + quote.user()).block(WAIT).toMillis();

        assertThat(sessionTtl).isBetween(29 * 60_000L, 30 * 60_000L);
        assertThat(pointerTtl).isBetween(29 * 60_000L, 30 * 60_000L);
        assertThat(redis.opsForValue().get("checkout:active:" + quote.user()).block(WAIT)).isEqualTo(quote.ref());
        assertThat(redisKeys("checkout:idem:" + quote.user())).hasSize(1);
        assertThat(redis.getExpire(redisKeys("checkout:idem:" + quote.user()).get(0)).block(WAIT).toMillis())
                .isBetween(4 * 60_000L, 5 * 60_000L);
    }

    @Test
    @DisplayName("Claim moves PREVIEWED to SUBMITTING with the payload and resets the TTLs; a second claim finds SUBMITTING")
    void claimIsExclusive() {
        Quote quote = quote();

        assertThat(claim(quote, "attempt-a")).isInstanceOf(SessionStore.Claim.Claimed.class);
        SessionStore.Claim second = claim(quote, "attempt-b");

        assertThat(second).isInstanceOfSatisfying(SessionStore.Claim.Held.class, held -> {
            assertThat(held.state()).isEqualTo(SessionState.SUBMITTING);
            assertThat(held.attemptId()).isEqualTo("attempt-a");
        });
        assertThat(redis.opsForValue().get("checkout:submit-payload:" + quote.ref()).block(WAIT))
                .isEqualTo("payload-cipher");
        assertThat(redis.getExpire("checkout:submit-payload:" + quote.ref()).block(WAIT).toMillis())
                .isBetween(29 * 60_000L, 30 * 60_000L);
    }

    @Test
    @DisplayName("Eight simultaneous claims: exactly one wins")
    void simultaneousClaims() {
        Quote quote = quote();

        List<SessionStore.Claim> claims = reactor.core.publisher.Flux.range(0, 8)
                .flatMap(i -> store.claim(quote.ref(), quote.user(), "attempt-" + i, "payload", clock.millis()))
                .collectList().block(WAIT);

        assertThat(claims).filteredOn(claim -> claim instanceof SessionStore.Claim.Claimed).hasSize(1);
        assertThat(claims).filteredOn(claim -> claim instanceof SessionStore.Claim.Held).hasSize(7);
    }

    @Test
    @DisplayName("Claim refuses another owner and an expired quotation")
    void claimGuards() {
        Quote quote = quote();

        assertThat(store.claim(quote.ref(), newUser(), "a", "p", clock.millis()).block(WAIT))
                .isInstanceOf(SessionStore.Claim.NotOwner.class);
        assertThat(store.claim(quote.ref(), quote.user(), "a", "p", clock.millis() + 21 * 60_000).block(WAIT))
                .isInstanceOf(SessionStore.Claim.Expired.class);
        assertThat(store.claim("v1:missing", quote.user(), "a", "p", clock.millis()).block(WAIT))
                .isInstanceOf(SessionStore.Claim.Missing.class);
        assertThat(state(quote)).isEqualTo(SessionState.PREVIEWED);
    }

    @Test
    @DisplayName("Finalize: the first writer wins and the result carries the order id with the state")
    void finalizeFirstWriterWins() {
        Quote quote = quote();
        claim(quote, "attempt-a");

        SessionStore.Finalized first = store.finalizeTerminal(quote.ref(), quote.user(), SessionState.SUCCEEDED,
                "10001", null, clock.millis()).block(WAIT);
        SessionStore.Finalized second = store.finalizeTerminal(quote.ref(), quote.user(), SessionState.CONFLICT, null,
                "problem-cipher", clock.millis()).block(WAIT);

        assertThat(first).isInstanceOf(SessionStore.Finalized.Written.class);
        assertThat(second).isInstanceOfSatisfying(SessionStore.Finalized.Already.class,
                already -> assertThat(already.state()).isEqualTo(SessionState.SUCCEEDED));
        SessionStore.Result result = store.findResult(quote.token()).block(WAIT).orElseThrow();
        assertThat(result.state()).isEqualTo(SessionState.SUCCEEDED);
        assertThat(result.orderId()).isEqualTo("10001");
        assertThat(result.userId()).isEqualTo(quote.user());
        assertThat(redis.getExpire("checkout:result:" + quote.ref()).block(WAIT).toHours()).isGreaterThanOrEqualTo(23);
        assertThat(state(quote)).isEqualTo(SessionState.SUCCEEDED);
    }

    @Test
    @DisplayName("A failure result is kept for 30 minutes, not 24 hours")
    void failureResultTtl() {
        Quote quote = quote();
        claim(quote, "attempt-a");

        store.finalizeTerminal(quote.ref(), quote.user(), SessionState.FAILED, null, "problem", clock.millis())
                .block(WAIT);

        assertThat(redis.getExpire("checkout:result:" + quote.ref()).block(WAIT).toMinutes()).isBetween(29L, 30L);
    }

    @Test
    @DisplayName("A token that was never claimed cannot be finalized")
    void finalizeRequiresAClaim() {
        Quote quote = quote();

        SessionStore.Finalized result = store.finalizeTerminal(quote.ref(), quote.user(), SessionState.SUCCEEDED,
                "1", null, clock.millis()).block(WAIT);

        assertThat(result).isInstanceOfSatisfying(SessionStore.Finalized.Invalid.class,
                invalid -> assertThat(invalid.state()).isEqualTo(SessionState.PREVIEWED));
        assertThat(store.findResult(quote.token()).block(WAIT)).isEmpty();
    }

    @Test
    @DisplayName("A stale attempt can neither mark progress nor move the token to RETRYABLE")
    void staleAttemptIsFenced() {
        Quote quote = quote();
        claim(quote, "attempt-a");
        assertThat(store.toRetryable(quote.ref(), "attempt-a", clock.millis()).block(WAIT)).isTrue();

        SessionStore.Claim recovery = store.recoveryClaim(quote.ref(), quote.user(), "attempt-b", "attempt-a",
                clock.millis()).block(WAIT);
        assertThat(recovery).isInstanceOf(SessionStore.Claim.Claimed.class);

        assertThat(store.mark(quote.ref(), "attempt-a", clock.millis(), SessionStore.Mark.BOTH).block(WAIT)).isFalse();
        assertThat(store.toRetryable(quote.ref(), "attempt-a", clock.millis()).block(WAIT)).isFalse();
        assertThat(state(quote)).isEqualTo(SessionState.SUBMITTING);
        assertThat(store.mark(quote.ref(), "attempt-b", clock.millis(), SessionStore.Mark.BOTH).block(WAIT)).isTrue();
        var control = store.findControl(quote.token()).block(WAIT).orElseThrow();
        assertThat(control.revalidatedAtMs()).isNotNull();
        assertThat(control.lastOrderCallStartedAtMs()).isNotNull();
        assertThat(control.attemptId()).isEqualTo("attempt-b");
    }

    @Test
    @DisplayName("Recovery keeps the original payload and never returns the token to PREVIEWED")
    void recoveryKeepsThePayload() {
        Quote quote = quote();
        claim(quote, "attempt-a");
        store.toRetryable(quote.ref(), "attempt-a", clock.millis()).block(WAIT);

        store.recoveryClaim(quote.ref(), quote.user(), "attempt-b", "attempt-a", clock.millis()).block(WAIT);

        assertThat(redis.opsForValue().get("checkout:submit-payload:" + quote.ref()).block(WAIT))
                .isEqualTo("payload-cipher");
        assertThat(state(quote)).isEqualTo(SessionState.SUBMITTING);
        assertThat(store.claim(quote.ref(), quote.user(), "attempt-c", "other", clock.millis()).block(WAIT))
                .isInstanceOf(SessionStore.Claim.Held.class);
    }

    @Test
    @DisplayName("Recovery claim: SUBMITTING is only taken over after the owner timeout; concurrent claimers race on one winner")
    void recoveryClaimRaces() {
        Quote quote = quote();
        claim(quote, "attempt-a");

        assertThat(store.recoveryClaim(quote.ref(), quote.user(), "attempt-b", "attempt-a", clock.millis()).block(WAIT))
                .as("a live owner is never taken over").isInstanceOf(SessionStore.Claim.Held.class);

        long later = clock.millis() + 31_000;
        List<SessionStore.Claim> claims = reactor.core.publisher.Flux.range(0, 6)
                .flatMap(i -> store.recoveryClaim(quote.ref(), quote.user(), "recoverer-" + i, "attempt-a", later))
                .collectList().block(WAIT);

        assertThat(claims).filteredOn(claim -> claim instanceof SessionStore.Claim.Claimed).hasSize(1);
    }

    @Test
    @DisplayName("A rejected Preview writes nothing: no orphan session")
    void rejectedPreviewLeavesNoSession() {
        Quote quote = quote();
        claim(quote, "attempt-a");
        int sessions = redisKeys("checkout:session:").size();

        SessionStore.Created created = store.createPreview(quote.user(), SecureIds.uuid(), "fp", null,
                SecureIds.uuid(), "snapshot", "response", clock.millis(), clock.millis() + 1000, "v1").block(WAIT);

        assertThat(created).isInstanceOf(SessionStore.Created.InFlight.class);
        assertThat(redisKeys("checkout:session:")).hasSize(sessions);
        assertThat(redis.opsForValue().get("checkout:active:" + quote.user()).block(WAIT)).isEqualTo(quote.ref());

        // The in-flight record also hands back the payload so the caller can build status_url.
        assertThat(((SessionStore.Created.InFlight) created).payloadCipher()).isEqualTo("payload-cipher");
    }
}
