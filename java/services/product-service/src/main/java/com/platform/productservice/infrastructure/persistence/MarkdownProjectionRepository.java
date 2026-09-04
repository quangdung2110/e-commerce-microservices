package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.MarkdownProjectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarkdownProjectionRepository extends JpaRepository<MarkdownProjectionEntity, UUID> {

    Optional<MarkdownProjectionEntity> findByPromotionRuleId(long promotionRuleId);

    List<MarkdownProjectionEntity> findByProductIdIn(Collection<UUID> productIds);
}
