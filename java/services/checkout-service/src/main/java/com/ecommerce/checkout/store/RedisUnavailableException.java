package com.ecommerce.checkout.store;

/**
 * A Redis command or script failed or timed out. Checkout has no fallback without its workflow state
 * (data.md, "Eviction va mat Redis"), so this becomes {@code 503 CHECKOUT_UNAVAILABLE}.
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String operation, Throwable cause) {
        super("redis " + operation + " failed", cause);
    }
}
