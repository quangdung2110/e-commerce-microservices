package com.ecommerce.inventory.command;

/**
 * Terminal {@code 200} outcome of a command body, including the domain outcome
 * {@code REJECT_ALL} which is a valid result rather than an error.
 *
 * @param body       response body; persisted and replayed for the same command
 * @param outcome    domain outcome recorded on the command query (e.g. {@code RESERVED})
 * @param resourceId aggregate the command acted on, for the command query
 */
public record Accepted(Object body, String outcome, String resourceId) {
}
