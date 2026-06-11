package com.platform.productservice.infrastructure.persistence.outbound.repository;

import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuCode;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.persistence.jpa.spring.SkuJpaRepository;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.SkuJpaMapper;
import com.platform.productservice.port.outbound.SkuRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class SkuRepositoryImpl implements SkuRepository {

    private final SkuJpaRepository jpaRepository;
    private final SkuJpaMapper mapper;

    public SkuRepositoryImpl(SkuJpaRepository jpaRepository, SkuJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Sku save(Sku sku) {
        var entity = mapper.toEntity(sku);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<Sku> findById(SkuId id) {
        return jpaRepository.findById(id.getValue()).map(mapper::toDomain);
    }

    @Override
    public Optional<Sku> findBySkuCode(SkuCode skuCode) {
        return jpaRepository.findBySkuCode(skuCode.getValue()).map(mapper::toDomain);
    }

    @Override
    public List<Sku> findByProductId(ProductId productId) {
        return jpaRepository.findByProductId(productId.getValue())
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<Sku> findByIdForUpdate(SkuId id) {
        return jpaRepository.findWithLockById(id.getValue()).map(mapper::toDomain);
    }

    @Override
    public Optional<Sku> findByIdAndNotDeleted(SkuId id) {
        return jpaRepository.findByIdAndDeletedFalse(id.getValue()).map(mapper::toDomain);
    }

    @Override
    public List<Sku> findByIds(List<SkuId> ids) {
        List<String> idStrings = ids.stream().map(SkuId::getValue).collect(Collectors.toList());
        return jpaRepository.findByIdIn(idStrings).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public int sumDisplayQuantityByProductId(ProductId productId) {
        Long sum = jpaRepository.sumDisplayQuantityByProductId(productId.getValue());
        return sum != null ? sum.intValue() : 0;
    }

    @Override
    @Transactional
    public List<Sku> saveAll(List<Sku> skus) {
        var entities = skus.stream().map(mapper::toEntity).collect(Collectors.toList());
        return jpaRepository.saveAll(entities).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void delete(Sku sku) {
        jpaRepository.delete(mapper.toEntity(sku));
    }

    @Override
    public List<Sku> findActiveByProductIds(List<ProductId> productIds) {
        List<String> idStrings = productIds.stream().map(ProductId::getValue).collect(Collectors.toList());
        return jpaRepository.findActiveByProductIds(idStrings).stream().map(mapper::toDomain).collect(Collectors.toList());
    }
}
