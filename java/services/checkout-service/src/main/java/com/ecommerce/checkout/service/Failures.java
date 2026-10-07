package com.ecommerce.checkout.service;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.problem.ApiException;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.ecommerce.checkout.resilience.CallResult;
import com.ecommerce.checkout.resilience.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a failed dependency call into the public {@code 503}. Infrastructure failure never becomes a
 * business result: not "no discount", not "enough stock" (CHK-INV-04). The detail text is generic on
 * purpose; which dependency failed is in the metrics and logs, never in the response.
 */
@Component
public class Failures {

    private static final Logger log = LoggerFactory.getLogger(Failures.class);

    private final CheckoutProperties properties;

    public Failures(CheckoutProperties properties) {
        this.properties = properties;
    }

    public ApiException unavailable(CallResult<?> result) {
        return unavailable(ProblemCode.CHECKOUT_UNAVAILABLE, result);
    }

    public ApiException unavailable(ProblemCode code, CallResult<?> result) {
        if (result.outcome() == Outcome.CLIENT_ERROR) {
            log.error("dependency rejected Checkout with a configuration error (outcome {}); alert platform",
                    result.outcome().tag());
        }
        int retryAfter = result.retryAfterSeconds() != null
                ? result.retryAfterSeconds()
                : properties.retryAfter().unavailableSeconds();
        return new ApiException(unavailableProblem(code).withRetryAfter(Math.min(60, retryAfter)));
    }

    public ApiException unavailable(ProblemCode code, int retryAfterSeconds) {
        return new ApiException(unavailableProblem(code).withRetryAfter(Math.min(60, Math.max(1, retryAfterSeconds))));
    }

    public ApiException unavailable(ProblemCode code) {
        return unavailable(code, properties.retryAfter().unavailableSeconds());
    }

    private static Problem unavailableProblem(ProblemCode code) {
        String detail = code == ProblemCode.VOUCHER_OPTIONS_UNAVAILABLE
                ? "Voucher options could not be listed, try again shortly"
                : "A required service is temporarily unavailable, try again shortly";
        return Problem.of(code, detail);
    }
}
