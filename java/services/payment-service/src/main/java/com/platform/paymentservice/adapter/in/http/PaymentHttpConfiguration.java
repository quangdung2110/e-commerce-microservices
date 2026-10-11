package com.platform.paymentservice.adapter.in.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentHttpConfiguration implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new PaymentOperationsContractInterceptor()).addPathPatterns("/internal/v1/payment/**");
    }
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictPaymentRequests() {
        return builder -> builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .postConfigurer(mapper -> mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                                com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                                com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
                                com.fasterxml.jackson.databind.cfg.CoercionAction.Fail));
    }
}
