package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.CategoryJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.List;

public class CategoryJpaRepositoryImpl implements CategoryJpaRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    private static final String FIND_DESCENDANTS_CTE =
            """
            WITH RECURSIVE category_tree AS (
                SELECT category_id, parent_id, name, slug, level, is_leaf, created_at, updated_at, 1 as depth
                FROM categories
                WHERE category_id = :categoryId
                UNION ALL
                SELECT c.category_id, c.parent_id, c.name, c.slug, c.level, c.is_leaf, c.created_at, c.updated_at, ct.depth + 1
                FROM categories c
                INNER JOIN category_tree ct ON c.parent_id = ct.category_id
            )
            SELECT category_id, parent_id, name, slug, level, is_leaf, created_at, updated_at
            FROM category_tree
            WHERE category_id != :categoryId
            ORDER BY depth, name
            """;

    private static final String FIND_DESCENDANT_IDS_CTE =
            """
            WITH RECURSIVE category_tree AS (
                SELECT category_id
                FROM categories
                WHERE category_id = :categoryId
                UNION ALL
                SELECT c.category_id
                FROM categories c
                INNER JOIN category_tree ct ON c.parent_id = ct.category_id
            )
            SELECT category_id FROM category_tree WHERE category_id != :categoryId
            """;

    private static final String FIND_PATH_CTE =
            """
            WITH RECURSIVE category_path AS (
                SELECT category_id, parent_id, name, slug, level, is_leaf, created_at, updated_at, 1 as depth
                FROM categories
                WHERE category_id = :categoryId
                UNION ALL
                SELECT c.category_id, c.parent_id, c.name, c.slug, c.level, c.is_leaf, c.created_at, c.updated_at, cp.depth + 1
                FROM categories c
                INNER JOIN category_path cp ON c.category_id = cp.parent_id
            )
            SELECT category_id, parent_id, name, slug, level, is_leaf, created_at, updated_at
            FROM category_path
            ORDER BY depth DESC
            """;

    @Override
    public List<CategoryJpaEntity> findAllDescendants(String categoryId) {
        List<Object[]> rows = em.createNativeQuery(FIND_DESCENDANTS_CTE)
                .setParameter("categoryId", categoryId)
                .getResultList();
        return mapToEntities(rows);
    }

    @Override
    public List<String> findAllDescendantIds(String categoryId) {
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery(FIND_DESCENDANT_IDS_CTE)
                .setParameter("categoryId", categoryId)
                .getResultList();
        return ids;
    }

    @Override
    public List<CategoryJpaEntity> findRootToLeafPath(String categoryId) {
        List<Object[]> rows = em.createNativeQuery(FIND_PATH_CTE)
                .setParameter("categoryId", categoryId)
                .getResultList();
        return mapToEntities(rows);
    }

    private List<CategoryJpaEntity> mapToEntities(List<Object[]> rows) {
        List<CategoryJpaEntity> entities = new ArrayList<>();
        for (Object[] row : rows) {
            CategoryJpaEntity e = new CategoryJpaEntity();
            e.setCategoryId((String) row[0]);
            e.setParentId((String) row[1]);
            e.setName((String) row[2]);
            e.setSlug((String) row[3]);
            e.setLevel(((Number) row[4]).intValue());
            e.setIsLeaf((Boolean) row[5]);
            e.setCreatedAt(((java.sql.Timestamp) row[6]).toLocalDateTime());
            e.setUpdatedAt(((java.sql.Timestamp) row[7]).toLocalDateTime());
            entities.add(e);
        }
        return entities;
    }
}
