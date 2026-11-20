package com.ecommerce.inventory.command;

import java.util.function.Supplier;

/**
 * Terminal {@code 200} outcome of a command body, including the domain outcome
 * {@code REJECT_ALL} which is a valid result rather than an error.
 *
 * @param body       response body; persisted and replayed for the same command
 * @param outcome    domain outcome recorded on the command query (e.g. {@code RESERVED})
 * @param resourceId aggregate the command acted on, for the command query
 * @param lastStep   statements the executor runs after it has recorded this result, as the last
 *                   ones before commit (ADR-033: the hot stock rows are locked only there). It
 *                   returns {@code null} to keep the recorded result, or, after rolling back past
 *                   that record, the outcome to record instead (which has no last step of its own).
 *                   A {@link com.ecommerce.inventory.common.DomainRejection} it throws is recorded
 *                   like one thrown by the body; any other exception aborts the transaction.
 *                   {@code null} when the body did everything itself.
 */
public record Accepted(Object body, String outcome, String resourceId, Supplier<Accepted> lastStep) {

    public Accepted(Object body, String outcome, String resourceId) {
        this(body, outcome, resourceId, null);
    }

    /** This outcome, recorded before {@code step} runs. */
    public Accepted thenLast(Supplier<Accepted> step) {
        return new Accepted(body, outcome, resourceId, step);
    }
}
