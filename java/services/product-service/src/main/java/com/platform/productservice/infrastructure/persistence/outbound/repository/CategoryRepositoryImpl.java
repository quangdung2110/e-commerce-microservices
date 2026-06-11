package com.platform.productservice.infrastructure.persistence.outbound.repository;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.infrastructure.persistence.jpa.spring.CategoryJpaRepository;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.CategoryJpaMapper;
import com.platform.productservice.port.outbound.database.CategoryRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository jpaRepository;
    private final CategoryJpaMapper mapper;

    public CategoryRepositoryImpl(CategoryJpaRepository jpaRepository, CategoryJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Category save(Category category) {
        var entity = mapper.toEntity(category);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<Category> findById(CategoryId id) {
        return jpaRepository.findById(id.getValue()).map(mapper::toDomain);
    }

    @Override
    public Optional<Category> findBySlug(String slug) {
        return jpaRepository.findBySlug(slug).map(mapper::toDomain);
    }

    @Override
    public boolean existsBySlug(String slug) {
        return jpaRepository.existsBySlug(slug);
    }

    @Override
    public List<Category> findChildren(CategoryId parentId) {
        return jpaRepository.findByParentId(parentId.getValue())
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Category> findRootCategories() {
        return jpaRepository.findByParentIdIsNull()
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Category> findAllDescendants(CategoryId categoryId) {
        return jpaRepository.findAllDescendants(categoryId.getValue())
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public boolean hasChildren(CategoryId categoryId) {
        return jpaRepository.existsByParentId(categoryId.getValue());
    }

    @Override
    @Transactional
    public void delete(Category category) {
        jpaRepository.delete(mapper.toEntity(category));
    }

    @Override
    public List<String> findAllDescendantIds(CategoryId categoryId) {
        return jpaRepository.findAllDescendantIds(categoryId.getValue());
    }

    @Override
    public List<Category> findRootToLeafPath(CategoryId categoryId) {
        return jpaRepository.findRootToLeafPath(categoryId.getValue())
                .stream().map(mapper::toDomain).collect(Collectors.toList());
    }
}
