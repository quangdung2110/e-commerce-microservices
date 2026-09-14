package com.platform.promotionservice.infrastructure.persistence;

import com.platform.promotionservice.domain.entity.CatalogProductTombstoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CatalogProductTombstoneRepository extends JpaRepository<CatalogProductTombstoneEntity, UUID> {
}
