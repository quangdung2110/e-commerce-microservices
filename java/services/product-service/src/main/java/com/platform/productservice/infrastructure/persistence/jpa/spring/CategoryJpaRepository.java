package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.CategoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
