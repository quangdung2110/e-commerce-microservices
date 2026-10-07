package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.CanonicalCommand;
import com.platform.paymentservice.application.IdempotencyAction;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.StoredHttpResponse;
import com.platform.paymentservice.application.port.out.WebhookProcessingPort;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class WebhookReplayService {
    private final WebhookProcessingPort inbox;
    private final IdempotencyExecutor idempotency;
    private final ClockPort clock;
    private final Duration leaseTtl;
    private final Duration retention;

    public WebhookReplayService(WebhookProcessingPort inbox, IdempotencyExecutor idempotency,
                                ClockPort clock, Duration leaseTtl, Duration retention) {
        this.inbox = Objects.requireNonNull(inbox);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.clock = Objects.requireNonNull(clock);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.retention = positive(retention, "retention");
    }

    public WebhookReplayResult replay(WebhookReplayCommand command, String owner) {
        var canonical = new CanonicalCommand(IdempotencyAction.WEBHOOK_REPLAY,
                Map.of("stripeEventId", command.stripeEventId()),
                Map.of("cause_ref", command.causeRef()));
        var result = idempotency.execute(command.idempotencyKey(), IdempotencyAction.WEBHOOK_REPLAY,
                canonical, owner, clock.now(), leaseTtl, clock.now().plus(retention),
                () -> execute(command));
        return switch (result.outcome()) {
            case IN_PROGRESS -> result(WebhookReplayResult.Outcome.REQUEST_IN_PROGRESS, 409);
            case KEY_REUSED -> result(WebhookReplayResult.Outcome.IDEMPOTENCY_KEY_REUSED, 422);
            case FENCED -> result(WebhookReplayResult.Outcome.FENCED, 409);
            case REPLAYED -> fromStatus(result.response().statusCode());
            case EXECUTED -> fromStatus(result.response().statusCode());
        };
    }

    private StoredHttpResponse execute(WebhookReplayCommand command) {
        var result = inbox.scheduleReplay(command.stripeEventId(), command.actor(),
                command.approver(), command.causeRef(), clock.now());
        return switch (result) {
            case SCHEDULED -> response(command.stripeEventId(), 202, "SCHEDULED");
            case MISSING -> response(command.stripeEventId(), 404, "MISSING");
            case PAYLOAD_SHREDDED -> response(command.stripeEventId(), 410, "PAYLOAD_SHREDDED");
            case BUSY -> response(command.stripeEventId(), 409, "BUSY");
        };
    }

    private static StoredHttpResponse response(String eventId, int status, String outcome) {
        return new StoredHttpResponse(eventId, status,
                "{\"stripe_event_id\":\"" + eventId + "\",\"outcome\":\"" + outcome + "\"}");
    }

    private static WebhookReplayResult fromStatus(int status) {
        return switch (status) {
            case 202 -> result(WebhookReplayResult.Outcome.SCHEDULED, status);
            case 404 -> result(WebhookReplayResult.Outcome.MISSING, status);
            case 409 -> result(WebhookReplayResult.Outcome.BUSY, status);
            case 410 -> result(WebhookReplayResult.Outcome.PAYLOAD_SHREDDED, status);
            default -> throw new IllegalStateException("unexpected replay status " + status);
        };
    }

    private static WebhookReplayResult result(WebhookReplayResult.Outcome outcome, int status) {
        return new WebhookReplayResult(outcome, status);
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
