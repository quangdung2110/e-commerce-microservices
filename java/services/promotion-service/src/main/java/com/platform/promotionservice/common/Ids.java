package com.platform.promotionservice.common;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Opaque ID trên wire ↔ storage. HTTP v1 dùng {@code OpaqueId} (string
 * {@code ^[A-Za-z0-9_.-]+$}); storage là BIGSERIAL. Client không được suy luận
 * kiểu, nhưng adapter phải parse được: ID không phải số → 400.
 */
public final class Ids {

    private static final Pattern OPAQUE = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    private Ids() {
    }

    public static String opaque(long id) {
        return Long.toString(id);
    }

    public static String opaque(Long id) {
        return id == null ? null : Long.toString(id);
    }

    public static long parse(String value, String field) {
        if (value == null || !OPAQUE.matcher(value).matches()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " is not a valid identifier");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " is not a known identifier");
        }
    }

    public static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, field + " is not a valid UUID");
        }
    }
}
