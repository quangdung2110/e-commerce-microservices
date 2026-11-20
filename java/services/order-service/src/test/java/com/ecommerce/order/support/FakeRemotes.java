package com.ecommerce.order.support;

import com.ecommerce.order.client.Gateways;
import com.ecommerce.order.client.RemoteResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contract fakes for Inventory workflow v1, Promotion allocation and Payment v1. They keep
 * stable idempotency (same key ⇒ same result) and record every call for assertions.
 * Behaviour switches are keyed by order ID so concurrent tests never interfere.
 */
public class FakeRemotes {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;
    public final List<String> calls = new CopyOnWriteArrayList<>();

    /** Per-order behaviour: "reserve" -> REJECT_ALL, "promotion" -> code, "initiate" -> REJECT, ... */
    public final Map<String, String> behaviour = new ConcurrentHashMap<>();
    /** Per-order number of upcoming transport failures for a given operation ("op:order"). */
    public final Map<String, Integer> transportFailures = new ConcurrentHashMap<>();
    /** Inventory refund-scope query body by scope ID (no-payout decisions). */
    public final Map<String, ObjectNode> scopes = new ConcurrentHashMap<>();

    private final Map<String, ObjectNode> replays = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> payments = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    public FakeRemotes(Clock clock) {
        this.clock = clock;
    }

    public List<String> callsFor(long orderId) {
        return calls.stream().filter(c -> c.contains(":" + orderId + ":") || c.endsWith(":" + orderId)).toList();
    }

    private boolean failTransport(String op, String orderId) {
        String key = op + ":" + orderId;
        Integer remaining = transportFailures.get(key);
        if (remaining != null && remaining > 0) {
            transportFailures.put(key, remaining - 1);
            return true;
        }
        return false;
    }

    private RemoteResult replayOr(String key, java.util.function.Supplier<RemoteResult> producer) {
        ObjectNode stored = replays.get(key);
        if (stored != null) {
            int status = stored.path("_status").asInt(200);
            ObjectNode body = stored.deepCopy();
            body.remove("_status");
            return status < 300 ? RemoteResult.accepted(status, body) : RemoteResult.rejected(status,
                    body.path("code").asText(), body);
        }
        RemoteResult result = producer.get();
        if (!result.isUnknown()) {
            ObjectNode copy = result.body() == null ? mapper.createObjectNode() : (ObjectNode) result.body().deepCopy();
            copy.put("_status", result.httpStatus());
            replays.put(key, copy);
        }
        return result;
    }

    private ObjectNode problem(String code) {
        return mapper.createObjectNode().put("code", code).put("retryable", false).put("status", 409);
    }

    // ------------------------------------------------------------------ Inventory

