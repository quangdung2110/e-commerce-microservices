package com.ecommerce.inventory.common;

/**
 * A durable business rejection. The command executor rolls back every attempted change to
 * its savepoint, stores this outcome as the command's terminal result and replays it for
 * the same command ID and payload. It never claims an operation identity.
 */
public class DomainRejection extends RuntimeException {

    private final ErrorCode code;

    public DomainRejection(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
