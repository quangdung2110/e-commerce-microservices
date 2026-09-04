package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.SkuEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SkuRepository extends JpaRepository<SkuEntity, UUID> {

    List<SkuEntity> findByProductIdOrderBySkuCodeAsc(UUID productId);

    List<SkuEntity> findByProductIdInOrderBySkuCodeAsc(Collection<UUID> productIds);

    /**
     * Lock thứ hai trong lock order. {@code ORDER BY sku_id} giữ thứ tự lock ổn
     * định giữa các transaction chạm cùng Product.
     */
    @Query(value = "SELECT * FROM product_skus WHERE product_id = :productId ORDER BY sku_id FOR UPDATE",
            nativeQuery = true)
    List<SkuEntity> lockByProductId(@Param("productId") UUID productId);

    boolean existsBySkuCode(String skuCode);

    boolean existsBySkuCodeAndSkuIdNot(String skuCode, UUID skuId);

    long countByProductId(UUID productId);

    @Query(value = """
            SELECT COUNT(*) FROM product_skus s
            JOIN products p ON p.product_id = s.product_id
            WHERE p.category_id = :categoryId AND NOT jsonb_exists(s.variant_attributes_json, :code)
            """, nativeQuery = true)
    long countInCategoryMissingVariantAttribute(@Param("categoryId") UUID categoryId, @Param("code") String code);

    @Query(value = "SELECT COUNT(*) FROM product_skus WHERE jsonb_exists(variant_attributes_json, :code)",
            nativeQuery = true)
    long countUsingAttribute(@Param("code") String code);

    @Query(value = """
            SELECT COUNT(*) FROM product_skus s
            JOIN products p ON p.product_id = s.product_id
            WHERE p.category_id = :categoryId AND jsonb_exists(s.variant_attributes_json, :code)
            """, nativeQuery = true)
    long countInCategoryUsingVariantAttribute(@Param("categoryId") UUID categoryId, @Param("code") String code);

    @Query(value = "SELECT COUNT(*) FROM product_skus WHERE variant_attributes_json ->> :code = :value",
            nativeQuery = true)
    long countUsingAttributeValue(@Param("code") String code, @Param("value") String value);
}
