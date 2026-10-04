package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.Accepted;
import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.store.AvailabilityStore;
import com.ecommerce.inventory.store.EvidenceStore;
import com.ecommerce.inventory.store.WarehouseStore;
import com.ecommerce.inventory.web.dto.AdminDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Warehouse master data (ADMIN). Status changes affect new allocation only: they never
 * release, reroute or rewrite existing commitments, and an eligibility change fans out a
 * durable per-SKU availability recomputation (ADR-016).
 */
@Service
public class WarehouseService {

    private static final String TARGET = "WAREHOUSE";

    private final WarehouseStore warehouses;
    private final AvailabilityStore availability;
    private final EvidenceStore evidence;
    private final ObjectMapper objectMapper;

    public WarehouseService(WarehouseStore warehouses, AvailabilityStore availability, EvidenceStore evidence,
                            ObjectMapper objectMapper) {
        this.warehouses = warehouses;
        this.availability = availability;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
    }

    public Accepted create(AdminDtos.CreateWarehouseRequest request, CommandContext context) {
        WarehouseStore.Warehouse warehouse = new WarehouseStore.Warehouse(Ids.newId(), request.name(),
                objectMapper.valueToTree(request.address()), request.isActive(), request.operationalState().name(),
                request.fulfillmentPriority(), 1);
        warehouses.insert(warehouse, context.now());
        evidence.audit(context, TARGET, warehouse.warehouseId(), request.reason(), request.evidenceReference(), null,
                view(warehouse));
        return result(warehouse, context);
    }

    public Accepted update(String warehouseId, AdminDtos.UpdateWarehouseRequest request, CommandContext context) {
        WarehouseStore.Warehouse current = warehouses.lockForUpdate(warehouseId)
                .orElseThrow(() -> new DomainRejection(ErrorCode.NOT_FOUND, "No warehouse exists with this identifier"));
        if (current.revision() != request.expectedRevision()) {
            throw new DomainRejection(ErrorCode.WAREHOUSE_CONFLICT,
                    "Warehouse revision is " + current.revision() + ", not " + request.expectedRevision());
        }
        WarehouseStore.Warehouse next = new WarehouseStore.Warehouse(warehouseId,
                request.name() != null ? request.name() : current.name(),
                request.address() != null ? objectMapper.valueToTree(request.address()) : current.address(),
                request.isActive() != null ? request.isActive() : current.active(),
                request.operationalState() != null ? request.operationalState().name() : current.operationalState(),
                request.fulfillmentPriority() != null ? request.fulfillmentPriority() : current.fulfillmentPriority(),
                current.revision() + 1);
        warehouses.update(next, context.now());
        if (next.allocatable() != current.allocatable()) {
            availability.createFanoutJob(warehouseId, next.revision(), context);
        }
        evidence.audit(context, TARGET, warehouseId, request.reason(), request.evidenceReference(), view(current),
                view(next));
        return result(next, context);
    }

    public static AdminDtos.WarehouseView view(WarehouseStore.Warehouse warehouse) {
        return new AdminDtos.WarehouseView(warehouse.warehouseId(), warehouse.name(), warehouse.address(),
                warehouse.active(), warehouse.operationalState(), warehouse.fulfillmentPriority(), warehouse.revision());
    }

    private static Accepted result(WarehouseStore.Warehouse warehouse, CommandContext context) {
        return new Accepted(new AdminDtos.WarehouseMutationResponse(context.commandId(), context.correlationId(),
                "ACCEPTED", view(warehouse), context.now(), false), "ACCEPTED", warehouse.warehouseId());
    }
}