    public Gateways.Inventory inventory() {
        return new Gateways.Inventory() {
            @Override
            public RemoteResult reserve(Gateways.Command c, JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("inventory.reserve:" + order + ":");
                if (failTransport("reserve", order)) {
                    return RemoteResult.unknown("TRANSPORT");
                }
                return replayOr(c.commandId(), () -> {
                    ObjectNode r = mapper.createObjectNode().put("command_id", c.commandId())
                            .put("correlation_id", c.correlationId()).put("recorded_at", clock.instant().toString())
                            .put("order_id", order);
                    if ("REJECT_ALL".equals(behaviour.get("reserve:" + body.path("allocation_plan_id").asText()))) {
                        r.put("outcome", "REJECT_ALL");
                        r.putArray("rejected_lines").add(body.path("lines").get(0).path("order_line_id").asText());
                        return RemoteResult.accepted(200, r);
                    }
                    r.put("outcome", "RESERVED").put("expires_at", clock.instant().plus(Duration.ofMinutes(15)).toString())
                            .put("reservation_revision", 1);
                    r.set("lines", body.path("lines"));
                    return RemoteResult.accepted(200, r);
                });
            }

            @Override
            public RemoteResult commit(String orderId, Gateways.Command c, JsonNode body) {
                calls.add("inventory.commit:" + orderId + ":");
                if (failTransport("commit", orderId)) {
                    return RemoteResult.unknown("TRANSPORT");
                }
                return replayOr(c.commandId(), () -> "NOT_RESERVED".equals(behaviour.get("commit:" + orderId))
                        ? RemoteResult.rejected(409, "RESERVATION_NOT_RESERVED", problem("RESERVATION_NOT_RESERVED"))
                        : RemoteResult.accepted(200, mapper.createObjectNode().put("command_id", c.commandId())
                        .put("outcome", "COMMITTED").put("order_id", orderId).put("reservation_revision", 2)));
            }

            @Override
            public RemoteResult release(String orderId, Gateways.Command c, JsonNode body) {
                calls.add("inventory.release:" + orderId + ":" + body.path("reason").asText());
                return replayOr(c.commandId(), () -> RemoteResult.accepted(200, mapper.createObjectNode()
                        .put("command_id", c.commandId()).put("outcome", "RELEASED").put("order_id", orderId)
                        .put("reservation_revision", 2)));
            }

            @Override
            public RemoteResult releaseCommittedBeforeDispatch(String orderId, Gateways.Command c, JsonNode body) {
                calls.add("inventory.releaseCommitted:" + orderId + ":");
                return replayOr(c.commandId(), () -> {
                    if ("DISPATCHED".equals(behaviour.get("releaseCommitted:" + orderId))) {
                        return RemoteResult.rejected(409, "PRE_DISPATCH_RELEASE_NOT_ALLOWED",
                                problem("PRE_DISPATCH_RELEASE_NOT_ALLOWED"));
                    }
                    ObjectNode r = mapper.createObjectNode().put("command_id", c.commandId())
                            .put("correlation_id", c.correlationId()).put("outcome", "RELEASED").put("order_id", orderId)
                            .put("reservation_revision", 3);
                    r.putArray("released_order_line_ids").add("1");
                    return RemoteResult.accepted(200, r);
                });
            }

            @Override
            public RemoteResult registerRefundScope(Gateways.Command c, JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("inventory.registerScope:" + order + ":");
                behaviour.put("request:" + order, body.path("refund_request_id").asText());
                return replayOr(c.commandId(), () -> "REJECT".equals(behaviour.get("register:" + order))
                        ? RemoteResult.rejected(409, "REFUND_SCOPE_LINE_NOT_DISPATCHED", problem("REFUND_SCOPE_LINE_NOT_DISPATCHED"))
                        : RemoteResult.accepted(200, scopeResponse(c, "scope-" + order, order,
                        body.path("refund_request_id").asText(), "REGISTERED", 1)));
            }

            @Override
            public RemoteResult closeRefundScope(String scopeId, Gateways.Command c, JsonNode body) {
                String order = scopeId.substring("scope-".length());
                calls.add("inventory.closeScope:" + order + ":" + body.path("reason").asText());
                if (failTransport("close", order)) {
                    return RemoteResult.unknown("TRANSPORT");
                }
                return replayOr(c.commandId(), () -> "REJECT".equals(behaviour.get("close:" + order))
                        ? RemoteResult.rejected(409, "REFUND_SCOPE_STATE_CONFLICT", problem("REFUND_SCOPE_STATE_CONFLICT"))
                        : RemoteResult.accepted(200, scopeResponse(c, scopeId, order,
                        behaviour.getOrDefault("request:" + order, ""), "CLOSED", 5)));
            }

            @Override
            public RemoteResult closeIncompleteRefundScope(String scopeId, Gateways.Command c, JsonNode body) {
                String order = scopeId.substring("scope-".length());
                calls.add("inventory.closeIncomplete:" + order + ":" + body.path("reason").asText());
                return replayOr(c.commandId(), () -> RemoteResult.accepted(200, scopeResponse(c, scopeId, order,
                        behaviour.getOrDefault("request:" + order, ""), "CLOSED_INCOMPLETE", 6)));
            }

            @Override
            public RemoteResult cancelRefundScope(String scopeId, Gateways.Command c) {
                String order = scopeId.substring("scope-".length());
                calls.add("inventory.cancelScope:" + order + ":");
                return replayOr(c.commandId(), () -> RemoteResult.accepted(200, scopeResponse(c, scopeId, order,
                        behaviour.getOrDefault("request:" + order, ""), "CANCELLED", 2)));
            }

            @Override
            public RemoteResult getReservation(String orderId) {
                return RemoteResult.accepted(200, mapper.createObjectNode().put("order_id", orderId).put("state", "RELEASED"));
            }

            @Override
            public RemoteResult getRefundScope(String scopeId) {
                ObjectNode scope = scopes.get(scopeId);
                return scope == null ? RemoteResult.rejected(404, "NOT_FOUND", null) : RemoteResult.accepted(200, scope);
            }

            @Override
            public RemoteResult getCommand(String commandId) {
                return RemoteResult.rejected(404, "NOT_FOUND", null);
            }
        };
    }

