package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.MarkdownRule;
import com.ecommerce.promotion.domain.enums.MarkdownRuleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;

/**
 * Repository cho MarkdownRule (Giảm giá Gạch chéo).
 * Theo thiết kế promotion_db_design.md - Section 2.1
 */
@Repository
public interface MarkdownRuleRepository extends JpaRepository<MarkdownRule, Long> {

    /**
     * Lấy tất cả active rules — dùng cho Redis Cache markdown:active_rules.
     * Theo thiết kế redis_caching_design.md - Section 2.6
     */
    List<MarkdownRule> findByStatus(MarkdownRuleStatus status);

    /**
     * Quét markdown_rules đã hết hạn — dùng cho Markdown Expiry Scanner CronJob.
     * Theo thiết kế promotion_workers.md - Section 3.4:
     *   SELECT ... FROM markdown_rules WHERE status = 'ACTIVE' AND end_time <= NOW()
     *   LIMIT 100 FOR UPDATE SKIP LOCKED
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT mr FROM MarkdownRule mr WHERE mr.status = 'ACTIVE' AND mr.endTime <= :now")
    List<MarkdownRule> findExpiredActiveRulesForUpdate(@Param("now") Instant now,
                                                       org.springframework.data.domain.Pageable pageable);
}
