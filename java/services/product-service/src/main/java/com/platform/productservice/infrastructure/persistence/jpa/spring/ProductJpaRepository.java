package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, String>, JpaSpecificationExecutor<ProductJpaEntity> {

    Optional<ProductJpaEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<ProductJpaEntity> findByCategoryId(String categoryId, Pageable pageable);

    Page<ProductJpaEntity> findByStatus(String status, Pageable pageable);

    List<ProductJpaEntity> findByCategoryIdAndStatus(String categoryId, String status);

    List<ProductJpaEntity> findByIdIn(List<String> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductJpaEntity p WHERE p.productId = :id")
    Optional<ProductJpaEntity> findWithLockById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT * FROM products WHERE product_id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<ProductJpaEntity> findByIdForNoKeyUpdate(@Param("id") String id);
}