    private ObjectNode scopeResponse(Gateways.Command c, String scopeId, String order, String requestId, String outcome,
                                     int revision) {
        if (requestId.isEmpty()) {
            requestId = behaviour.getOrDefault("request:" + order, "");
        }
        return mapper.createObjectNode().put("command_id", c.commandId()).put("correlation_id", c.correlationId())
                .put("recorded_at", clock.instant().toString()).put("outcome", outcome).put("refund_scope_id", scopeId)
                .put("order_id", order).put("refund_request_id", requestId).put("scope_revision", revision);
    }

    // ------------------------------------------------------------------ Promotion

    public Gateways.Promotion promotion() {
        return new Gateways.Promotion() {
            @Override
            public RemoteResult reserve(JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("promotion.reserve:" + order + ":" + body.path("promotion_id").asText());
                String reject = behaviour.get("promotion:" + body.path("user_id").asText());
                if (reject != null) {
                    return RemoteResult.rejected(409, reject, problem(reject));
                }
                ObjectNode r = mapper.createObjectNode().put("order_id", order)
                        .put("promotion_id", body.path("promotion_id").asText()).put("state", "RESERVED")
                        .put("reserved_at", clock.instant().toString())
                        .put("expires_at", clock.instant().plus(Duration.ofMinutes(15)).toString());
                r.set("discount", body.path("expected_discount"));
                return RemoteResult.accepted(201, r);
            }

            @Override
            public RemoteResult cancel(JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("promotion.cancel:" + order + ":" + body.path("promotion_id").asText());
                return RemoteResult.accepted(200, mapper.createObjectNode().put("order_id", order)
                        .put("promotion_id", body.path("promotion_id").asText()).put("outcome", "RELEASED"));
            }

            @Override
            public RemoteResult confirm(String key, JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("promotion.confirm:" + order + ":" + key);
                if (failTransport("confirm", order)) {
                    return RemoteResult.unknown("TRANSPORT");
                }
                ObjectNode r = mapper.createObjectNode().put("operation", "CONFIRM").put("order_id", order)
                        .put("promotion_id", body.path("promotion_id").asText()).put("wallet_status", "USED")
                        .put("completed_at", clock.instant().toString());
                r.set("discount", body.path("expected_discount"));
                return RemoteResult.accepted(200, r);
            }
        };
    }

    // ------------------------------------------------------------------ Payment

    public static String txn(long n) {
        return "TXN_" + String.format("%026d", n);
    }

    public static String rfd(long n) {
        return "RFD_" + String.format("%026d", n);
    }

