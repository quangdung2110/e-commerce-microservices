package com.platform.productservice.port.outbound.database;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.Slug;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(ProductId id);

    Optional<Product> findBySlug(Slug slug);

    boolean existsBySlug(Slug slug);

    Page<Product> findByCategoryId(CategoryId categoryId, Pageable pageable);

    Optional<Product> findByIdForUpdate(ProductId id);

    Optional<Product> findByIdForNoKeyUpdate(ProductId id);

    Page<Product> findByFilters(String categoryId, String status, String search, Boolean deleted, Pageable pageable);

    void delete(Product product);

    List<Product> findByIdIn(List<ProductId> ids);

    List<Product> findByCategoryIdAndStatusIn(String categoryId, List<String> statuses);
}
