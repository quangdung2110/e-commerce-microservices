package com.platform.productservice.port.outbound;

import com.platform.productservice.domain.entity.ProductMedia;

import java.util.List;

public interface ProductMediaRepository {

    ProductMedia save(ProductMedia media);

    List<ProductMedia> findByProductId(String productId);

    List<ProductMedia> findBySkuId(String skuId);

    void deleteByProductId(String productId);

    List<ProductMedia> saveAll(List<ProductMedia> media);
}
