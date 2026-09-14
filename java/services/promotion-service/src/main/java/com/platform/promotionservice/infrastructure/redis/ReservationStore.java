package com.platform.promotionservice.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reservation runtime hold (TRY/CONFIRM-Prepare/finalize/CANCEL) bằng Lua atomic
 * trên cùng hash slot. Không persist RESERVED; không applied marker (ADR-015).
 */
@Component
public class ReservationStore {

    public static final String STATE_RESERVED = "RESERVED";
    public static final String STATE_CONFIRMING = "CONFIRMING";

    private final StringRedisTemplate redis;
    private final LuaScripts scripts;
    private final ObjectMapper payloadMapper;

    public ReservationStore(StringRedisTemplate redis, LuaScripts scripts, ObjectMapper objectMapper) {
        this.redis = redis;
        this.scripts = scripts;
        this.payloadMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /** Payload JSON của reservation; field snake_case vì Lua đọc trực tiếp. */
    public record ReservationPayload(long orderId, long promotionId, UUID userId, long walletId,
                                     long amount, String state, long reservedAtMs, long expiresAtMs,
                                     String payloadHash) {
    }

    public enum ReserveCode { RESERVED, REPLAY, PAYLOAD_MISMATCH, BUDGET_EXHAUSTED, RUNTIME_MISSING }

    public enum PrepareCode { PREPARED, CONFIRMING, MISSING }

    public enum FinalizeCode { FINALIZED, MISSING, DRIFT }

    public enum CancelCode { RELEASED, NO_RESERVATION, CONFIRMING, DRIFT }

    public record ReserveResult(ReserveCode code, ReservationPayload payload) {
    }

    public record PrepareResult(PrepareCode code, ReservationPayload payload) {
    }

    public ReserveResult reserve(ReservationPayload payload, long ttlMs) {
        List<String> keys = List.of(
                PromotionRedisKeys.runtime(payload.promotionId()),
                PromotionRedisKeys.reservation(payload.promotionId(), payload.orderId()),
                PromotionRedisKeys.reservationIndex(payload.promotionId()));
        List<?> result = redis.execute(scripts.reserve(), keys,
                Long.toString(payload.orderId()), Long.toString(payload.amount()), payload.payloadHash(),
                write(payload), Long.toString(payload.expiresAtMs()), Long.toString(ttlMs));
        ReserveCode code = ReserveCode.valueOf(first(result));
        return new ReserveResult(code, parseAt(result, 1));
    }

    public PrepareResult prepare(long promotionId, long orderId, long newExpiresAtMs, long ttlMs) {
        List<String> keys = List.of(
                PromotionRedisKeys.reservation(promotionId, orderId),
                PromotionRedisKeys.reservationIndex(promotionId));
        List<?> result = redis.execute(scripts.confirmPrepare(), keys,
                Long.toString(orderId), Long.toString(newExpiresAtMs), Long.toString(ttlMs));
        return new PrepareResult(PrepareCode.valueOf(first(result)), parseAt(result, 1));
    }

    public FinalizeCode finalize(long promotionId, long orderId) {
        List<?> result = redis.execute(scripts.confirmFinalize(), runtimeKeys(promotionId, orderId),
                Long.toString(orderId));
        return FinalizeCode.valueOf(first(result));
    }

    public CancelCode cancel(long promotionId, long orderId, boolean force) {
        List<?> result = redis.execute(scripts.cancelRelease(), runtimeKeys(promotionId, orderId),
                Long.toString(orderId), force ? "1" : "0");
        return CancelCode.valueOf(first(result));
    }

    public Optional<ReservationPayload> read(long promotionId, long orderId) {
        String json = redis.opsForValue().get(PromotionRedisKeys.reservation(promotionId, orderId));
        return json == null ? Optional.empty() : Optional.of(parse(json));
    }

    public List<Long> dueOrders(long promotionId, long nowMs, int limit) {
        Set<String> members = redis.opsForZSet().rangeByScore(
                PromotionRedisKeys.reservationIndex(promotionId), Double.NEGATIVE_INFINITY, nowMs, 0, limit);
        return toIds(members);
    }

    public List<Long> indexedOrders(long promotionId) {
        Set<String> members = redis.opsForZSet().range(PromotionRedisKeys.reservationIndex(promotionId), 0, -1);
        return toIds(members);
    }

    public void removeIndex(long promotionId, long orderId) {
        redis.opsForZSet().remove(PromotionRedisKeys.reservationIndex(promotionId), Long.toString(orderId));
    }

    private List<String> runtimeKeys(long promotionId, long orderId) {
        return List.of(
                PromotionRedisKeys.runtime(promotionId),
                PromotionRedisKeys.reservation(promotionId, orderId),
                PromotionRedisKeys.reservationIndex(promotionId));
    }

    private static List<Long> toIds(Set<String> members) {
        List<Long> ids = new ArrayList<>();
        if (members != null) {
            members.forEach(member -> ids.add(Long.parseLong(member)));
        }
        return ids;
    }

    private static String first(List<?> result) {
        if (result == null || result.isEmpty() || result.get(0) == null) {
            throw new IllegalStateException("Lua reservation script returned no result");
        }
        return result.get(0).toString();
    }

    private ReservationPayload parseAt(List<?> result, int index) {
        if (result == null || result.size() <= index || result.get(index) == null) {
            return null;
        }
        return parse(result.get(index).toString());
    }

    private ReservationPayload parse(String json) {
        try {
            return payloadMapper.readValue(json, ReservationPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("reservation payload is not valid JSON", e);
        }
    }

    private String write(ReservationPayload payload) {
        try {
            return payloadMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize reservation payload", e);
        }
    }
}
