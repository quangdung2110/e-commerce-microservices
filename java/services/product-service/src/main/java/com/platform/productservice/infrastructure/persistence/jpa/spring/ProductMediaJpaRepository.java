package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.ProductMediaJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductMediaJpaRepository extends JpaRepository<ProductMediaJpaEntity, String> {

    List<ProductMediaJpaEntity> findByProductIdOrderBySortOrder(String productId);

    List<ProductMediaJpaEntity> findBySkuId(String skuId);

    void deleteByProductId(String productId);
}
