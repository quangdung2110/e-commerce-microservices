package com.ecommerce.review.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two mappers with deliberately different null handling and unknown-field handling.
 *
 * <p>The HTTP mapper must emit nulls: {@code next_cursor}, {@code content},
 * {@code variant_name}, {@code admin_reply}, {@code hidden_at} and
 * {@code next_after_product_id} are all required <em>and</em> nullable in the OpenAPI, so
 * a field that vanished would fail the contract rather than satisfy it.
 *
 * <p>The event mapper must omit them: the CloudEvents envelope carries
 * {@code sequence} and {@code correlationid} only when they apply, and every
 * {@code data} schema is {@code additionalProperties: false} with an explicit required
 * list, so a stray null attribute would fail validation of the stored payload.
 */
@Configuration
public class JacksonConfig {

    /**
     * Strict on input: every request body schema is {@code additionalProperties: false}, so
     * a field the contract does not define is answered 400 instead of being silently
     * dropped (the same rule PATCH already applies to its raw body).
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = base()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        return mapper;
    }

    /**
     * Tolerant on input: events from other services may gain optional fields, and a
     * consumer must keep working when they do.
     */
    @Bean("eventObjectMapper")
    public ObjectMapper eventObjectMapper() {
        return base().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static ObjectMapper base() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
