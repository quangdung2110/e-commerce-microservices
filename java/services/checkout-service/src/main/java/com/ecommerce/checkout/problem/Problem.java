package com.ecommerce.checkout.problem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business or infrastructure problem before it is rendered as {@code application/problem+json}.
 * It carries no request-specific data ({@code instance}, {@code trace_id}) so the same value can be
 * stored in a terminal result and replayed for a later request.
 *
 * @param extensions code-specific fields of the contract: {@code status_url}, {@code changed_fields},
 *                   {@code sku_ids}, {@code rejected_lines}, {@code promotion_reason},
 *                   {@code dropped_promotion}, {@code errors}
 * @param retryAfterSeconds present for {@code 503}; not stored in terminal results
 */
public record Problem(ProblemCode code, int status, String detail, Map<String, Object> extensions,
                      Integer retryAfterSeconds) {

    public Problem {
        extensions = extensions == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }

    public static Problem of(ProblemCode code, String detail) {
        return new Problem(code, code.status(), detail, Map.of(), null);
    }

    public static Problem of(ProblemCode code, String detail, Map<String, Object> extensions) {
        return new Problem(code, code.status(), detail, extensions, null);
    }

    public Problem withStatus(int newStatus) {
        return new Problem(code, newStatus, detail, extensions, retryAfterSeconds);
    }

    public Problem withExtension(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(extensions);
        copy.put(name, value);
        return new Problem(code, status, detail, copy, retryAfterSeconds);
    }

    public Problem withRetryAfter(int seconds) {
        return new Problem(code, status, detail, extensions, seconds);
    }

    /** The form kept in Redis: no retry-after, which belongs to one response. */
    public Problem forStorage() {
        return new Problem(code, status, detail, extensions, null);
    }
}
