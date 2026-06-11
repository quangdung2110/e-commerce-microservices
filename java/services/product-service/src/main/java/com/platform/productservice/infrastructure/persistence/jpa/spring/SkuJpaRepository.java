package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.SkuJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface SkuJpaRepository extends JpaRepository<SkuJpaEntity, String> {

    Optional<SkuJpaEntity> findBySkuCode(String skuCode);

    boolean existsBySkuCode(String skuCode);

    List<SkuJpaEntity> findByProductId(String productId);

    List<SkuJpaEntity> findByProductIdAndDeletedFalse(String productId);

    List<SkuJpaEntity> findByIdIn(List<String> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SkuJpaEntity s WHERE s.skuId = :id")
    Optional<SkuJpaEntity> findWithLockById(@Param("id") String id);

    Optional<SkuJpaEntity> findByIdAndDeletedFalse(String id);

    @Query("SELECT s FROM SkuJpaEntity s WHERE s.productId IN :productIds AND s.isDeleted = false")
    List<SkuJpaEntity> findActiveByProductIds(@Param("productIds") List<String> productIds);

    @Query("SELECT SUM(s.displayQuantity) FROM SkuJpaEntity s WHERE s.productId = :productId AND s.isDeleted = false")
    Long sumDisplayQuantityByProductId(@Param("productId") String productId);
}
