package com.platform.productservice.infrastructure.persistence.jpa.spring;

import java.util.List;
import java.util.Map;

public interface SkuJpaRepositoryCustom {

    int sumDisplayQuantityByProductId(String productId);

    Map<String, Integer> sumDisplayQuantityByProductIds(List<String> productIds);
}
