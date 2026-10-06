package com.ecommerce.review.domain;

/**
 * One frozen {@code t.review.events} watermark of an export session.
 *
 * <p>{@code replayFromOffset} is the end offset captured before the scan, and it is
 * inclusive: Search seeks to it and replays from there (ADR-006). Every page of the
 * session echoes the identical set (REVIEW-INV-009).
 */
public record PartitionOffset(String topic, int partition, long replayFromOffset) {
}
