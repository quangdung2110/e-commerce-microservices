package com.ecommerce.promotion.presentation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration cho Promotion Service.
 *
 * Trong kiến trúc microservices, Authentication/Authorization được xử lý bởi:
 * 1. API Gateway (KrakenD) — JWT validation
 * 2. Identity Service — Token issuance
 *
 * Promotion Service tin tưởng requests đã được Gateway xác thực,
 * nhận user info qua headers (X-User-Id, X-User-Roles).
 *
 * Phân quyền tại đây:
 * - Admin APIs (CRUD promotions/campaigns/markdown-rules): yêu cầu ADMIN role
 * - User APIs (claim, wallet, calculate-discount): yêu cầu USER role
 * - Internal APIs (reserve budget): từ service-to-service, permitAll (qua internal network)
 * - Actuator/health: permitAll
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless: không tạo HTTP Session
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Disable CSRF: Stateless REST API, token-based auth
                .csrf(AbstractHttpConfigurer::disable)

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Health/Actuator endpoints — public
                        .requestMatchers("/actuator/**").permitAll()

                        // Internal service-to-service APIs — permitAll (mạng nội bộ)
                        // Order Service gọi POST /v1/budgets/reserve
                        // Cart/Order Service gọi POST /v1/discounts/calculate
                        .requestMatchers(HttpMethod.POST, "/v1/budgets/reserve").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/discounts/calculate").permitAll()

                        // Tất cả các endpoints còn lại — authenticated
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}
