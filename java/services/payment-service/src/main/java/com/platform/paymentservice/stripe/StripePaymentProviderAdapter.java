package com.platform.paymentservice.stripe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationType;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeSearchResult;
import com.stripe.model.StripeCollection;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class StripePaymentProviderAdapter implements PaymentProviderPort {
    private static final List<String> REQUIRED_METADATA = List.of(
            "payment_transaction_id", "order_id", "payment_attempt_number", "user_id");

    private final StripeGateway gateway;
    private final ObjectMapper json;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxNetworkRetries;
    private final List<String> paymentMethodTypes;

    StripePaymentProviderAdapter(StripeGateway gateway, ObjectMapper json,
                                 Duration connectTimeout, Duration readTimeout,
                                 int maxNetworkRetries) {
        this(gateway, json, connectTimeout, readTimeout, maxNetworkRetries, List.of("card"));
    }

    StripePaymentProviderAdapter(StripeGateway gateway, ObjectMapper json,
                                 Duration connectTimeout, Duration readTimeout,
                                 int maxNetworkRetries, List<String> paymentMethodTypes) {
        if (paymentMethodTypes == null || paymentMethodTypes.isEmpty())
            throw new IllegalArgumentException("paymentMethodTypes must not be empty");
        this.paymentMethodTypes = List.copyOf(paymentMethodTypes);
        this.gateway = Objects.requireNonNull(gateway);
        this.json = Objects.requireNonNull(json);
        this.connectTimeoutMillis = exactMillis(connectTimeout, "connectTimeout");
        this.readTimeoutMillis = exactMillis(readTimeout, "readTimeout");
        if (maxNetworkRetries < 0) throw new IllegalArgumentException("maxNetworkRetries must be non-negative");
        this.maxNetworkRetries = maxNetworkRetries;
    }

    @Override
    public ProviderOperationResult dispatch(ProviderOperation operation) {
        if (operation.type() == ProviderOperationType.CANCEL) return cancel(operation);
        if (operation.type() == ProviderOperationType.REFUND) return refund(operation);
        if (operation.type() != ProviderOperationType.CREATE)
            throw new IllegalArgumentException("Stripe adapter cannot dispatch " + operation.type());
        CreateSpec spec = parseCreateSpec(operation);
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(spec.amountMinor())
                .setCurrency("vnd")
                .setConfirmationMethod(PaymentIntentCreateParams.ConfirmationMethod.AUTOMATIC)
                // ADR-022 / RULE-PAY-025: card only; other methods have refund failure paths we did not design.
                .addAllPaymentMethodType(paymentMethodTypes)
                .putAllMetadata(spec.metadata());
        try {
            PaymentIntent intent = gateway.createPaymentIntent(params.build(), requestOptions(operation.providerIdempotencyKey()));
            return confirmed(intent);
        } catch (StripeException exception) {
            return mapException(exception);
        }
    }

    @Override
    public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
        if (operation.type() == ProviderOperationType.CANCEL) return retrieveCancel(operation);
        if (operation.type() == ProviderOperationType.REFUND) return searchRefund(operation);
        if (operation.type() != ProviderOperationType.CREATE)
            throw new IllegalArgumentException("Stripe adapter cannot search " + operation.type());
        CreateSpec spec = parseCreateSpec(operation);
        String transactionId = spec.metadata().get("payment_transaction_id");
        PaymentIntentSearchParams params = PaymentIntentSearchParams.builder()
                .setQuery("metadata['payment_transaction_id']:'" + transactionId + "'")
                .setLimit(10L)
                .build();
        try {
            StripeSearchResult<PaymentIntent> result = gateway.searchPaymentIntents(params, requestOptions(null));
            List<PaymentIntent> matching = result.getData().stream()
                    .filter(intent -> matches(intent, spec))
                    .toList();
            if (matching.size() != 1) {
                return new ProviderOperationResult.ReviewRequired(requestId(result), write(Map.of(
                        "error_code", matching.isEmpty() ? "NO_PROVIDER_MATCH" : "MULTIPLE_PROVIDER_MATCHES",
                        "candidate_count", matching.size())));
            }
            return confirmed(matching.getFirst());
        } catch (StripeException exception) {
            return mapException(exception);
        }
    }

    private ProviderOperationResult refund(ProviderOperation operation) {
        RefundSpec spec = parseRefundSpec(operation);
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(spec.providerTransactionId())
                .setAmount(spec.amountMinor())
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .putAllMetadata(spec.metadata())
                .build();
        try {
            return confirmed(gateway.createRefund(params,
                    requestOptions(operation.providerIdempotencyKey())));
        } catch (StripeException exception) {
            return mapException(exception);
        }
    }

    private ProviderOperationResult searchRefund(ProviderOperation operation) {
        RefundSpec spec = parseRefundSpec(operation);
        RefundListParams params = RefundListParams.builder()
                .setPaymentIntent(spec.providerTransactionId()).setLimit(100L).build();
        try {
            StripeCollection<Refund> result = gateway.listRefunds(params, requestOptions(null));
            List<Refund> matching = result.getData().stream()
                    .filter(refund -> matches(refund, spec)).toList();
            if (matching.size() != 1) {
                return new ProviderOperationResult.ReviewRequired(requestId(result), write(Map.of(
                        "error_code", matching.isEmpty() ? "NO_PROVIDER_MATCH" : "MULTIPLE_PROVIDER_MATCHES",
                        "candidate_count", matching.size())));
            }
            return confirmed(matching.getFirst());
        } catch (StripeException exception) {
            return mapException(exception);
        }
    }

    private ProviderOperationResult cancel(ProviderOperation operation) {
        CancelSpec spec = parseCancelSpec(operation);
        PaymentIntentCancelParams params = PaymentIntentCancelParams.builder()
                .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                .build();
        try {
            return cancelFact(gateway.cancelPaymentIntent(
                    spec.providerTransactionId(), params,
                    requestOptions(operation.providerIdempotencyKey())));
        } catch (StripeException cancelFailure) {
            try {
                return cancelFact(gateway.retrievePaymentIntent(
                        spec.providerTransactionId(), requestOptions(null)));
            } catch (StripeException retrieveFailure) {
                return new ProviderOperationResult.Unknown(
                        cancelFailure.getRequestId(), sanitizedException(cancelFailure));
            }
        }
    }

    private ProviderOperationResult retrieveCancel(ProviderOperation operation) {
        CancelSpec spec = parseCancelSpec(operation);
        try {
            return cancelFact(gateway.retrievePaymentIntent(
                    spec.providerTransactionId(), requestOptions(null)));
        } catch (StripeException exception) {
            return new ProviderOperationResult.Unknown(
                    exception.getRequestId(), sanitizedException(exception));
        }
    }

    private ProviderOperationResult cancelFact(PaymentIntent intent) {
        Map<String, Object> durable = durableIntent(intent);
        String requestId = intent.getLastResponse() == null ? null : intent.getLastResponse().requestId();
        if ("canceled".equals(intent.getStatus()) || "succeeded".equals(intent.getStatus())) {
            return new ProviderOperationResult.Confirmed(
                    intent.getId(), requestId, write(durable), null,
                    intent.getStatus(), intent.getLatestCharge(),
                    intent.getAmount(), intent.getCurrency() == null ? null : intent.getCurrency().toUpperCase());
        }
        return new ProviderOperationResult.Unknown(requestId, write(durable));
    }

    private ProviderOperationResult.Confirmed confirmed(PaymentIntent intent) {
        String requestId = intent.getLastResponse() == null ? null : intent.getLastResponse().requestId();
        Map<String, Object> durable = durableIntent(intent);
        char[] clientSecret = intent.getClientSecret() == null ? null : intent.getClientSecret().toCharArray();
        return new ProviderOperationResult.Confirmed(intent.getId(), requestId, write(durable), clientSecret);
    }

    private ProviderOperationResult.Confirmed confirmed(Refund refund) {
        String requestId = refund.getLastResponse() == null ? null : refund.getLastResponse().requestId();
        Map<String, Object> durable = new LinkedHashMap<>();
        durable.put("id", refund.getId());
        durable.put("status", refund.getStatus());
        if (refund.getAmount() != null) durable.put("amount_minor", refund.getAmount());
        if (refund.getCurrency() != null) durable.put("currency", refund.getCurrency());
        if (refund.getPaymentIntent() != null)
            durable.put("provider_transaction_id", refund.getPaymentIntent());
        if (refund.getCharge() != null) durable.put("provider_charge_id", refund.getCharge());
        if (refund.getFailureReason() != null)
            durable.put("failure_reason", refund.getFailureReason());
        return new ProviderOperationResult.Confirmed(refund.getId(), requestId, write(durable),
                null, refund.getStatus(), refund.getCharge(), refund.getAmount(),
                refund.getCurrency() == null ? null : refund.getCurrency().toUpperCase());
    }

    private ProviderOperationResult mapException(StripeException exception) {
        String body = sanitizedException(exception);
        Integer statusCode = exception.getStatusCode();
        if (statusCode != null && statusCode >= 400 && statusCode < 500) {
            return new ProviderOperationResult.Rejected(exception.getRequestId(), body);
        }
        return new ProviderOperationResult.Unknown(exception.getRequestId(), body);
    }

    private String sanitizedException(StripeException exception) {
        String code = exception.getCode() == null
                ? exception.getClass().getSimpleName().toUpperCase()
                : exception.getCode();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error_code", code);
        if (exception.getUserMessage() != null) result.put("error_message", exception.getUserMessage());
        Integer statusCode = exception.getStatusCode();
        if (statusCode != null) result.put("http_status", statusCode);
        return write(result);
    }

    private CreateSpec parseCreateSpec(ProviderOperation operation) {
        try {
            JsonNode root = json.readTree(operation.requestFingerprintJson());
            long amountMinor = root.path("amount_minor").asLong(-1);
            String currency = root.path("currency").asText();
            if (amountMinor <= 0 || !"VND".equals(currency)) {
                throw new IllegalArgumentException("CREATE fingerprint must contain positive VND amount");
            }
            JsonNode metadataNode = root.path("metadata");
            Map<String, String> metadata = new LinkedHashMap<>();
            for (String key : REQUIRED_METADATA) {
                String value = metadataNode.path(key).asText(null);
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("CREATE fingerprint missing metadata " + key);
                }
                metadata.put(key, value);
            }
            if (!operation.paymentTransactionId().equals(metadata.get("payment_transaction_id"))) {
                throw new IllegalArgumentException("operation payment id does not match provider metadata");
            }
            return new CreateSpec(amountMinor, metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid CREATE request fingerprint", exception);
        }
    }

    private CancelSpec parseCancelSpec(ProviderOperation operation) {
        try {
            JsonNode root = json.readTree(operation.requestFingerprintJson());
            String providerTransactionId = root.path("provider_transaction_id").asText(null);
            String reason = root.path("reason").asText(null);
            if (providerTransactionId == null || providerTransactionId.isBlank()
                    || !providerTransactionId.startsWith("pi_")) {
                throw new IllegalArgumentException("CANCEL fingerprint missing provider_transaction_id");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("CANCEL fingerprint missing reason");
            }
            return new CancelSpec(providerTransactionId, reason);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid CANCEL request fingerprint", exception);
        }
    }

    private RefundSpec parseRefundSpec(ProviderOperation operation) {
        try {
            JsonNode root = json.readTree(operation.requestFingerprintJson());
            String providerTransactionId = root.path("provider_transaction_id").asText(null);
            long amountMinor = root.path("amount_minor").asLong(-1);
            String currency = root.path("currency").asText(null);
            JsonNode metadataNode = root.path("metadata");
            Map<String, String> metadata = new LinkedHashMap<>();
            for (String key : List.of("order_id", "refund_request_id", "refund_transaction_id",
                    "payment_transaction_id")) {
                String value = metadataNode.path(key).asText(null);
                if (value == null || value.isBlank())
                    throw new IllegalArgumentException("REFUND fingerprint missing metadata " + key);
                metadata.put(key, value);
            }
            if (providerTransactionId == null || !providerTransactionId.startsWith("pi_")
                    || amountMinor <= 0 || !"VND".equals(currency)
                    || !operation.paymentTransactionId().equals(metadata.get("payment_transaction_id"))
                    || !operation.refundTransactionId().equals(metadata.get("refund_transaction_id"))) {
                throw new IllegalArgumentException("invalid REFUND request fingerprint");
            }
            return new RefundSpec(providerTransactionId, amountMinor, metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid REFUND request fingerprint", exception);
        }
    }

    private static Map<String, Object> durableIntent(PaymentIntent intent) {
        Map<String, Object> durable = new LinkedHashMap<>();
        durable.put("id", intent.getId());
        durable.put("status", intent.getStatus());
        if (intent.getAmount() != null) durable.put("amount_minor", intent.getAmount());
        if (intent.getCurrency() != null) durable.put("currency", intent.getCurrency());
        if (intent.getLatestCharge() != null) durable.put("provider_charge_id", intent.getLatestCharge());
        return durable;
    }

    private boolean matches(PaymentIntent intent, CreateSpec spec) {
        return intent.getAmount() != null
                && intent.getAmount() == spec.amountMinor()
                && "vnd".equalsIgnoreCase(intent.getCurrency())
                && intent.getMetadata() != null
                && REQUIRED_METADATA.stream().allMatch(key ->
                        spec.metadata().get(key).equals(intent.getMetadata().get(key)));
    }

    private boolean matches(Refund refund, RefundSpec spec) {
        return refund.getAmount() != null && refund.getAmount() == spec.amountMinor()
                && "vnd".equalsIgnoreCase(refund.getCurrency())
                && spec.providerTransactionId().equals(refund.getPaymentIntent())
                && refund.getMetadata() != null
                && spec.metadata().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(refund.getMetadata().get(entry.getKey())));
    }

    private RequestOptions requestOptions(String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder()
                .setConnectTimeout(connectTimeoutMillis)
                .setReadTimeout(readTimeoutMillis)
                .setMaxNetworkRetries(maxNetworkRetries);
        if (idempotencyKey != null) builder.setIdempotencyKey(idempotencyKey);
        return builder.build();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize sanitized provider result", impossible);
        }
    }

    private static String requestId(StripeSearchResult<?> result) {
        return result.getLastResponse() == null ? null : result.getLastResponse().requestId();
    }

    private static String requestId(StripeCollection<?> result) {
        return result.getLastResponse() == null ? null : result.getLastResponse().requestId();
    }

    private static int exactMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative() || duration.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a positive integer millisecond duration");
        }
        return Math.toIntExact(duration.toMillis());
    }

    private record CreateSpec(long amountMinor, Map<String, String> metadata) {}
    private record CancelSpec(String providerTransactionId, String reason) {}
    private record RefundSpec(String providerTransactionId, long amountMinor,
                              Map<String, String> metadata) {}
}
