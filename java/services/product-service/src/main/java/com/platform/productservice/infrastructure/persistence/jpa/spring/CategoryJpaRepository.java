package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.CategoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryJpaRepository extends JpaRepository<CategoryJpaEntity, String> {

    Optional<CategoryJpaEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<CategoryJpaEntity> findByParentIdIsNull();

    List<CategoryJpaEntity> findByParentId(String parentId);

    boolean existsByParentId(String parentId);

    @Query(value = "WITH RECURSIVE descendants AS (" +
            "  SELECT category_id, parent_id, name, slug, level, is_leaf, created_at, updated_at FROM categories WHERE category_id = :categoryId " +
            "  UNION ALL " +
            "  SELECT c.category_id, c.parent_id, c.name, c.slug, c.level, c.is_leaf, c.created_at, c.updated_at " +
            "  FROM categories c INNER JOIN descendants d ON c.parent_id = d.category_id" +
            ") SELECT * FROM descendants WHERE category_id != :categoryId", nativeQuery = true)
    List<CategoryJpaEntity> findAllDescendants(@Param("categoryId") String categoryId);

    @Query(value = "WITH RECURSIVE descendant_ids AS (" +
            "  SELECT category_id FROM categories WHERE category_id = :categoryId " +
            "  UNION ALL " +
            "  SELECT c.category_id FROM categories c INNER JOIN descendant_ids d ON c.parent_id = d.category_id" +
            ") SELECT category_id::text FROM descendant_ids WHERE category_id != :categoryId", nativeQuery = true)
    List<String> findAllDescendantIds(@Param("categoryId") String categoryId);

    @Query(value = "WITH RECURSIVE path AS (" +
            "  SELECT category_id, parent_id, name, slug, level, is_leaf, created_at, updated_at, 1 as depth FROM categories WHERE category_id = :categoryId " +
            "  UNION ALL " +
            "  SELECT c.category_id, c.parent_id, c.name, c.slug, c.level, c.is_leaf, c.created_at, c.updated_at, p.depth + 1 " +
            "  FROM categories c INNER JOIN path p ON c.category_id = p.parent_id" +
            ") SELECT * FROM path ORDER BY depth DESC", nativeQuery = true)
    List<CategoryJpaEntity> findRootToLeafPath(@Param("categoryId") String categoryId);
}
