package com.ecommerce.order.web;

import java.util.UUID;

/** Authenticated caller resolved by {@link IdentityFilter} for the current request. */
public record Caller(Kind kind, String principal, UUID userId, UUID actorId) {

    public enum Kind { BUYER, ADMIN, CHECKOUT, FINANCE }

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    public static Caller current() {
        return CURRENT.get();
    }

    static void set(Caller caller) {
        CURRENT.set(caller);
    }

    static void clear() {
        CURRENT.remove();
    }

    public boolean admin() {
        return kind == Kind.ADMIN;
    }

    public String role() {
        return kind == Kind.ADMIN ? "ADMIN" : "BUYER";
    }
}
