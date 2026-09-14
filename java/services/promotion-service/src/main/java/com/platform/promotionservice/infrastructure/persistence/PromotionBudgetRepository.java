package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.PromotionBudgetEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PromotionBudgetRepository extends JpaRepository<PromotionBudgetEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PromotionBudgetEntity b where b.promotionId = :id")
    Optional<PromotionBudgetEntity> lockById(@Param("id") long id);
}
