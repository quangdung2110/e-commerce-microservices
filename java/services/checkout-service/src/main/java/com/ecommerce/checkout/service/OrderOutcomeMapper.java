package com.ecommerce.checkout.service;

import com.ecommerce.checkout.client.OrderClient;
import com.ecommerce.checkout.domain.SessionState;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps the terminal business outcomes Order reports (interfaces.md, "Mapping ket qua Order"; ADR-015)
 * to a Checkout state and a public problem. Order's body is never relayed: only its {@code code}
 * decides, and an unknown code is a contract violation that becomes {@code SUBMISSION_REJECTED}.
 *
 * <p>The same rules apply to the {@code 409}/{@code 422} of the POST and to the {@code failure} of the
 * recovery GET, because Order stores one failure and returns it on both paths.
 */
@Component
public class OrderOutcomeMapper {

    private static final Logger log = LoggerFactory.getLogger(OrderOutcomeMapper.class);

    /** Order failure codes that Checkout relays under the same name. */
    private static final Set<ProblemCode> RELAYED_FAILURES = Set.of(
            ProblemCode.INVENTORY_RESERVATION_REJECTED,
            ProblemCode.PAYMENT_INITIATION_REJECTED,
            ProblemCode.CHECKOUT_PAYMENT_WINDOW_EXHAUSTED,
            ProblemCode.ORDER_CANCELLED_DURING_PREPARATION);

    /** A terminal result: the state the token ends in and the problem the client sees. */
    public record Terminal(SessionState state, Problem problem) {
    }

    /** Order POST {@code 409}/{@code 422}. */
    public Terminal fromRejection(OrderClient.Rejected rejected) {
        return map(rejected.problem());
    }

    /** Order GET {@code state=FAILED} or {@code RECALCULATION_REQUIRED}; {@code failure} is Order's problem. */
    public Terminal fromFailure(String orderState, JsonNode failure) {
        if ("RECALCULATION_REQUIRED".equals(orderState)) {
            return recalculation(failure);
        }
        return map(failure);
    }

    private Terminal map(JsonNode problem) {
        ProblemCode code = ProblemCode.parseOrNull(problem.path("code").asText(null));
        if (code == ProblemCode.PROMOTION_RECALCULATION_NEEDED) {
            return recalculation(problem);
        }
        if (code != null && RELAYED_FAILURES.contains(code)) {
            return new Terminal(SessionState.FAILED, Problem.of(code, code.title()));
        }
        // Order answered a business outcome this Checkout does not know: a contract violation to alert on.
        log.error("Order returned an unmapped submission outcome (code {}); Checkout and Order contracts differ",
                problem.path("code").asText("?"));
        return new Terminal(SessionState.FAILED, Problem.of(ProblemCode.SUBMISSION_REJECTED,
                "The order could not be created from this checkout; start a new preview"));
    }

    /** {@code dropped_promotion} is the first element of Order's {@code dropped_promotions}. */
    private Terminal recalculation(JsonNode problem) {
        JsonNode dropped = problem.path("dropped_promotions");
        Problem result = Problem.of(ProblemCode.PROMOTION_RECALCULATION_NEEDED,
                "The voucher can no longer be applied; choose a voucher again");
        if (dropped.isArray() && !dropped.isEmpty() && dropped.get(0).path("promotion_id").isTextual()) {
            JsonNode first = dropped.get(0);
            Map<String, Object> droppedPromotion = new LinkedHashMap<>();
            droppedPromotion.put("promotion_id", first.path("promotion_id").asText());
            droppedPromotion.put("reason", first.path("reason").asText("UNKNOWN"));
            result = result.withExtension("dropped_promotion", droppedPromotion);
        }
        return new Terminal(SessionState.CONFLICT, result);
    }
}
