package com.ecommerce.review.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The object storage Review holds media metadata for but does not own bytes in.
 *
 * <p>Nothing here is ever called inside a database transaction. The upload happens
 * directly between client and storage, the metadata read happens before the create or
 * edit transaction opens, and deletion happens after the transaction that recorded
 * {@code DELETE_PENDING} committed (ADR-011).
 */
public interface ReviewObjectStorage {

    /** A provider-neutral signed upload instruction; the object key never appears in it. */
    record UploadInstruction(String method, String url, Map<String, String> headers, Instant expiresAt) {
    }

    /** The immutable metadata of an uploaded object. */
    record StoredObject(String contentType, long byteSize, String checksum) {
    }

    /** What a delete attempt established about the object. */
    enum DeleteOutcome {
        /** Storage confirmed the object is gone. */
        DELETED,
        /**
         * Storage confirmed there is no such object. This is a successful, idempotent
         * outcome: an {@code UNATTACHED_EXPIRED} asset may never have been uploaded, and
         * treating it as a failure would retry it forever.
         */
        NOT_FOUND
    }

    /**
     * Signs a single-write upload of exactly this object.
     *
     * <p>The signature must not permit overwriting: without that, bytes could be
     * replaced after Review verified them and before it attached them.
     */
    UploadInstruction presignUpload(String objectKey, String contentType, long byteSize, Duration ttl);

    /** Reads immutable object metadata; empty when the object is not there. */
    Optional<StoredObject> head(String objectKey);

    /** A short-lived signed download URL, generated per response and never stored. */
    String presignDownload(String objectKey, Duration ttl);

    /**
     * Deletes the bytes.
     *
     * @throws StorageUnavailableException on a transient or definitive failure, which
     *         leaves the asset {@code DELETE_PENDING} for a safe retry
     */
    DeleteOutcome delete(String objectKey);
}
