package com.platform.productservice.infrastructure.persistence.jpa.custom;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SkuJpaRepositoryImpl implements SkuJpaRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public int sumDisplayQuantityByProductId(String productId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<com.platform.productservice.infrastructure.persistence.entity.SkuJpaEntity> root =
                query.from(com.platform.productservice.infrastructure.persistence.entity.SkuJpaEntity.class);

        query.select(cb.sumAsLong(root.get("displayQuantity")));
        query.where(
                cb.equal(root.get("productId"), productId),
                cb.equal(root.get("isDeleted"), false)
        );

        Long result = em.createQuery(query).getSingleResult();
        return result != null ? result.intValue() : 0;
    }

    @Override
    public Map<String, Integer> sumDisplayQuantityByProductIds(List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return new HashMap<>();
        }

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<com.platform.productservice.infrastructure.persistence.entity.SkuJpaEntity> root =
                query.from(com.platform.productservice.infrastructure.persistence.entity.SkuJpaEntity.class);

        query.multiselect(root.get("productId"), cb.sumAsLong(root.get("displayQuantity")));
        query.groupBy(root.get("productId"));
        query.where(
                root.get("productId").in(productIds),
                cb.equal(root.get("isDeleted"), false)
        );

        List<Object[]> results = em.createQuery(query).getResultList();

        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : results) {
            String pid = (String) row[0];
            Long sum = (Long) row[1];
            map.put(pid, sum != null ? sum.intValue() : 0);
        }
        return map;
    }
}
