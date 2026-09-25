package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.ProductMediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductMediaRepository extends JpaRepository<ProductMediaEntity, UUID> {

    List<ProductMediaEntity> findByProductIdOrderBySortOrderAscMediaIdAsc(UUID productId);

    List<ProductMediaEntity> findByProductIdInOrderBySortOrderAscMediaIdAsc(Collection<UUID> productIds);

    /**
     * Gallery của buyer-read (ADR-018): tối đa {@code productLimit} media cấp Product
     * và {@code skuLimit} media cho mỗi SKU {@code ACTIVE}, mỗi nhóm lấy theo
     * {@code sort_order} rồi {@code media_id}. Media của SKU {@code INACTIVE} không có
     * mặt. Thứ tự trả về là thứ tự của contract: media cấp Product trước, rồi theo
     * {@code sku_id}, {@code sort_order}, {@code media_id}.
     *
     * <p>Cắt trong database để một Product nhiều SKU không kéo hàng chục nghìn row về
     * chỉ để bỏ đi.
     */
    @Query(value = """
            SELECT media_id, product_id, sku_id, media_type, storage_key, alt_text,
                   sort_order, created_at, updated_at
            FROM (
                SELECT m.*,
                       ROW_NUMBER() OVER (PARTITION BY m.sku_id
                                          ORDER BY m.sort_order, m.media_id) AS position
                FROM product_media m
                LEFT JOIN product_skus s ON s.sku_id = m.sku_id
                WHERE m.product_id = :productId
                  AND (m.sku_id IS NULL OR s.status = 'ACTIVE')
            ) ranked
            WHERE position <= CASE WHEN sku_id IS NULL THEN :productLimit ELSE :skuLimit END
            ORDER BY sku_id NULLS FIRST, sort_order, media_id
            """, nativeQuery = true)
    List<ProductMediaEntity> findBuyerGallery(@Param("productId") UUID productId,
                                              @Param("productLimit") int productLimit,
                                              @Param("skuLimit") int skuLimit);

    /**
     * Ứng viên thumbnail: mọi {@code IMAGE} có {@code sort_order} nhỏ nhất trong từng
     * nhóm (cấp Product và từng SKU), kể cả khi hòa. Chạy rule ADR-013 trên tập này cho
     * cùng kết quả như trên toàn bộ media, nên thumbnail không phụ thuộc gallery đã cắt.
     */
    @Query(value = """
            SELECT media_id, product_id, sku_id, media_type, storage_key, alt_text,
                   sort_order, created_at, updated_at
            FROM (
                SELECT m.*,
                       RANK() OVER (PARTITION BY m.sku_id ORDER BY m.sort_order) AS position
                FROM product_media m
                WHERE m.product_id = :productId AND m.media_type = 'IMAGE'
            ) ranked
            WHERE position = 1
            """, nativeQuery = true)
    List<ProductMediaEntity> findThumbnailCandidates(@Param("productId") UUID productId);
}
