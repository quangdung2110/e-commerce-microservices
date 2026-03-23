package com.ecommerce.shipping.infrastructure.persistence.adapter;

import com.ecommerce.shipping.application.port.out.RateCardRepositoryPort;
import com.ecommerce.shipping.domain.model.RateCard;
import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.RateCardEntity;
import com.ecommerce.shipping.infrastructure.persistence.postgres.repository.RateCardJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;

// Theo thiết kế shipping_db_design.md mục 1.2: route fallback lookup cho bảng giá.
@Component
public class RateCardPersistenceAdapter implements RateCardRepositoryPort {

    private final RateCardJpaRepository rateCardJpaRepository;

    public RateCardPersistenceAdapter(RateCardJpaRepository rateCardJpaRepository) {
        this.rateCardJpaRepository = rateCardJpaRepository;
    }

    @Override
    public List<RateCard> findActiveRateCardsByRouteWithFallback(Integer providerId,
                                                                 String fromProvinceCode,
                                                                 String fromDistrictCode,
                                                                 String toProvinceCode,
                                                                 String toDistrictCode,
                                                                 String toWardCode) {
        return rateCardJpaRepository.findByRouteWithFallback(
                        providerId,
                        fromProvinceCode,
                        fromDistrictCode,
                        toProvinceCode,
                        toDistrictCode,
                        toWardCode)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private RateCard toDomain(RateCardEntity entity) {
        return new RateCard(
                entity.getId(),
                entity.getProvider() == null ? null : entity.getProvider().getId(),
                entity.getFromProvinceCode(),
                entity.getFromDistrictCode(),
                entity.getToProvinceCode(),
                entity.getToDistrictCode(),
                entity.getToWardCode(),
                entity.getBaseWeight(),
                entity.getBaseFee(),
                entity.getStepWeight(),
                entity.getStepFee(),
                entity.getEstimatedDays());
    }
}
