package com.ecommerce.review.config;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.common.Uuids;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.UUID;

/**
 * Reads the trusted caller identity the API Gateway injected after JWT verification.
 *
 * <p>These headers are only trustworthy because {@code /api/v1/**} is not reachable
 * except through the Gateway, which strips any client-supplied copy. Review still
 * authorizes ownership and role itself; the header establishes who is calling, never
 * what they may do.
 *
 * <p>No profile name or email is read from anywhere: the author pseudonym is derived
 * from the identifier alone (ADR-007).
 */
@Component
public class CallerResolver {

    private final ReviewProperties properties;

    public CallerResolver(ReviewProperties properties) {
        this.properties = properties;
    }

    /** The authenticated buyer or administrator, or a 401 when the header is absent. */
    public UUID requireUserId(HttpServletRequest request) {
        UUID userId = Uuids.parseOrNull(request.getHeader(properties.gateway().userIdHeader()));
        if (userId == null) {
            throw new ReviewException(ErrorCode.UNAUTHENTICATED,
                    "Missing trusted identity from the API Gateway");
        }
        return userId;
    }

    /** The acting administrator of a moderation command; also enforces the role. */
    public UUID requireAdminId(HttpServletRequest request) {
        UUID adminId = requireUserId(request);
        if (!hasAdminRole(request)) {
            throw new ReviewException(ErrorCode.FORBIDDEN,
                    "Review moderation requires the " + properties.gateway().adminRole() + " role");
        }
        return adminId;
    }

    public boolean hasAdminRole(HttpServletRequest request) {
        String roles = request.getHeader(properties.gateway().rolesHeader());
        return roles != null && Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(role -> role.equalsIgnoreCase(properties.gateway().adminRole()));
    }
}
