package com.ecommerce.review.storage;

/**
 * The signer or the metadata read failed.
 *
 * <p>It maps to a retryable {@code 503} on the media command and never to an attachment:
 * Review does not attach an object it could not check
 * ({@code docs/review/operations.md 2}).
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
