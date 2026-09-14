package com.platform.promotionservice.infrastructure.redis;

import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code promo:campaign:schedule}: ZSET score = boundary epoch ms, member =
 * {campaign_id}:{revision}:{START|END}. Chỉ là trigger/index; PostgreSQL quyết định
 * transition (architecture.md §6). Không destructive pop, không schedule lock.
 */
@Component
@RequiredArgsConstructor
public class ScheduleIndex {

    private final StringRedisTemplate redis;

    public record Member(long campaignId, long revision, ScheduleTrigger trigger, String raw) {

        public static Member parse(String raw) {
            String[] parts = raw.split(":");
            if (parts.length != 3) {
                return null;
            }
            try {
                return new Member(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                        ScheduleTrigger.valueOf(parts[2]), raw);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public void add(long campaignId, long revision, ScheduleTrigger trigger, long atMs) {
        redis.opsForZSet().add(PromotionRedisKeys.CAMPAIGN_SCHEDULE,
                PromotionRedisKeys.scheduleMember(campaignId, revision, trigger.name()), atMs);
    }

    public List<Member> due(long nowMs, int limit) {
        Set<String> raw = redis.opsForZSet().rangeByScore(PromotionRedisKeys.CAMPAIGN_SCHEDULE,
                Double.NEGATIVE_INFINITY, nowMs, 0, limit);
        List<Member> members = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                Member member = Member.parse(value);
                if (member == null) {
                    redis.opsForZSet().remove(PromotionRedisKeys.CAMPAIGN_SCHEDULE, value);
                } else {
                    members.add(member);
                }
            }
        }
        return members;
    }

    public void remove(String rawMember) {
        redis.opsForZSet().remove(PromotionRedisKeys.CAMPAIGN_SCHEDULE, rawMember);
    }

    public boolean contains(long campaignId, long revision, ScheduleTrigger trigger) {
        Double score = redis.opsForZSet().score(PromotionRedisKeys.CAMPAIGN_SCHEDULE,
                PromotionRedisKeys.scheduleMember(campaignId, revision, trigger.name()));
        return score != null;
    }
}
