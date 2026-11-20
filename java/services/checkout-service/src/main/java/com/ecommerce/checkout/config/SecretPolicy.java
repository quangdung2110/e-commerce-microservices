package com.ecommerce.checkout.config;

import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a secret must not be outside development (security review 2026-10-22, H1; mesh ADR-004):
 * missing, shorter than its minimum, a value committed to the repository for the local stack, or a
 * placeholder. A base64 value is checked again after decoding, so a placeholder phrase cannot hide
 * inside an encoded key. Every service with a secret carries the same rules (Go: Auth and
 * Notification {@code internal/common/config/secrets.go}).
 */
public final class SecretPolicy {

    /** Profiles of a local run (IDE, docker-compose, Docker Desktop Kubernetes) and of the test suite. */
    public static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "local", "test");

    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change_me", "change-me", "changeme", "change-in-prod", "change_in_prod", "local", "dev-", "dev_",
            "development", "example", "placeholder", "not-a-secret", "minioadmin");

    private SecretPolicy() {
    }

    public static boolean isDevelopment(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(DEVELOPMENT_PROFILES::contains);
    }

    /** The reason {@code value} cannot be a secret, or empty when it can. */
    public static Optional<String> problem(String name, String value, int minLength, Collection<String> committedValues) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return Optional.of(name + " is required outside development");
        }
        if (committedValues.contains(trimmed)) {
            return Optional.of(name + " is the development value committed to the repository");
        }
        List<String> candidates = new ArrayList<>(List.of(trimmed));
        try {
            candidates.add(new String(Base64.getDecoder().decode(trimmed), StandardCharsets.ISO_8859_1));
        } catch (IllegalArgumentException notBase64) {
            // checked as plain text only
        }
        for (String candidate : candidates) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (String marker : PLACEHOLDER_MARKERS) {
                if (lower.contains(marker)) {
                    return Optional.of(name + " looks like a placeholder (contains \"" + marker + "\")");
                }
            }
        }
        if (trimmed.length() < minLength) {
            return Optional.of(name + " must be at least " + minLength + " characters");
        }
        return Optional.empty();
    }
}
