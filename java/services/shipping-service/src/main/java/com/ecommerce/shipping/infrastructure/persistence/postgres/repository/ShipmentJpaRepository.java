package com.ecommerce.shipping.infrastructure.persistence.postgres.repository;

import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.ShipmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Theo thiết kế shipping_db_design.md mục 2.1: repository cho shipments.
public interface ShipmentJpaRepository extends JpaRepository<ShipmentEntity, Long> {

    Optional<ShipmentEntity> findByTrackingNumber(String trackingNumber);

    Optional<ShipmentEntity> findByParcelId(Long parcelId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
	    update ShipmentEntity s
	       set s.standardizedStatus = :newStandardizedStatus,
		   s.providerStatus = :providerStatus,
		   s.updatedAt = :updatedAt,
		   s.version = s.version + 1
	     where s.id = :shipmentId
	       and s.version = :expectedVersion
	    """)
    int updateStatusWithOptimisticLock(@Param("shipmentId") Long shipmentId,
				       @Param("expectedVersion") Long expectedVersion,
				       @Param("providerStatus") String providerStatus,
				       @Param("newStandardizedStatus") String newStandardizedStatus,
				       @Param("updatedAt") OffsetDateTime updatedAt);

    @Query("""
	    select s from ShipmentEntity s
	     where s.standardizedStatus in :statuses
	       and s.updatedAt < :before
	     order by s.updatedAt asc
	    """)
    List<ShipmentEntity> findStaleShipments(@Param("statuses") Collection<String> statuses,
					    @Param("before") OffsetDateTime before);
}
