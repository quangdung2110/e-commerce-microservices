package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ProductJpaRepositoryCustom {

    Page<ProductJpaEntity> findByFilters(String categoryId, String status, String search, Boolean deleted, Pageable pageable);

    List<ProductJpaEntity> findByCategoryIdAndStatusIn(String categoryId, List<String> statuses);
}
