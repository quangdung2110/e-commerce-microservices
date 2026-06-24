package com.platform.inventoryservice.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "Idempotency-Key";
    public static final String MDC_KEY = "idempotencyKey";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = request.getHeader(HEADER);
        if (key != null && !key.isBlank()) {
            MDC.put(MDC_KEY, key);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove(MDC_KEY);
    }
}