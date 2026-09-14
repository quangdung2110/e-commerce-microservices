package com.platform.promotionservice.common;

import java.util.List;
import java.util.UUID;

/**
 * Identity của request hiện tại do {@code TrustedIdentityFilter} đặt: user từ
 * Gateway (X-User-Id/X-User-Roles) hoặc workload identity của route internal.
 * Ngoài ra giữ X-Request-Id/traceparent cho log và problem trace_id.
 */
public final class CallerContext {

    private static final ThreadLocal<CallerContext> CURRENT = new ThreadLocal<>();

    private final UUID userId;
    private final List<String> roles;
    private final String serviceIdentity;
    private final String requestId;
    private final String traceparent;

    private CallerContext(UUID userId, List<String> roles, String serviceIdentity,
                          String requestId, String traceparent) {
        this.userId = userId;
        this.roles = roles;
        this.serviceIdentity = serviceIdentity;
        this.requestId = requestId;
        this.traceparent = traceparent;
    }

    public static void set(UUID userId, List<String> roles, String serviceIdentity,
                           String requestId, String traceparent) {
        CURRENT.set(new CallerContext(userId, roles == null ? List.of() : List.copyOf(roles),
                serviceIdentity, requestId, traceparent));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static CallerContext current() {
        CallerContext context = CURRENT.get();
        return context == null ? new CallerContext(null, List.of(), null, null, null) : context;
    }

    /** User ID đã được Gateway verify; ném 401 nếu route yêu cầu user mà không có. */
    public static UUID requireUserId() {
        UUID userId = current().userId;
        if (userId == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Missing trusted user identity");
        }
        return userId;
    }

    /** Actor dùng cho audit created_by/updated_by. */
    public static String actor() {
        CallerContext context = current();
        if (context.userId != null) {
            return context.userId.toString();
        }
        return context.serviceIdentity == null ? "system" : context.serviceIdentity;
    }

    public UUID userId() {
        return userId;
    }

    public List<String> roles() {
        return roles;
    }

    public String serviceIdentity() {
        return serviceIdentity;
    }

    public String requestId() {
        return requestId;
    }

    public String traceparent() {
        return traceparent;
    }

    public static String currentTraceparent() {
        return current().traceparent;
    }

    public static String currentRequestId() {
        return current().requestId;
    }
}
