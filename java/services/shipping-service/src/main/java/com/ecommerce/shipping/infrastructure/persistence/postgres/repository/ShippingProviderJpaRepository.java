package com.ecommerce.shipping.infrastructure.persistence.postgres.repository;

import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.ShippingProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// Theo thiết kế shipping_db_design.md mục 1.1: lookup provider theo code khi gọi adapter.
public interface ShippingProviderJpaRepository extends JpaRepository<ShippingProviderEntity, Integer> {

    Optional<ShippingProviderEntity> findByCode(String code);
}
