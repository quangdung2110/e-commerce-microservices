package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {

    @Query(value = "SELECT * FROM categories WHERE category_id = :categoryId FOR NO KEY UPDATE", nativeQuery = true)
    Optional<CategoryEntity> lockById(@Param("categoryId") UUID categoryId);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndCategoryIdNot(String slug, UUID categoryId);

    boolean existsByParentId(UUID parentId);

    List<CategoryEntity> findByParentIdOrderByNameAsc(UUID parentId);

    List<CategoryEntity> findByParentIdIsNullOrderByNameAsc();

    List<CategoryEntity> findAllByOrderByNameAsc();

    @Query(value = """
            SELECT * FROM categories
            WHERE category_id > CAST(:after AS uuid) AND category_id <= CAST(:bound AS uuid)
            ORDER BY category_id
            LIMIT :limit
            """, nativeQuery = true)
    List<CategoryEntity> findExportPage(@Param("after") String after,
                                        @Param("bound") String bound,
                                        @Param("limit") int limit);
}
