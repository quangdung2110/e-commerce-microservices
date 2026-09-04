package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {

    /**
     * Lock đầu tiên trong lock order Product then SKU (docs/product/architecture.md).
     * {@code FOR NO KEY UPDATE} cho phép SKU giữ FK tới Product mà không chặn nhau.
     */
    @Query(value = "SELECT * FROM products WHERE product_id = :productId FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<ProductEntity> lockById(@Param("productId") UUID productId);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndProductIdNot(String slug, UUID productId);

    boolean existsByCategoryId(UUID categoryId);

    List<ProductEntity> findByCategoryId(UUID categoryId);

    /** Trang admin theo cursor; tham số null truyền dạng chuỗi để native cast xử lý. */
    @Query(value = """
            SELECT * FROM products
            WHERE (CAST(:categoryId AS uuid) IS NULL OR category_id = CAST(:categoryId AS uuid))
              AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
              AND (CAST(:cursor AS uuid) IS NULL OR product_id > CAST(:cursor AS uuid))
            ORDER BY product_id
            LIMIT :limit
            """, nativeQuery = true)
    List<ProductEntity> findPage(@Param("categoryId") String categoryId,
                                 @Param("status") String status,
                                 @Param("cursor") String cursor,
                                 @Param("limit") int limit);

    /**
     * Keyset export cho Search full reindex: bound được chụp ở trang đầu nên
     * Product tạo sau đó không làm export chạy vô hạn.
     */
    @Query(value = """
            SELECT * FROM products
            WHERE product_id > CAST(:after AS uuid) AND product_id <= CAST(:bound AS uuid)
            ORDER BY product_id
            LIMIT :limit
            """, nativeQuery = true)
    List<ProductEntity> findExportPage(@Param("after") String after,
                                       @Param("bound") String bound,
                                       @Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM products WHERE category_id = :categoryId AND NOT jsonb_exists(attributes_json, :code)",
            nativeQuery = true)
    long countInCategoryMissingAttribute(@Param("categoryId") UUID categoryId, @Param("code") String code);

    @Query(value = "SELECT COUNT(*) FROM products WHERE jsonb_exists(attributes_json, :code)", nativeQuery = true)
    long countUsingAttribute(@Param("code") String code);

    @Query(value = "SELECT COUNT(*) FROM products WHERE category_id = :categoryId AND jsonb_exists(attributes_json, :code)",
            nativeQuery = true)
    long countInCategoryUsingAttribute(@Param("categoryId") UUID categoryId, @Param("code") String code);

    @Query(value = "SELECT COUNT(*) FROM products WHERE attributes_json ->> :code = :value", nativeQuery = true)
    long countUsingAttributeValue(@Param("code") String code, @Param("value") String value);
}
