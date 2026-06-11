package com.platform.productservice.port.outbound.database;

import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuCode;
import com.platform.productservice.domain.vo.SkuId;

import java.util.List;
import java.util.Optional;

public interface SkuRepository {

    Sku save(Sku sku);

    Optional<Sku> findById(SkuId id);

    Optional<Sku> findBySkuCode(SkuCode skuCode);

    List<Sku> findByProductId(ProductId productId);

    Optional<Sku> findByIdForUpdate(SkuId id);

    Optional<Sku> findWithLockById(SkuId id);

    Optional<Sku> findByIdAndNotDeleted(SkuId id);

    List<Sku> findByIds(List<SkuId> ids);

    int sumDisplayQuantityByProductId(ProductId productId);

    List<Sku> saveAll(List<Sku> skus);

    void delete(Sku sku);

    List<Sku> findActiveByProductIds(List<ProductId> productIds);
}
