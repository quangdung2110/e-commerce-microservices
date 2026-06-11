package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.ProductJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;

public class ProductJpaRepositoryImpl implements ProductJpaRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Page<ProductJpaEntity> findByFilters(String categoryId, String status, String search, Boolean deleted, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ProductJpaEntity> query = cb.createQuery(ProductJpaEntity.class);
        Root<ProductJpaEntity> root = query.from(ProductJpaEntity.class);

        List<Predicate> predicates = new ArrayList<>();

        if (categoryId != null && !categoryId.isBlank()) {
            predicates.add(cb.equal(root.get("categoryId"), categoryId));
        }
        if (status != null && !status.isBlank()) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            Predicate nameSearch = cb.like(cb.lower(root.get("name")), pattern);
            Predicate descSearch = cb.like(cb.lower(root.get("description")), pattern);
            predicates.add(cb.or(nameSearch, descSearch));
        }
        if (deleted != null) {
            predicates.add(cb.equal(root.get("isDeleted"), deleted));
        }

        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(cb.desc(root.get("updatedAt")));

        TypedQuery<ProductJpaEntity> typedQuery = em.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());

        List<ProductJpaEntity> results = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<ProductJpaEntity> countRoot = countQuery.from(ProductJpaEntity.class);
        countQuery.select(cb.count(countRoot));
        if (!predicates.isEmpty()) {
            countQuery.where(predicates.toArray(new Predicate[0]));
        }
        Long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(results, pageable, total);
    }

    @Override
    public List<ProductJpaEntity> findByCategoryIdAndStatusIn(String categoryId, List<String> statuses) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ProductJpaEntity> query = cb.createQuery(ProductJpaEntity.class);
        Root<ProductJpaEntity> root = query.from(ProductJpaEntity.class);

        Predicate categoryMatch = cb.equal(root.get("categoryId"), categoryId);
        Predicate statusMatch = root.get("status").in(statuses);

        query.where(categoryMatch, statusMatch);
        query.orderBy(cb.asc(root.get("updatedAt")));

        return em.createQuery(query).getResultList();
    }
}
