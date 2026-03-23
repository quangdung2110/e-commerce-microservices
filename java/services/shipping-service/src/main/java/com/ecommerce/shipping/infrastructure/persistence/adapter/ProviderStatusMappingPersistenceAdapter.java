package com.ecommerce.shipping.infrastructure.persistence.adapter;

import com.ecommerce.shipping.application.port.out.ProviderStatusMappingRepositoryPort;
import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.model.ProviderStatusMapping;
import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.ProviderStatusMappingEntity;
import com.ecommerce.shipping.infrastructure.persistence.postgres.repository.ProviderStatusMappingJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

// Theo thiết kế shipping_state_machine.md mục 2: chuẩn hóa trạng thái provider qua table mappings.
@Component
public class ProviderStatusMappingPersistenceAdapter implements ProviderStatusMappingRepositoryPort {

    private final ProviderStatusMappingJpaRepository providerStatusMappingJpaRepository;

    public ProviderStatusMappingPersistenceAdapter(ProviderStatusMappingJpaRepository providerStatusMappingJpaRepository) {
        this.providerStatusMappingJpaRepository = providerStatusMappingJpaRepository;
    }

    @Override
    public Optional<ProviderStatusMapping> findByProviderAndStatusCode(Integer providerId, String providerStatusCode) {
        return providerStatusMappingJpaRepository
                .findByProviderIdAndProviderStatusCode(providerId, providerStatusCode)
                .map(this::toDomain);
    }

    @Override
    public List<ProviderStatusMapping> findByProvider(Integer providerId) {
        return providerStatusMappingJpaRepository.findByProviderId(providerId).stream().map(this::toDomain).toList();
    }

    private ProviderStatusMapping toDomain(ProviderStatusMappingEntity entity) {
        return new ProviderStatusMapping(
                entity.getProvider() == null ? null : entity.getProvider().getId(),
                entity.getProviderStatusCode(),
                ShipmentStatus.valueOf(entity.getStandardizedStatus()));
    }
}
