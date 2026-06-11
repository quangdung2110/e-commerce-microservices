package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.MarkdownAppliedJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarkdownAppliedJpaRepository extends JpaRepository<MarkdownAppliedJpaEntity, String> {

    List<MarkdownAppliedJpaEntity> findByRuleId(String ruleId);

    Optional<MarkdownAppliedJpaEntity> findByRuleIdAndSkuId(String ruleId, String skuId);

    List<MarkdownAppliedJpaEntity> findBySkuId(String skuId);

    List<MarkdownAppliedJpaEntity> findByProductId(String productId);

    @Query("SELECT m FROM MarkdownAppliedJpaEntity m WHERE m.expiresAt < :now")
    List<MarkdownAppliedJpaEntity> findExpiredMarkdowns(@Param("now") Instant now);

    void deleteByRuleId(String ruleId);
}
