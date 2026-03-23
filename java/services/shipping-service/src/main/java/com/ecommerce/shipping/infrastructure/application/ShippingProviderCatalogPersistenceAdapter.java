package com.ecommerce.shipping.infrastructure.application;

import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.domain.model.ShippingProvider;
import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.ShippingProviderEntity;
import com.ecommerce.shipping.infrastructure.persistence.postgres.repository.ShippingProviderJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

// Theo thiết kế shipping_db_design.md mục 1.1: truy vấn danh sách provider active từ bảng shipping_providers.
@Component
public class ShippingProviderCatalogPersistenceAdapter implements ShippingProviderCatalogPort {

    private final ShippingProviderJpaRepository shippingProviderJpaRepository;

    public ShippingProviderCatalogPersistenceAdapter(ShippingProviderJpaRepository shippingProviderJpaRepository) {
        this.shippingProviderJpaRepository = shippingProviderJpaRepository;
    }

    @Override
    public List<ShippingProvider> findActiveProviders() {
        return shippingProviderJpaRepository.findAllByActiveTrueOrderByPriorityAsc().stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ShippingProvider> findById(Integer providerId) {
        return shippingProviderJpaRepository.findById(providerId).map(this::toDomain);
    }

    @Override
    public Optional<ShippingProvider> findByCode(String providerCode) {
        return shippingProviderJpaRepository.findByCode(providerCode).map(this::toDomain);
    }

    private ShippingProvider toDomain(ShippingProviderEntity entity) {
        return new ShippingProvider(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                Boolean.TRUE.equals(entity.getActive()),
                entity.getPriority());
    }
}
