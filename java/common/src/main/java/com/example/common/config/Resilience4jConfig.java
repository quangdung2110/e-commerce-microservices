package com.example.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j Configuration for Fault Tolerance
 * 
 * Patterns:
 * 1. Circuit Breaker: Prevent cascade failures
 * 2. Rate Limiter: Prevent system overload
 * 3. Retry: Handle transient failures
 * 4. Time Limiter: Prevent hanging requests
 */
@Configuration
public class Resilience4jConfig {

    /**
     * Circuit Breaker for inter-service calls
     * - Opens after 50% failures in 5 requests
     * - Waits 60s before half-open
     * - Closes after 5 successful requests
     */
    @Bean
    public CircuitBreakerConfig defaultCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .permittedNumberOfCallsInHalfOpenState(5)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build();
    }

    /**
     * Rate Limiter for API endpoints
     * - 100 requests per minute per user
     */
    @Bean
    public RateLimiterConfig defaultRateLimiterConfig() {
        return RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .limitForPeriod(100)
            .timeoutDuration(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Retry for transient failures
     * - 3 attempts with exponential backoff
     */
    @Bean
    public RetryConfig defaultRetryConfig() {
        return RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofSeconds(1))
            .retryExceptions(Exception.class)
            .build();
    }

    /**
     * Time Limiter for preventing hanging requests
     * - 5 seconds timeout
     */
    @Bean
    public TimeLimiterConfig defaultTimeLimiterConfig() {
        return TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofSeconds(5))
            .build();
    }
}
