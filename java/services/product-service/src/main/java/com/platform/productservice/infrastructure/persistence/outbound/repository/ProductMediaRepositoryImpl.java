package com.platform.productservice.infrastructure.persistence.outbound.repository;

import com.platform.productservice.domain.entity.ProductMedia;
import com.platform.productservice.infrastructure.persistence.jpa.spring.ProductMediaJpaRepository;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.ProductMediaJpaMapper;
import com.platform.productservice.port.outbound.database.ProductMediaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class ProductMediaRepositoryImpl implements ProductMediaRepository {

    private final ProductMediaJpaRepository jpaRepository;
    private final ProductMediaJpaMapper mapper;

    public ProductMediaRepositoryImpl(ProductMediaJpaRepository jpaRepository, ProductMediaJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ProductMedia save(ProductMedia media) {
        var entity = mapper.toEntity(media);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<ProductMedia> findByProductId(String productId) {
        return jpaRepository.findByProductIdOrderBySortOrder(productId)
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<ProductMedia> findBySkuId(String skuId) {
        return jpaRepository.findBySkuId(skuId)
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteByProductId(String productId) {
        jpaRepository.deleteByProductId(productId);
    }

    @Override
    @Transactional
    public List<ProductMedia> saveAll(List<ProductMedia> mediaList) {
        var entities = mediaList.stream().map(mapper::toEntity).collect(Collectors.toList());
        return jpaRepository.saveAll(entities).stream().map(mapper::toDomain).collect(Collectors.toList());
    }
}
