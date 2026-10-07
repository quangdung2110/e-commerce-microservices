package com.ecommerce.checkout.service;

import com.ecommerce.checkout.api.dto.PublicDtos;
import com.ecommerce.checkout.client.OrderClient;
import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.crypto.SecureIds;
import com.ecommerce.checkout.crypto.TokenHasher;
import com.ecommerce.checkout.domain.SessionState;
import com.ecommerce.checkout.domain.Snapshot;
import com.ecommerce.checkout.observability.CheckoutMetrics;
import com.ecommerce.checkout.problem.ApiException;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.ecommerce.checkout.resilience.CallResult;
import com.ecommerce.checkout.resilience.Dependency;
import com.ecommerce.checkout.resilience.DependencyGuard;
import com.ecommerce.checkout.resilience.Outcome;
import com.ecommerce.checkout.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.util.Optional;

/**
 * WF-02 Submit, WF-03 duplicate Submit and WF-04 recovery (architecture.md, sections 5 and 6).
 *
 * <p>A token is claimed atomically ({@code PREVIEWED -> SUBMITTING}) together with its immutable Order
 * payload. From the claim on, the remaining work is subscribed separately from the request, so a client
 * that disconnects cannot cancel the Order call: the result is always written back to Redis and the
 * client reads it through the status endpoint (CHK-INV-08). No lock is held across a network call:
 * Lua/CAS picks the owner and Order's idempotency ({@code Idempotency-Key = checkout_token}) prevents a
 * second order. There is no background worker; recovery only runs when a client asks.
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final SessionStore store;
    private final Codec codec;
    private final DependencyGuard guard;
    private final OrderClient orders;
    private final Revalidator revalidator;
    private final OrderPayloadFactory payloads;
    private final OrderOutcomeMapper outcomes;
    private final Failures failures;
    private final CheckoutProperties properties;
    private final CheckoutMetrics metrics;
    private final TokenHasher hasher;
    private final Clock clock;

    public SubmissionService(SessionStore store, Codec codec, DependencyGuard guard, OrderClient orders,
                             Revalidator revalidator, OrderPayloadFactory payloads, OrderOutcomeMapper outcomes,
                             Failures failures, CheckoutProperties properties, CheckoutMetrics metrics,
                             TokenHasher hasher, Clock clock) {
        this.store = store;
        this.codec = codec;
        this.guard = guard;
        this.orders = orders;
        this.revalidator = revalidator;
        this.payloads = payloads;
        this.outcomes = outcomes;
        this.failures = failures;
        this.properties = properties;
        this.metrics = metrics;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** What a Submit or status call returns when it is not a problem. */
    public sealed interface SubmissionOutcome permits Ready, Pending {
    }

    /**
     * PAYMENT_READY. {@code created} is true only for the call that wrote the result by itself (HTTP 201 on
     * Submit); a replay, or a result found at Order, is false.
     */
    public record Ready(boolean created, PublicDtos.CheckoutSubmissionReady body) implements SubmissionOutcome {
    }

    /** {@code PREPARING}: Order is working. {@code RESULT_UNKNOWN}: the outcome is not known yet; keep polling. */
    public record Pending(String state) implements SubmissionOutcome {
    }

    private static final Pending PREPARING = new Pending("PREPARING");
    private static final Pending RESULT_UNKNOWN = new Pending("RESULT_UNKNOWN");

    /** The attempt that owns a claimed token; {@code snapshot} is present only when revalidation is still due. */
    private record Attempt(String ref, String userId, String token, String attemptId, String payload,
                           Snapshot snapshot) {
    }

    private sealed interface Found permits Found.Live, Found.Finished, Found.Absent {
        record Live(SessionStore.Control control) implements Found {
        }

        record Finished(SessionStore.Result result) implements Found {
        }

        record Absent() implements Found {
        }
    }

    // ------------------------------------------------------------------ Submit

    public Mono<SubmissionOutcome> submit(String userId, String token, RequestContext context) {
        return lookup(userId, token).flatMap(found -> switch (found) {
            case Found.Absent absent -> Mono.<SubmissionOutcome>error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
            case Found.Finished finished -> replay(finished.result(), token, context);
            case Found.Live live -> submitLive(live.control(), userId, token, context);
        });
    }

    private Mono<SubmissionOutcome> submitLive(SessionStore.Control control, String userId, String token,
                                               RequestContext context) {
        return switch (control.state()) {
            case SUPERSEDED -> Mono.error(gone(ProblemCode.CHECKOUT_SUPERSEDED));
            case PREVIEWED -> clock.millis() >= control.expiresAtMs()
                    ? Mono.error(gone(ProblemCode.CHECKOUT_EXPIRED))
                    : claimAndRun(control, userId, token, context);
            case SUBMITTING, RETRYABLE -> inFlight(control, userId, token, context);
            case SUCCEEDED, CONFLICT, FAILED -> replayStored(userId, token, context);
        };
    }

    /** WF-02 steps 2 to 4: circuit check, atomic claim with the payload, then the detached work. */
    private Mono<SubmissionOutcome> claimAndRun(SessionStore.Control control, String userId, String token,
                                                RequestContext context) {
        if (guard.isOpen(Dependency.ORDER_POST)) {
            // Order is known to be down: refuse before claiming, so the token stays PREVIEWED.
            return Mono.error(failures.unavailable(ProblemCode.CHECKOUT_UNAVAILABLE,
                    guard.openRetryAfterSeconds(Dependency.ORDER_POST)));
        }
        String ref = control.ref();
        return store.readSnapshotCipher(ref)
                .switchIfEmpty(Mono.error(notFound(ProblemCode.CHECKOUT_NOT_FOUND)))
                .flatMap(cipher -> {
                    Snapshot snapshot = codec.decryptSnapshot(cipher);
                    String payload = payloads.build(token, snapshot);
                    String attemptId = SecureIds.uuid();
                    // The claim script and everything after it run detached: once the script has been sent
                    // the token may be SUBMITTING even if the client disconnects before the reply arrives,
                    // and an abandoned SUBMITTING token would block the user until the owner timeout.
                    return detached(store.claim(ref, userId, attemptId, codec.encryptPayload(payload), clock.millis())
                            .flatMap(claim -> switch (claim) {
                                case SessionStore.Claim.Claimed claimed -> {
                                    metrics.transition("PREVIEWED", "SUBMITTING");
                                    log.info("submission {} claimed", hasher.fingerprint(token));
                                    yield run(new Attempt(ref, userId, token, attemptId, payload, snapshot), context);
                                }
                                case SessionStore.Claim.Missing missing ->
                                        Mono.<SubmissionOutcome>error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
                                case SessionStore.Claim.NotOwner notOwner ->
                                        Mono.<SubmissionOutcome>error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
                                case SessionStore.Claim.Expired expired ->
                                        Mono.<SubmissionOutcome>error(gone(ProblemCode.CHECKOUT_EXPIRED));
                                case SessionStore.Claim.Held held -> lostClaim(held, ref, userId, token, context);
                            }));
                });
    }

    /** Another request changed the token between our read and the claim: answer from the state it left. */
    private Mono<SubmissionOutcome> lostClaim(SessionStore.Claim.Held held, String ref, String userId, String token,
                                              RequestContext context) {
        SessionStore.Control control = new SessionStore.Control(ref, userId, held.state(), 0, held.claimedAtMs(),
                held.attemptStartedAtMs(), held.attemptId(), held.revalidatedAtMs(),
                held.lastOrderCallStartedAtMs(), null);
        return submitLive(control, userId, token, context);
    }

    // ------------------------------------------------------------------ the claimed attempt

    /** Revalidate, then call Order with the stored payload and key, then write the result. */
    private Mono<SubmissionOutcome> run(Attempt attempt, RequestContext context) {
        if (attempt.snapshot() == null) {
            return callOrder(attempt, context);
        }
        return revalidator.revalidate(attempt.snapshot(), context).flatMap(result -> switch (result) {
            case Revalidator.Changed changed ->
                    finalizeProblem(attempt, SessionState.CONFLICT, changed.problem(), context);
            case Revalidator.Failed failed ->
                    toRetryable(attempt).then(Mono.<SubmissionOutcome>error(failures.unavailable(failed.failure())));
            case Revalidator.Unchanged unchanged -> callOrder(attempt, context);
        });
    }

    /**
     * WF-02 step 6: record {@code revalidated_at} and {@code last_order_call_started_at} before the call.
     * With less than the minimum time left Order is not called: the token becomes RETRYABLE and the client
     * polls, which later resends exactly this payload.
     */
    private Mono<SubmissionOutcome> callOrder(Attempt attempt, RequestContext context) {
        boolean enoughTime = context.deadline().remaining().compareTo(properties.orderMinRemaining()) >= 0;
        SessionStore.Mark mark = enoughTime ? SessionStore.Mark.BOTH : SessionStore.Mark.REVALIDATED;
        return store.mark(attempt.ref(), attempt.attemptId(), clock.millis(), mark).flatMap(owned -> {
            if (!owned) {
                // A newer attempt owns the token; whatever it decides is the answer, not ours.
                return Mono.<SubmissionOutcome>just(PREPARING);
            }
            if (!enoughTime) {
                return toRetryable(attempt).thenReturn(RESULT_UNKNOWN);
            }
            return postToOrder(attempt, context);
        });
    }

    private Mono<SubmissionOutcome> postToOrder(Attempt attempt, RequestContext context) {
        return guard.call(Dependency.ORDER_POST, context.deadline(),
                        () -> orders.post(attempt.token(), attempt.payload(), context),
                        response -> response instanceof OrderClient.Rejected)
                .flatMap(result -> {
                    if (result.outcome() == Outcome.SUCCESS) {
                        return finalizeSuccess(attempt, ((OrderClient.Created) result.value()).ready(), true);
                    }
                    if (result.outcome() == Outcome.BUSINESS_REJECT) {
                        OrderOutcomeMapper.Terminal terminal =
                                outcomes.fromRejection((OrderClient.Rejected) result.value());
                        return finalizeProblem(attempt, terminal.state(), terminal.problem(), context);
                    }
                    return unresolved(attempt, result);
                });
    }

    /**
     * Order's answer did not settle the token. {@code 400/401/403/429} mean Order did not accept the request:
     * RETRYABLE and {@code 503}. Order's own {@code SUBMISSION_IN_PROGRESS} means it accepted it and is still
     * preparing: RETRYABLE and {@code PREPARING}. Anything else (timeout, {@code 503}, connection error,
     * unreadable answer) may or may not have reached Order: RETRYABLE and {@code RESULT_UNKNOWN}.
     */
    private Mono<SubmissionOutcome> unresolved(Attempt attempt, CallResult<?> result) {
        if (result.outcome() == Outcome.CLIENT_ERROR) {
            return toRetryable(attempt).then(Mono.<SubmissionOutcome>error(failures.unavailable(result)));
        }
        if (result.outcome() == Outcome.SERVER_ERROR && "SUBMISSION_IN_PROGRESS".equals(result.detail())) {
            return toRetryable(attempt).thenReturn(PREPARING);
        }
        log.warn("Order result unknown after {}; the token is RETRYABLE", result.outcome().tag());
        return toRetryable(attempt).thenReturn(RESULT_UNKNOWN);
    }

    private Mono<SubmissionOutcome> finalizeSuccess(Attempt attempt, OrderClient.Ready ready, boolean owner) {
        return store.finalizeTerminal(attempt.ref(), attempt.userId(), SessionState.SUCCEEDED, ready.orderId(), null,
                        clock.millis())
                .map(finalized -> {
                    boolean wrote = finalized instanceof SessionStore.Finalized.Written;
                    if (wrote) {
                        metrics.transition("SUBMITTING", "SUCCEEDED");
                    }
                    return (SubmissionOutcome) new Ready(owner && wrote,
                            readyBody(attempt.token(), ready.orderId(), ready.payment()));
                });
    }

    /** Writes CONFLICT or FAILED and answers with the problem; if another writer finished first, replays theirs. */
    private Mono<SubmissionOutcome> finalizeProblem(Attempt attempt, SessionState terminal, Problem problem,
                                                    RequestContext context) {
        return finalizeProblem(attempt.ref(), attempt.userId(), attempt.token(), terminal, problem, context);
    }

    private Mono<SubmissionOutcome> finalizeProblem(String ref, String userId, String token, SessionState terminal,
                                                    Problem problem, RequestContext context) {
        return store.finalizeTerminal(ref, userId, terminal, null, codec.encryptProblem(problem), clock.millis())
                .flatMap(finalized -> {
                    if (finalized instanceof SessionStore.Finalized.Already) {
                        return replayStored(userId, token, context);
                    }
                    metrics.transition("SUBMITTING", terminal.name());
                    return Mono.<SubmissionOutcome>error(new ApiException(problem));
                });
    }

    private Mono<Boolean> toRetryable(Attempt attempt) {
        return store.toRetryable(attempt.ref(), attempt.attemptId(), clock.millis())
                .doOnNext(changed -> {
                    if (changed) {
                        metrics.transition("SUBMITTING", "RETRYABLE");
                    }
                });
    }

    // ------------------------------------------------------------------ status and recovery

    public Mono<SubmissionOutcome> status(String userId, String token, RequestContext context) {
        return lookup(userId, token).flatMap(found -> switch (found) {
            case Found.Absent absent -> Mono.<SubmissionOutcome>error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
            case Found.Finished finished -> replay(finished.result(), token, context);
            case Found.Live live -> statusLive(live.control(), userId, token, context);
        });
    }

    private Mono<SubmissionOutcome> statusLive(SessionStore.Control control, String userId, String token,
                                               RequestContext context) {
        return switch (control.state()) {
            // The token was never submitted: there is nothing to recover.
            case PREVIEWED, SUPERSEDED -> Mono.error(notFound(ProblemCode.SUBMISSION_NOT_FOUND));
            case SUBMITTING, RETRYABLE -> inFlight(control, userId, token, context);
            case SUCCEEDED, CONFLICT, FAILED -> replayStored(userId, token, context);
        };
    }

    /** WF-03: SUBMITTING inside the owner timeout is somebody's live attempt; otherwise run WF-04. */
    private Mono<SubmissionOutcome> inFlight(SessionStore.Control control, String userId, String token,
                                             RequestContext context) {
        long ownerTimeoutMs = properties.lifecycle().submitOwnerTimeout().toMillis();
        if (control.state() == SessionState.SUBMITTING
                && clock.millis() - control.attemptStartMs() < ownerTimeoutMs) {
            metrics.recovery("still_preparing");
            return Mono.just(PREPARING);
        }
        return recover(control, userId, token, context);
    }

    /** WF-04: ask Order for the durable truth before deciding anything. */
    private Mono<SubmissionOutcome> recover(SessionStore.Control control, String userId, String token,
                                            RequestContext context) {
        return guard.call(Dependency.ORDER_GET, context.deadline(), () -> orders.get(token, context), Optional::isEmpty)
                .flatMap(result -> {
                    if (!result.isUsable()) {
                        metrics.recovery("unknown");
                        return Mono.<SubmissionOutcome>just(RESULT_UNKNOWN);
                    }
                    Optional<OrderClient.Status> status = result.value();
                    if (status.isEmpty()) {
                        return absentAtOrder(control, userId, token, context);
                    }
                    return resolveFromOrder(control, status.get(), userId, token, context);
                });
    }

    private Mono<SubmissionOutcome> resolveFromOrder(SessionStore.Control control, OrderClient.Status status,
                                                     String userId, String token, RequestContext context) {
        return switch (status.state()) {
            case "PAYMENT_READY" -> store.finalizeTerminal(control.ref(), userId, SessionState.SUCCEEDED,
                            status.orderId(), null, clock.millis())
                    .map(finalized -> {
                        metrics.recovery("resolved_success");
                        if (finalized instanceof SessionStore.Finalized.Written) {
                            metrics.transition(control.state().name(), "SUCCEEDED");
                        }
                        return (SubmissionOutcome) new Ready(false,
                                readyBody(token, status.orderId(), status.payment()));
                    });
            case "PREPARING" -> {
                metrics.recovery("still_preparing");
                yield Mono.just(PREPARING);
            }
            default -> {
                OrderOutcomeMapper.Terminal terminal = outcomes.fromFailure(status.state(), status.failure());
                metrics.recovery(terminal.state() == SessionState.CONFLICT ? "resolved_conflict" : "resolved_failed");
                yield finalizeProblem(control.ref(), userId, token, terminal.state(), terminal.problem(), context);
            }
        };
    }

    /**
     * Order has no submission for the token. If a POST of ours may still be in flight the answer is unknown;
     * inside the retry window the identical payload and key are sent again; after it the token ends as
     * {@code SUBMISSION_NOT_ACCEPTED}, because Order confirmed it has nothing and no call can still arrive.
     */
    private Mono<SubmissionOutcome> absentAtOrder(SessionStore.Control control, String userId, String token,
                                                  RequestContext context) {
        long now = clock.millis();
        Long lastCall = control.lastOrderCallStartedAtMs();
        if (lastCall != null && now - lastCall < properties.lifecycle().orderCallInFlightWindow().toMillis()) {
            metrics.recovery("unknown");
            return Mono.just(RESULT_UNKNOWN);
        }
        long claimedAt = control.claimedAtMs() == null ? 0 : control.claimedAtMs();
        if (now - claimedAt >= properties.lifecycle().submitRetryWindow().toMillis()) {
            metrics.recovery("not_accepted");
            return finalizeProblem(control.ref(), userId, token, SessionState.FAILED,
                    Problem.of(ProblemCode.SUBMISSION_NOT_ACCEPTED,
                            "The order was never received; start a new preview"), context);
        }
        return repost(control, userId, token, context);
    }

    /** CAS-claim the recovery, revalidate if that never completed, then POST the same payload again. */
    private Mono<SubmissionOutcome> repost(SessionStore.Control control, String userId, String token,
                                           RequestContext context) {
        boolean revalidated = control.revalidatedAtMs() != null;
        String newAttempt = SecureIds.uuid();
        return store.readPayloadCipher(control.ref())
                .flatMap(payloadCipher -> {
                    if (payloadCipher.isEmpty()) {
                        return Mono.<SubmissionOutcome>error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
                    }
                    Mono<Optional<Snapshot>> snapshot = revalidated
                            ? Mono.just(Optional.<Snapshot>empty())
                            : store.readSnapshotCipher(control.ref()).map(codec::decryptSnapshot).map(Optional::of);
                    // Detached from the claim script on, for the same reason as the first claim.
                    return snapshot.flatMap(optionalSnapshot -> detached(store
                            .recoveryClaim(control.ref(), userId, newAttempt, control.attemptId(), clock.millis())
                            .flatMap(claim -> switch (claim) {
                                case SessionStore.Claim.Claimed claimed -> {
                                    metrics.recovery("reposted");
                                    metrics.transition(control.state().name(), "SUBMITTING");
                                    String payload = codec.decryptPayload(payloadCipher.get());
                                    yield run(new Attempt(control.ref(), userId, token, newAttempt, payload,
                                            optionalSnapshot.orElse(null)), context);
                                }
                                case SessionStore.Claim.Held held -> {
                                    // Another recoverer won the race; its attempt is the live one.
                                    metrics.recovery("still_preparing");
                                    yield Mono.<SubmissionOutcome>just(PREPARING);
                                }
                                default -> Mono.<SubmissionOutcome>error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
                            })));
                })
                .switchIfEmpty(Mono.error(notFound(ProblemCode.CHECKOUT_NOT_FOUND)));
    }

    // ------------------------------------------------------------------ terminal replay

    private Mono<SubmissionOutcome> replayStored(String userId, String token, RequestContext context) {
        return store.findResult(token).flatMap(result -> {
            if (result.isEmpty() || !result.get().userId().equals(userId)) {
                return Mono.<SubmissionOutcome>error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
            }
            return replay(result.get(), token, context);
        });
    }

    /**
     * SUCCEEDED asks Order for fresh Payment data (never a stored secret, ADR-010); CONFLICT and FAILED
     * replay the stored problem.
     */
    private Mono<SubmissionOutcome> replay(SessionStore.Result result, String token, RequestContext context) {
        if (result.state() != SessionState.SUCCEEDED) {
            if (result.problemCipher() == null) {
                return Mono.error(notFound(ProblemCode.CHECKOUT_NOT_FOUND));
            }
            return Mono.error(new ApiException(codec.decryptProblem(result.problemCipher())));
        }
        return guard.call(Dependency.ORDER_GET, context.deadline(), () -> orders.get(token, context), Optional::isEmpty)
                .map(call -> {
                    if (!call.isUsable() || call.value().isEmpty()
                            || !"PAYMENT_READY".equals(call.value().get().state())) {
                        // Order cannot answer right now; there is no stored secret to fall back on.
                        return (SubmissionOutcome) RESULT_UNKNOWN;
                    }
                    OrderClient.Status status = call.value().get();
                    return new Ready(false, readyBody(token, status.orderId(), status.payment()));
                });
    }

    // ------------------------------------------------------------------ plumbing

    private Mono<Found> lookup(String userId, String token) {
        return store.findControl(token).flatMap(control -> {
            if (control.isPresent()) {
                return Mono.<Found>just(control.get().userId().equals(userId)
                        ? new Found.Live(control.get())
                        : new Found.Absent());
            }
            return store.findResult(token).map(result -> result
                    .filter(r -> r.userId().equals(userId))
                    .<Found>map(Found.Finished::new)
                    .orElseGet(Found.Absent::new));
        });
    }

    /**
     * Runs {@code work} without tying it to the request subscription (ADR-013): a client that disconnects
     * cancels the returned Mono, never the work. Errors reach the caller if it is still there and are logged
     * otherwise. The Reactor context is copied so tracing continues.
     */
    private Mono<SubmissionOutcome> detached(Mono<SubmissionOutcome> work) {
        return Mono.deferContextual(reactorContext -> {
            Sinks.One<SubmissionOutcome> sink = Sinks.one();
            work.contextWrite(context -> context.putAll(reactorContext))
                    .subscribe(sink::tryEmitValue, error -> {
                        if (!(error instanceof ApiException)) {
                            log.error("detached submission work failed", error);
                        }
                        sink.tryEmitError(error);
                    });
            return sink.asMono();
        });
    }

    private PublicDtos.CheckoutSubmissionReady readyBody(String token, String orderId, OrderClient.Payment payment) {
        return new PublicDtos.CheckoutSubmissionReady(token, orderId, "PENDING_PAYMENT",
                new PublicDtos.PaymentClientData(payment.paymentAttemptNumber(), payment.paymentTransactionId(),
                        payment.usable(), payment.clientSecret(), payment.publishableKey(), payment.expiresAt()));
    }

    private static ApiException notFound(ProblemCode code) {
        return new ApiException(code, code == ProblemCode.SUBMISSION_NOT_FOUND
                ? "This checkout was never submitted"
                : "No checkout with this token exists for the current user");
    }

    private static ApiException gone(ProblemCode code) {
        return new ApiException(code, code == ProblemCode.CHECKOUT_EXPIRED
                ? "The quotation expired; start a new preview"
                : "A newer quotation replaced this one; start a new preview");
    }
}
