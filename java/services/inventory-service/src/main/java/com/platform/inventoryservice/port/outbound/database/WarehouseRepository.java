package com.platform.inventoryservice.port.outbound.database;

import com.platform.inventoryservice.domain.entity.Warehouse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WarehouseRepository {
    Optional<Warehouse> findById(long id);
    List<Warehouse> findActiveByCityCode(String cityCode);
    List<Warehouse> findByIds(Collection<Long> ids);
}
