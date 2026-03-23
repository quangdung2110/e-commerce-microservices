package com.ecommerce.shipping.infrastructure.persistence.postgres.repository;

import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.RateCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// Theo thiết kế shipping_workflow.md mục 1: rate card lookup phục vụ calculate-batch.
public interface RateCardJpaRepository extends JpaRepository<RateCardEntity, Long> {

		@Query("""
						select rc from RateCardEntity rc
						 where rc.provider.id = :providerId
							 and rc.provider.active = true
							 and rc.fromProvinceCode = :fromProvinceCode
							 and (:fromDistrictCode is null or rc.fromDistrictCode = :fromDistrictCode or rc.fromDistrictCode is null)
							 and rc.toProvinceCode = :toProvinceCode
							 and (:toDistrictCode is null or rc.toDistrictCode = :toDistrictCode or rc.toDistrictCode is null)
							 and (:toWardCode is null or rc.toWardCode = :toWardCode or rc.toWardCode is null)
						 order by case when rc.toWardCode is not null then 3 when rc.toDistrictCode is not null then 2 else 1 end desc,
											case when rc.fromDistrictCode is not null then 1 else 0 end desc
						""")
		List<RateCardEntity> findByRouteWithFallback(@Param("providerId") Integer providerId,
																								 @Param("fromProvinceCode") String fromProvinceCode,
																								 @Param("fromDistrictCode") String fromDistrictCode,
																								 @Param("toProvinceCode") String toProvinceCode,
																								 @Param("toDistrictCode") String toDistrictCode,
																								 @Param("toWardCode") String toWardCode);
}
