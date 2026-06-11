package com.platform.productservice.infrastructure.persistence.outbound.repository;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.Slug;
import com.platform.productservice.infrastructure.persistence.jpa.custom.ProductJpaRepositoryCustom;
import com.platform.productservice.infrastructure.persistence.jpa.spring.ProductJpaRepository;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.ProductJpaMapper;
import com.platform.productservice.port.outbound.database.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository jpaRepository;
    private final ProductJpaRepositoryCustom jpaRepositoryCustom;
    private final ProductJpaMapper mapper;

    public ProductRepositoryImpl(ProductJpaRepository jpaRepository,
                                ProductJpaRepositoryCustom jpaRepositoryCustom,
                                ProductJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.jpaRepositoryCustom = jpaRepositoryCustom;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Product save(Product product) {
        var entity = mapper.toEntity(product);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jpaRepository.findById(id.getValue()).map(mapper::toDomain);
    }

    @Override
    public Optional<Product> findBySlug(Slug slug) {
        return jpaRepository.findBySlug(slug.getValue()).map(mapper::toDomain);
    }

    @Override
    public boolean existsBySlug(Slug slug) {
        return jpaRepository.existsBySlug(slug.getValue());
    }

    @Override
    public Page<Product> findByCategoryId(CategoryId categoryId, Pageable pageable) {
        return jpaRepository.findByCategoryId(categoryId.getValue(), pageable)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Product> findByIdForUpdate(ProductId id) {
        return jpaRepository.findWithLockById(id.getValue()).map(mapper::toDomain);
    }

    @Override
    public Optional<Product> findByIdForNoKeyUpdate(ProductId id) {
        return jpaRepository.findByIdForNoKeyUpdate(id.getValue()).map(mapper::toDomain);
    }

    @Override
    public Page<Product> findByFilters(String categoryId, String status, String search, Boolean deleted, Pageable pageable) {
        return jpaRepositoryCustom.findByFilters(categoryId, status, search, deleted, pageable)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public void delete(Product product) {
        jpaRepository.delete(mapper.toEntity(product));
    }

    @Override
    public List<Product> findByIdIn(List<ProductId> ids) {
        List<String> idStrings = ids.stream().map(ProductId::getValue).collect(Collectors.toList());
        return jpaRepository.findByIdIn(idStrings).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Product> findByCategoryIdAndStatusIn(String categoryId, List<String> statuses) {
        return jpaRepositoryCustom.findByCategoryIdAndStatusIn(categoryId, statuses)
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }
}
