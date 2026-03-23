package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.domain.model.RateCard;

import java.util.List;

// Theo thiết kế shipping_db_design.md mục 1.2: persistence rate cards.
public interface RateCardRepositoryPort {

	List<RateCard> findActiveRateCardsByRouteWithFallback(Integer providerId,
														  String fromProvinceCode,
														  String fromDistrictCode,
														  String toProvinceCode,
														  String toDistrictCode,
														  String toWardCode);
}
