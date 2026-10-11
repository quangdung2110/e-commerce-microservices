package com.platform.paymentservice.adapter.in.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

final class PaymentOperationsContractInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        if (!(handler instanceof org.springframework.web.method.HandlerMethod method)
                || method.getBeanType() != PaymentOperationsController.class) return true;
        String path = request.getRequestURI();
        String actor = request.getHeader("X-Actor"), approver = request.getHeader("X-Approver");
        String correlation = request.getHeader("X-Correlation-Id"), key = request.getHeader("Idempotency-Key");
        boolean mutation = !"GET".equals(request.getMethod());
        boolean checker = mutation && !path.endsWith("/claim");
        boolean valid = text(actor, 3, 128) && text(correlation, 1, 128)
                && (!mutation || text(key, 16, 255))
                && (!checker || text(approver, 3, 128) && !actor.equals(approver));
        String relative = path.substring("/internal/v1/payment/".length());
        if (relative.startsWith("review-cases/")) valid &= relative.split("/")[1].matches("PRC_[0-9A-HJKMNP-TV-Z]{26}");
        if (relative.startsWith("webhooks/")) valid &= relative.split("/")[1].matches("evt_[A-Za-z0-9_]+");
        if (relative.startsWith("ledger/journals/")) valid &= relative.split("/")[2].matches("FJN_[0-9A-HJKMNP-TV-Z]{26}");
        if (relative.startsWith("disputes/")) valid &= relative.split("/")[1].matches("DSP_[0-9A-HJKMNP-TV-Z]{26}");
        if (valid) return true;
        String safe = text(correlation, 1, 128) ? correlation : "missing-correlation-id";
        response.setStatus(400);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Correlation-Id", safe);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(response.getOutputStream(),
                new PaymentController.Problem("VALIDATION_FAILED", false, safe));
        return false;
    }

    private static boolean text(String value, int min, int max) {
        return value != null && !value.isBlank() && value.length() >= min && value.length() <= max;
    }
}
