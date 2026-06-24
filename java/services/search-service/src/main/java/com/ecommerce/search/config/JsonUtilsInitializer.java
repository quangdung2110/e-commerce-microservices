package com.ecommerce.search.config;

import com.ecommerce.search.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Initialises the static {@link JsonUtils} helper with the application's primary
 * {@link ObjectMapper} bean.
 *
 * <p>This is a separate {@link Component} rather than a {@code @PostConstruct}
 * on {@link JacksonConfig} to avoid circular dependency issues between the
 * ObjectMapper, JwtClaimUtil, and MdcPopulationFilter beans.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonUtilsInitializer {

    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        JsonUtils.init(objectMapper);
        log.debug("JsonUtils initialized with ObjectMapper");
    }
}
