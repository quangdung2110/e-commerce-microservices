package com.ecommerce.order.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Classified outcome of one remote command. UNKNOWN is never a negative business
 * outcome: callers query/replay the same command identity (ADR-011).
 */
public record RemoteResult(Kind kind, int httpStatus, String errorCode, JsonNode body) {

    public enum Kind { ACCEPTED, REJECTED, UNKNOWN }

    public static RemoteResult accepted(int status, JsonNode body) {
        return new RemoteResult(Kind.ACCEPTED, status, null, body);
    }

    public static RemoteResult rejected(int status, String code, JsonNode body) {
        return new RemoteResult(Kind.REJECTED, status, code, body);
    }

    public static RemoteResult unknown(String reason) {
        return new RemoteResult(Kind.UNKNOWN, 0, reason, null);
    }

    public boolean isAccepted() {
        return kind == Kind.ACCEPTED;
    }

    public boolean isRejected() {
        return kind == Kind.REJECTED;
    }

    public boolean isUnknown() {
        return kind == Kind.UNKNOWN;
    }
}
