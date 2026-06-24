package com.ecommerce.search.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final SearchProperties searchProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = searchProperties.getCors().getAllowedOrigins().split(",");

        registry.addMapping("/api/v1/search/**")
            .allowedOrigins(origins)
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("X-Request-Id");

        registry.addMapping("/internal/v1/search/**")
            .allowedOrigins(origins)
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("X-Request-Id");
    }
}
