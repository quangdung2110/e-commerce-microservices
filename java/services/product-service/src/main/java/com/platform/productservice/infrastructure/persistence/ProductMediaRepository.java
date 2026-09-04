package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.ProductMediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductMediaRepository extends JpaRepository<ProductMediaEntity, UUID> {

    List<ProductMediaEntity> findByProductIdOrderBySortOrderAscMediaIdAsc(UUID productId);

    List<ProductMediaEntity> findByProductIdInOrderBySortOrderAscMediaIdAsc(Collection<UUID> productIds);
}
