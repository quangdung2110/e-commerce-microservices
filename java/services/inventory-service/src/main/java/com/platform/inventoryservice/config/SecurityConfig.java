package com.platform.inventoryservice.config;

import com.platform.inventoryservice.security.InternalServiceTokenValidator;
import com.platform.inventoryservice.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final InternalServiceTokenValidator internalValidator;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/inventory/routing-options")
                    .hasAnyAuthority("SCOPE_inventory:routing", "SCOPE_inventory:read")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/restock")
                    .hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/ship-to-customer")
                    .hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/returns/refund-expectations")
                    .hasAuthority("SCOPE_inventory:internal")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/admin/unlock-sku")
                    .hasAuthority("ROLE_ADMIN")
                .anyRequest().denyAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(internalValidator, JwtAuthFilter.class);
        return http.build();
    }
}