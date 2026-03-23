package com.ecommerce.shipping.infrastructure.persistence.adapter;

import com.ecommerce.shipping.application.port.out.ShipmentRepositoryPort;
import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.model.Shipment;
import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.ShipmentEntity;
import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.ShippingProviderEntity;
import com.ecommerce.shipping.infrastructure.persistence.postgres.repository.ShipmentJpaRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Theo thiết kế shipping_db_design.md mục 2.1 và shipping_state_machine.md mục 4.2: optimistic locking update.
@Component
public class ShipmentPersistenceAdapter implements ShipmentRepositoryPort {

    private final ShipmentJpaRepository shipmentJpaRepository;
    private final PersistenceJsonMapper persistenceJsonMapper;

    public ShipmentPersistenceAdapter(ShipmentJpaRepository shipmentJpaRepository,
                                      PersistenceJsonMapper persistenceJsonMapper) {
        this.shipmentJpaRepository = shipmentJpaRepository;
        this.persistenceJsonMapper = persistenceJsonMapper;
    }

    @Override
    public Shipment save(Shipment shipment) {
        ShipmentEntity entity = toEntity(shipment);
        ShipmentEntity saved = shipmentJpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<Shipment> findById(Long shipmentId) {
        return shipmentJpaRepository.findById(shipmentId).map(this::toDomain);
    }

    @Override
    public Optional<Shipment> findByTrackingNumber(String trackingNumber) {
        return shipmentJpaRepository.findByTrackingNumber(trackingNumber).map(this::toDomain);
    }

    @Override
    public Optional<Shipment> findByParcelId(Long parcelId) {
        return shipmentJpaRepository.findByParcelId(parcelId).map(this::toDomain);
    }

    @Override
    @Transactional
    public boolean updateStatusWithOptimisticLock(Long shipmentId,
                                                  long expectedVersion,
                                                  String providerStatus,
                                                  String standardizedStatus,
                                                  OffsetDateTime updatedAt) {
        int updated = shipmentJpaRepository.updateStatusWithOptimisticLock(
                shipmentId,
                expectedVersion,
                providerStatus,
                standardizedStatus,
                updatedAt);
        return updated > 0;
    }

    @Override
    public List<Shipment> findStaleShipments(OffsetDateTime before, int limit) {
        List<ShipmentEntity> stale = shipmentJpaRepository.findStaleShipments(
                List.of(ShipmentStatus.READY_TO_SHIP.name(), ShipmentStatus.IN_TRANSIT.name()),
                before);
        return stale.stream().limit(Math.max(limit, 0)).map(this::toDomain).toList();
    }

    private Shipment toDomain(ShipmentEntity entity) {
        return new Shipment(
                entity.getId(),
                entity.getParcelId(),
                entity.getOrderId(),
                entity.getProvider() == null ? null : entity.getProvider().getId(),
                entity.getTrackingNumber(),
                persistenceJsonMapper.readMap(entity.getSenderInfo()),
                persistenceJsonMapper.readMap(entity.getReceiverInfo()),
                persistenceJsonMapper.readMap(entity.getPackageInfo()),
                ShipmentStatus.valueOf(entity.getStandardizedStatus()),
                entity.getProviderStatus(),
                entity.getShippingFee(),
                entity.getCodAmount(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private ShipmentEntity toEntity(Shipment shipment) {
        ShipmentEntity entity = new ShipmentEntity();
        entity.setId(shipment.id());
        entity.setParcelId(shipment.parcelId());
        entity.setOrderId(shipment.orderId());
        if (shipment.providerId() != null) {
            ShippingProviderEntity provider = new ShippingProviderEntity();
            provider.setId(shipment.providerId());
            entity.setProvider(provider);
        }
        entity.setTrackingNumber(shipment.trackingNumber());
        entity.setSenderInfo(persistenceJsonMapper.writeMap(shipment.senderInfo()));
        entity.setReceiverInfo(persistenceJsonMapper.writeMap(shipment.receiverInfo()));
        entity.setPackageInfo(persistenceJsonMapper.writeMap(shipment.packageInfo()));
        entity.setStandardizedStatus(shipment.standardizedStatus() == null ? null : shipment.standardizedStatus().name());
        entity.setProviderStatus(shipment.providerStatus());
        entity.setShippingFee(shipment.shippingFee());
        entity.setCodAmount(shipment.codAmount());
        entity.setVersion(shipment.version());
        entity.setCreatedAt(shipment.createdAt());
        entity.setUpdatedAt(shipment.updatedAt());
        return entity;
    }
}
