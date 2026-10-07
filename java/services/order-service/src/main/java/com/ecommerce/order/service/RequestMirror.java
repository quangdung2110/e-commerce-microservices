package com.ecommerce.order.service;

import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Redis request replay mirror for the four public commands (ADR-012/017). Key
 * {@code order:req:{principal}:{action}:{sha256(key)}} stores only the canonical digest
 * and the durable operation reference. It never decides a transition; any Redis error
 * falls back to the durable operation record.
 */
@Component
public class RequestMirror {

    public record Entry(String digest, String operationId) {
    }

    private static final Logger log = LoggerFactory.getLogger(RequestMirror.class);

    private final ObjectProvider<StringRedisTemplate> redis;
    private final Json json;
    private final OrderProperties.RedisMirror config;
    private final MeterRegistry meters;

    public RequestMirror(ObjectProvider<StringRedisTemplate> redis, Json json, OrderProperties properties,
                         MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.config = properties.redisMirror();
        this.meters = meters;
    }

    public Optional<Entry> get(String principal, String action, String idempotencyKey) {
        StringRedisTemplate template = template();
        if (template == null) {
            return Optional.empty();
        }
        try {
            String value = template.opsForValue().get(key(principal, action, idempotencyKey));
            meters.counter("order.request_mirror", "action", action, "result", value == null ? "miss" : "hit").increment();
            if (value == null) {
                return Optional.empty();
            }
            JsonNode node = json.read(value);
            return Optional.of(new Entry(node.path("digest").asText(), node.path("operation_id").asText()));
        } catch (RuntimeException e) {
            meters.counter("order.request_mirror", "action", action, "result", "error").increment();
            log.debug("Redis mirror read failed; falling back to PostgreSQL: {}", e.toString());
            return Optional.empty();
        }
    }

    public void put(String principal, String action, String idempotencyKey, String digest, String operationId) {
        StringRedisTemplate template = template();
        if (template == null) {
            return;
        }
        try {
            String value = json.write(json.object().put("digest", digest).put("operation_id", operationId));
            template.opsForValue().setIfAbsent(key(principal, action, idempotencyKey), value, config.ttl());
        } catch (RuntimeException e) {
            meters.counter("order.request_mirror", "action", action, "result", "error").increment();
            log.debug("Redis mirror write failed: {}", e.toString());
        }
    }

    private StringRedisTemplate template() {
        return config.enabled() ? redis.getIfAvailable() : null;
    }

    static String key(String principal, String action, String idempotencyKey) {
        return "order:req:" + principal + ":" + action + ":" + CanonicalJson.sha256Hex(idempotencyKey);
    }
}
