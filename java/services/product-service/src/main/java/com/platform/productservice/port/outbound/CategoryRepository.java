package com.platform.productservice.port.outbound;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.Slug;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository {

    Category save(Category category);

    Optional<Category> findById(CategoryId id);

    Optional<Category> findBySlug(Slug slug);

    boolean existsBySlug(Slug slug);

    List<Category> findChildren(CategoryId parentId);

    List<Category> findRootCategories();

    List<Category> findAllDescendants(CategoryId categoryId);

    boolean hasChildren(CategoryId categoryId);

    void delete(Category category);

    List<String> findAllDescendantIds(CategoryId categoryId);

    List<Category> findRootToLeafPath(CategoryId categoryId);
}
