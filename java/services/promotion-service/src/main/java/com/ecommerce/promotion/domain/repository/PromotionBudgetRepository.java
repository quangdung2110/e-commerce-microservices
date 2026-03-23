package com.ecommerce.promotion.domain.repository;

import com.ecommerce.promotion.domain.entity.PromotionBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Repository cho PromotionBudget (Ngân sách & Tồn kho).
 * Theo thiết kế promotion_db_design.md - Section 1.3
 *
 * Worker chỉ chạy UPDATE đơn giản vì Redis Lua Script là Gatekeeper.
 */
@Repository
public interface PromotionBudgetRepository extends JpaRepository<PromotionBudget, Long> {

    /**
     * Tăng used_quantity và used_budget (Commit Redeem).
     * Theo thiết kế promotion_workflow.md - Bước B (CONFIRM)
     */
    @Modifying
    @Query("UPDATE PromotionBudget pb SET pb.usedQuantity = pb.usedQuantity + :qty, " +
           "pb.usedBudget = pb.usedBudget + :amount WHERE pb.promotionId = :promoId")
    int incrementUsed(@Param("promoId") Long promotionId,
                      @Param("qty") int quantity,
                      @Param("amount") BigDecimal amount);

    /**
     * Giảm used_quantity và used_budget (Refund).
     * Theo thiết kế promotion_workflow.md - Luồng 5 (Refund Flow)
     */
    @Modifying
    @Query("UPDATE PromotionBudget pb SET pb.usedQuantity = pb.usedQuantity - :qty, " +
           "pb.usedBudget = pb.usedBudget - :amount WHERE pb.promotionId = :promoId")
    int decrementUsed(@Param("promoId") Long promotionId,
                      @Param("qty") int quantity,
                      @Param("amount") BigDecimal amount);
}
