package com.ecommerce.checkout.api;

import com.ecommerce.checkout.api.dto.PublicDtos;
import com.ecommerce.checkout.client.RequestContext;
import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.problem.ApiException;
import com.ecommerce.checkout.problem.ProblemCode;
import com.ecommerce.checkout.service.PreviewService;
import com.ecommerce.checkout.service.SubmissionService;
import com.ecommerce.checkout.service.VoucherOptionsService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The four buyer endpoints of {@code contracts/http/checkout/v1/openapi.yaml}. Authentication is the
 * Gateway's: {@code X-User-Id} is trusted and the client never sends a user, tier, role, price or
 * quantity. Everything that can go wrong is an {@link ApiException}, rendered by
 * {@link ApiExceptionHandler}.
 */
@RestController
public class CheckoutController {

    private static final String STATUS_PATH = "/api/v1/checkout/submissions/";

    private final PreviewService preview;
    private final VoucherOptionsService voucherOptions;
    private final SubmissionService submissions;
    private final RequestParser parser;
    private final CheckoutProperties properties;

    public CheckoutController(PreviewService preview, VoucherOptionsService voucherOptions,
                              SubmissionService submissions, RequestParser parser, CheckoutProperties properties) {
        this.preview = preview;
        this.voucherOptions = voucherOptions;
        this.submissions = submissions;
        this.parser = parser;
        this.properties = properties;
    }

    @PostMapping(path = "/api/v1/checkout/preview")
    public Mono<ResponseEntity<String>> preview(
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody(required = false) Mono<byte[]> body,
            ServerWebExchange exchange) {
        String userId = user(userHeader);
        String idempotencyKey = parser.idempotencyKey(idempotencyHeader);
        RequestContext context = context(exchange).withCausationId(idempotencyKey);
        return body.defaultIfEmpty(new byte[0]).flatMap(bytes -> {
            RequestParser.PreviewBody request = parser.preview(bytes);
            return preview.preview(new PreviewService.Command(userId, idempotencyKey, request.previousCheckoutToken(),
                    request.deliveryAddressId(), request.promotionId()), context);
        }).map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json));
    }

    @GetMapping(path = "/api/v1/checkout/previews/{checkoutToken}/voucher-options",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PublicDtos.VoucherOptions>> voucherOptions(
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @PathVariable("checkoutToken") String tokenPath,
            ServerWebExchange exchange) {
        String userId = user(userHeader);
        String token = pathToken(tokenPath);
        return voucherOptions.list(userId, token, context(exchange))
                .map(options -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(options));
    }

    @PostMapping(path = "/api/v1/checkout/submit")
    public Mono<ResponseEntity<Object>> submit(
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestBody(required = false) Mono<byte[]> body,
            ServerWebExchange exchange) {
        String userId = user(userHeader);
        RequestContext context = context(exchange);
        return body.defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    String token = parser.submit(bytes).checkoutToken();
                    return submissions.submit(userId, token, context).map(outcome -> respond(outcome, true, token));
                });
    }

    @GetMapping(path = "/api/v1/checkout/submissions/{checkoutToken}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> status(
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @PathVariable("checkoutToken") String tokenPath,
            ServerWebExchange exchange) {
        String userId = user(userHeader);
        String token = pathToken(tokenPath);
        return submissions.status(userId, token, context(exchange)).map(outcome -> respond(outcome, false, token));
    }

    /**
     * Submit answers 201 for the call that created the result and 200 for a replay; the status endpoint
     * only ever answers 200. Every pending answer is 202 with {@code Location} and {@code Retry-After}.
     */
    private ResponseEntity<Object> respond(SubmissionService.SubmissionOutcome outcome, boolean submit, String token) {
        return switch (outcome) {
            case SubmissionService.Ready ready -> {
                HttpStatus status = submit && ready.created() ? HttpStatus.CREATED : HttpStatus.OK;
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cacheControl(CacheControl.noStore().cachePrivate());
                if (submit) {
                    builder.header(HttpHeaders.LOCATION, STATUS_PATH + token);
                }
                yield builder.body(ready.body());
            }
            case SubmissionService.Pending pending -> {
                int retryAfter = "PREPARING".equals(pending.state())
                        ? properties.retryAfter().preparingSeconds()
                        : properties.retryAfter().resultUnknownSeconds();
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .cacheControl(CacheControl.noStore().cachePrivate())
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.min(60, retryAfter)));
                if (submit) {
                    builder.header(HttpHeaders.LOCATION, STATUS_PATH + token);
                }
                yield builder.body(new PublicDtos.CheckoutSubmissionPending(token, pending.state(),
                        STATUS_PATH + token));
            }
        };
    }

    // ------------------------------------------------------------------ helpers


    private String user(String header) {
        String userId = RequestParser.uuidOrNull(header == null ? null : header.trim());
        if (userId == null) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "Missing or untrusted Gateway identity");
        }
        return userId;
    }

    /** A malformed token cannot exist for any user, so it is a plain not found, never a 400. */
    private String pathToken(String path) {
        String token = RequestParser.uuidOrNull(path);
        if (token == null) {
            throw new ApiException(ProblemCode.CHECKOUT_NOT_FOUND, "No checkout with this token exists");
        }
        return token;
    }

    private static RequestContext context(ServerWebExchange exchange) {
        return exchange.getRequiredAttribute(RequestContextFilter.CONTEXT_ATTRIBUTE);
    }
}