    public Gateways.Payment payment() {
        return new Gateways.Payment() {
            @Override
            public RemoteResult initiate(String key, String correlationId, JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("payment.initiate:" + order + ":" + body.path("payment_attempt_number").asInt());
                if ("NO_CREATE".equals(behaviour.get("initiate:" + body.path("user_id").asText()))) {
                    return replayOr(key, () -> RemoteResult.rejected(422, "PROVIDER_CREATE_NOT_ACCEPTED",
                            mapper.createObjectNode().put("code", "PROVIDER_CREATE_NOT_ACCEPTED")
                                    .put("retryable", false).put("no_payment_created", true)
                                    .put("order_id", order).put("payment_attempt_number", body.path("payment_attempt_number").asInt())
                                    .put("transaction_id", txn(sequence.getAndIncrement()))
                                    .put("provider_operation_id", "POP_00000000000000000000000001")));
                }
                if ("REJECT".equals(behaviour.get("initiate:" + body.path("user_id").asText()))) {
                    return RemoteResult.rejected(422, "PROVIDER_REQUEST_REJECTED", problem("PROVIDER_REQUEST_REJECTED"));
                }
                return replayOr(key, () -> {
                    String txn = txn(sequence.getAndIncrement());
                    ObjectNode state = mapper.createObjectNode().put("transaction_id", txn).put("order_id", order)
                            .put("payment_attempt_number", body.path("payment_attempt_number").asInt())
                            .put("status", "PENDING").put("financial_state_version", 1);
                    state.set("amount", body.path("amount"));
                    payments.put(txn, state);
                    return RemoteResult.accepted(200, mapper.createObjectNode().put("transaction_id", txn)
                            .put("payment_attempt_number", body.path("payment_attempt_number").asInt())
                            .put("status", "PENDING").put("client_secret", "cs_test_" + txn)
                            .put("publishable_key", "pk_test_local")
                            .put("expires_at", clock.instant().plus(Duration.ofMinutes(30)).toString()));
                });
            }

            @Override
            public RemoteResult cancel(String txn, String key, String correlationId, JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("payment.cancel:" + order + ":" + body.path("reason").asText());
                ObjectNode state = payments.get(txn);
                String outcome = behaviour.getOrDefault("cancel:" + order, "CANCELED");
                ObjectNode r = mapper.createObjectNode().put("transaction_id", txn).put("order_id", order)
                        .put("outcome", outcome).put("financial_state_version", 2);
                if ("ALREADY_SUCCEEDED".equals(outcome)) {
                    r.set("amount", state.path("amount"));
                    state.put("status", "SUCCESS");
                } else if (!"CANCEL_PENDING".equals(outcome)) {
                    state.put("status", "CANCELED");
                }
                return RemoteResult.accepted(200, r);
            }

            @Override
            public RemoteResult refund(String txn, String key, String correlationId, JsonNode body) {
                String order = body.path("order_id").asText();
                calls.add("payment.refund:" + order + ":" + body.path("amount").path("amount_minor").asLong());
                behaviour.put("refundBody:" + order, body.toString());
                return replayOr(key, () -> {
                    String rfd = rfd(sequence.getAndIncrement());
                    payments.get(txn).set("refund", mapper.createObjectNode().put("refund_transaction_id", rfd)
                            .put("status", "PROCESSING"));
                    return RemoteResult.accepted(200, mapper.createObjectNode().put("refund_transaction_id", rfd)
                            .put("payment_transaction_id", txn).put("status", "PROCESSING"));
                });
            }

            @Override
            public RemoteResult clientData(String txn, String correlationId) {
                ObjectNode state = payments.get(txn);
                boolean usable = state != null && "PENDING".equals(state.path("status").asText());
                ObjectNode r = mapper.createObjectNode().put("transaction_id", txn)
                        .put("payment_attempt_number", state == null ? 1 : state.path("payment_attempt_number").asInt())
                        .put("status", state == null ? "FAILED" : state.path("status").asText()).put("usable", usable);
                if (usable) {
                    r.put("client_secret", "cs_test_" + txn).put("publishable_key", "pk_test_local")
                            .put("client_expires_at", clock.instant().plus(Duration.ofMinutes(30)).toString());
                } else {
                    r.putNull("client_secret").putNull("publishable_key").putNull("client_expires_at");
                }
                return RemoteResult.accepted(200, r);
            }

            @Override
            public RemoteResult getPayment(String txn, String correlationId) {
                ObjectNode state = payments.get(txn);
                return state == null ? RemoteResult.rejected(404, "PAYMENT_NOT_FOUND", null)
                        : RemoteResult.accepted(200, state.deepCopy());
            }

            @Override
            public RemoteResult getRefund(String refundTxn, String correlationId) {
                return RemoteResult.rejected(404, "REFUND_NOT_FOUND", null);
            }
        };
    }

    /** Marks the fake payment state (e.g. FAILED) so client-data stops returning a secret. */
    public void setPaymentStatus(String txn, String status) {
        ObjectNode state = payments.get(txn);
        if (state != null) {
            state.put("status", status);
        }
    }

    public ObjectNode scope(String scopeId, String orderId, String requestId, String state, long[][] counters) {
        ObjectNode scope = mapper.createObjectNode().put("refund_scope_id", scopeId).put("order_id", orderId)
                .put("refund_request_id", requestId).put("state", state).put("revision", 4);
        ArrayNode lines = scope.putArray("lines");
        for (long[] c : counters) {
            lines.addObject().put("order_line_id", Long.toString(c[0])).put("sku_id", "SKU")
                    .put("expected_quantity", c[1]).put("received_quantity", c[2]).put("passed_quantity", c[3])
                    .put("failed_quantity", c[4]);
        }
        scopes.put(scopeId, scope);
        return scope;
    }
}
