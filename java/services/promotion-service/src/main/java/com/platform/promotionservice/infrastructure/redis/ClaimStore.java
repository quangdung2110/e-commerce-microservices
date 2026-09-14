package com.platform.promotionservice.infrastructure.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Claim pool / claimed users / pending acquisition evidence. Lua acquisition là
 * linearization point; pending Hash/ZSET là recovery source của crash window
 * (interfaces.md §5). Pending chỉ bị xóa sau DB commit.
 */
@Component
@RequiredArgsConstructor
public class ClaimStore {

    private final StringRedisTemplate redis;
    private final LuaScripts scripts;
    private final ObjectMapper objectMapper;

    public enum AcquireCode { ACQUIRED, ALREADY_CLAIMED, OUT_OF_STOCK, GATE_MISSING, GATE_DISABLED, GATE_STALE, OUT_OF_WINDOW }

    /** Payload pending ghi atomically với SPOP: identity + acquisition time/revision. */
    public record PendingClaim(long claimId, UUID userId, long acquiredAtMs, long campaignRevision) {
    }

    public record AcquireResult(AcquireCode code, Long claimId, Long acquiredAtMs, Long gateRevision,
                                PendingClaim pending) {
    }

    public AcquireResult acquire(long promotionId, UUID userId, long expectedRevision, long staleAfterMs) {
        List<String> keys = List.of(
                PromotionRedisKeys.runtime(promotionId),
                PromotionRedisKeys.claimPool(promotionId),
                PromotionRedisKeys.claimedUsers(promotionId),
                PromotionRedisKeys.pendingClaimsData(promotionId),
                PromotionRedisKeys.pendingClaimsIndex(promotionId));
        List<?> result = redis.execute(scripts.claimAcquire(), keys,
                userId.toString(), Long.toString(expectedRevision), Long.toString(staleAfterMs));
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("claim_acquire returned no result");
        }
        AcquireCode code = AcquireCode.valueOf(result.get(0).toString());
        Long claimId = at(result, 1) == null || at(result, 1).isBlank() ? null : Long.parseLong(at(result, 1));
        Long acquiredAt = at(result, 2) == null || at(result, 2).isBlank() ? null : Long.parseLong(at(result, 2));
        Long revision = at(result, 3) == null || at(result, 3).isBlank() ? null : Long.parseLong(at(result, 3));
        PendingClaim pending = at(result, 4) == null || at(result, 4).isBlank() ? null : parse(at(result, 4));
        return new AcquireResult(code, claimId, acquiredAt, revision, pending);
    }

    public Optional<Long> claimedClaimId(long promotionId, UUID userId) {
        Object value = redis.opsForHash().get(PromotionRedisKeys.claimedUsers(promotionId), userId.toString());
        return value == null ? Optional.empty() : Optional.of(Long.parseLong(value.toString()));
    }

    public Optional<PendingClaim> pending(long promotionId, long claimId) {
        Object value = redis.opsForHash().get(PromotionRedisKeys.pendingClaimsData(promotionId), Long.toString(claimId));
        return value == null ? Optional.empty() : Optional.of(parse(value.toString()));
    }

    /** Sau DB commit: xóa pending evidence; giữ claimed_users để chặn claim lần hai. */
    public void ack(long promotionId, long claimId) {
        redis.opsForHash().delete(PromotionRedisKeys.pendingClaimsData(promotionId), Long.toString(claimId));
        redis.opsForZSet().remove(PromotionRedisKeys.pendingClaimsIndex(promotionId), Long.toString(claimId));
    }

    public List<Long> duePending(long promotionId, long nowMs, int limit) {
        Set<String> members = redis.opsForZSet().rangeByScore(
                PromotionRedisKeys.pendingClaimsIndex(promotionId), Double.NEGATIVE_INFINITY, nowMs, 0, limit);
        List<Long> ids = new ArrayList<>();
        if (members != null) {
            members.forEach(member -> ids.add(Long.parseLong(member)));
        }
        return ids;
    }

    public Set<Object> pendingClaimIds(long promotionId) {
        return redis.opsForHash().keys(PromotionRedisKeys.pendingClaimsData(promotionId));
    }

    /** Rebuild pool từ AVAILABLE claim rows trừ những suất đang pending. */
    public void resetPool(long promotionId) {
        redis.delete(PromotionRedisKeys.claimPool(promotionId));
    }

    public void addToPool(long promotionId, List<Long> claimIds) {
        if (claimIds.isEmpty()) {
            return;
        }
        String[] members = claimIds.stream().map(String::valueOf).toArray(String[]::new);
        redis.opsForSet().add(PromotionRedisKeys.claimPool(promotionId), members);
    }

    public long poolSize(long promotionId) {
        Long size = redis.opsForSet().size(PromotionRedisKeys.claimPool(promotionId));
        return size == null ? 0 : size;
    }

    public void rememberClaimedUser(long promotionId, UUID userId, long claimId) {
        redis.opsForHash().put(PromotionRedisKeys.claimedUsers(promotionId), userId.toString(), Long.toString(claimId));
    }

    private PendingClaim parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new PendingClaim(
                    node.path("claim_id").asLong(),
                    UUID.fromString(node.path("user_id").asText()),
                    node.path("acquired_at_ms").asLong(),
                    node.path("campaign_revision").asLong());
        } catch (Exception e) {
            throw new IllegalStateException("pending claim payload is not valid JSON", e);
        }
    }

    private static String at(List<?> result, int index) {
        return result.size() > index && result.get(index) != null ? result.get(index).toString() : null;
    }
}
