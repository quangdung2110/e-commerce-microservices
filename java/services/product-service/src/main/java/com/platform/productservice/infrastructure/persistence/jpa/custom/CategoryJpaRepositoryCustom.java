package com.platform.productservice.infrastructure.persistence.jpa.custom;

import com.platform.productservice.infrastructure.persistence.entity.CategoryJpaEntity;

import java.util.List;

public interface CategoryJpaRepositoryCustom {

    List<CategoryJpaEntity> findAllDescendants(String categoryId);

    List<String> findAllDescendantIds(String categoryId);

    List<CategoryJpaEntity> findRootToLeafPath(String categoryId);
}
