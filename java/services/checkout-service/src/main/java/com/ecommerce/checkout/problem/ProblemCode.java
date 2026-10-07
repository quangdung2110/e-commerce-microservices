package com.ecommerce.checkout.problem;

/**
 * The stable {@code CheckoutProblem} catalog (interfaces.md, "Error catalog"; ADR-015). Each code
 * carries its default HTTP status, whether the same request may be retried, the title, and the
 * next step the frontend takes ({@link Action}). {@code SKU_UNAVAILABLE} is {@code 422} at Preview
 * and {@code 409} at Submit, so a {@link Problem} may override the status.
 */
public enum ProblemCode {
    INVALID_REQUEST(400, false, Action.NONE, "Invalid request"),
    UNAUTHENTICATED(401, false, Action.NONE, "Authentication required"),
    IDEMPOTENCY_KEY_REUSED(409, false, Action.NONE, "Idempotency key reused with a different request"),
    ACTIVE_CHECKOUT_CHANGED(409, false, Action.START_NEW_PREVIEW, "The active checkout changed"),
    SUBMISSION_IN_PROGRESS(409, false, Action.VIEW_SUBMISSION, "A submission is in progress"),
    CHECKOUT_ALREADY_SUBMITTED(409, false, Action.VIEW_SUBMISSION, "The checkout was already submitted"),
    CART_EMPTY(422, false, Action.EDIT_CART, "The cart is empty"),
    SKU_UNAVAILABLE(422, false, Action.EDIT_CART, "A product is no longer available"),
    ADDRESS_NOT_FOUND(422, false, Action.CHANGE_ADDRESS, "Delivery address not found"),
    INSUFFICIENT_STOCK(422, false, Action.EDIT_CART, "Not enough stock"),
    PROMOTION_INVALID(422, false, Action.CHANGE_VOUCHER, "The voucher cannot be applied"),
    FINAL_AMOUNT_NOT_PAYABLE(422, false, Action.CHANGE_VOUCHER, "The amount to pay is below 1 VND"),
    CHECKOUT_NOT_FOUND(404, false, Action.START_NEW_PREVIEW, "Checkout not found"),
    SUBMISSION_NOT_FOUND(404, false, Action.NONE, "Submission not found"),
    CHECKOUT_EXPIRED(410, false, Action.START_NEW_PREVIEW, "The checkout expired"),
    CHECKOUT_SUPERSEDED(410, false, Action.START_NEW_PREVIEW, "The checkout was replaced by a newer one"),
    CART_CHANGED(409, false, Action.START_NEW_PREVIEW, "The cart changed"),
    PRICE_CHANGED(409, false, Action.START_NEW_PREVIEW, "A price changed"),
    PROMOTION_CHANGED(409, false, Action.CHANGE_VOUCHER, "The voucher result changed"),
    PROMOTION_RECALCULATION_NEEDED(409, false, Action.CHANGE_VOUCHER, "The voucher must be recalculated"),
    INVENTORY_RESERVATION_REJECTED(409, false, Action.EDIT_CART, "Stock could not be reserved"),
    PAYMENT_INITIATION_REJECTED(409, false, Action.START_NEW_PREVIEW, "Payment could not be started"),
    CHECKOUT_PAYMENT_WINDOW_EXHAUSTED(409, false, Action.START_NEW_PREVIEW, "The payment window was exhausted"),
    ORDER_CANCELLED_DURING_PREPARATION(409, false, Action.START_NEW_PREVIEW, "The order was cancelled"),
    SUBMISSION_NOT_ACCEPTED(409, false, Action.START_NEW_PREVIEW, "The submission was not accepted"),
    SUBMISSION_REJECTED(409, false, Action.START_NEW_PREVIEW, "The submission was rejected"),
    CHECKOUT_UNAVAILABLE(503, true, Action.RETRY, "Checkout is temporarily unavailable"),
    VOUCHER_OPTIONS_UNAVAILABLE(503, true, Action.RETRY, "Voucher options are temporarily unavailable");

    /** What the frontend does next; never a reason to retry the same request unless {@link #RETRY}. */
    public enum Action {
        RETRY, START_NEW_PREVIEW, EDIT_CART, CHANGE_ADDRESS, CHANGE_VOUCHER, VIEW_SUBMISSION, NONE
    }

    private final int status;
    private final boolean retryable;
    private final Action action;
    private final String title;

    ProblemCode(int status, boolean retryable, Action action, String title) {
        this.status = status;
        this.retryable = retryable;
        this.action = action;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public Action action() {
        return action;
    }

    public String title() {
        return title;
    }

    /** Kebab-case slug used in the problem {@code type} URI. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    public static ProblemCode parseOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
