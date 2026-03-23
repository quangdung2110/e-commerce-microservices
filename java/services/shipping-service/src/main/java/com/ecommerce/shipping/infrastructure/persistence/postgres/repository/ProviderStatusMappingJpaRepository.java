package com.ecommerce.shipping.infrastructure.persistence.postgres.repository;

import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.ProviderStatusMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// Theo thiết kế shipping_state_machine.md mục 2: map provider status -> standardized status.
public interface ProviderStatusMappingJpaRepository extends JpaRepository<ProviderStatusMappingEntity, Long> {

    Optional<ProviderStatusMappingEntity> findByProviderIdAndProviderStatusCode(Integer providerId, String providerStatusCode);

    List<ProviderStatusMappingEntity> findByProviderId(Integer providerId);
}
