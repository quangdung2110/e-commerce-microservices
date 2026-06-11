package com.platform.productservice.infrastructure.persistence.jpa.custom;

import java.util.Map;

public interface SkuJpaRepositoryCustom {

    int sumDisplayQuantityByProductId(String productId);

    Map<String, Integer> sumDisplayQuantityByProductIds(java.util.List<String> productIds);
}
