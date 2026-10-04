package com.ecommerce.inventory.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code warehouse}: Inventory-owned master data. */
@Repository
public class WarehouseStore {

    private static final String COLUMNS = "warehouse_id, name, address::text AS address, is_active, "
            + "operational_state, fulfillment_priority, revision";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Warehouse> mapper;

    public WarehouseStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = (rs, i) -> new Warehouse(rs.getString("warehouse_id"), rs.getString("name"),
                parse(rs.getString("address")), rs.getBoolean("is_active"), rs.getString("operational_state"),
                rs.getInt("fulfillment_priority"), rs.getLong("revision"));
    }

    public record Warehouse(String warehouseId, String name, JsonNode address, boolean active,
                            String operationalState, int fulfillmentPriority, long revision) {

        public boolean allocatable() {
            return active && "NORMAL".equals(operationalState);
        }
    }

    public void insert(Warehouse warehouse, Instant now) {
        jdbc.update("""
                INSERT INTO warehouse (warehouse_id, name, address, is_active, operational_state, fulfillment_priority,
                                       revision, created_at, updated_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, warehouse.warehouseId(), warehouse.name(), json(warehouse.address()), warehouse.active(),
                warehouse.operationalState(), warehouse.fulfillmentPriority(), warehouse.revision(),
                Timestamp.from(now), Timestamp.from(now));
    }

    public void update(Warehouse warehouse, Instant now) {
        jdbc.update("""
                UPDATE warehouse SET name = ?, address = ?::jsonb, is_active = ?, operational_state = ?,
                                     fulfillment_priority = ?, revision = ?, updated_at = ?
                WHERE warehouse_id = ?
                """, warehouse.name(), json(warehouse.address()), warehouse.active(), warehouse.operationalState(),
                warehouse.fulfillmentPriority(), warehouse.revision(), Timestamp.from(now), warehouse.warehouseId());
    }

    public Optional<Warehouse> find(String warehouseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM warehouse WHERE warehouse_id = ?", mapper, warehouseId)
                .stream().findFirst();
    }

    /** Configuration change: exclusive lock. */
    public Optional<Warehouse> lockForUpdate(String warehouseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM warehouse WHERE warehouse_id = ? FOR UPDATE", mapper,
                warehouseId).stream().findFirst();
    }

    /**
     * Allocation: shared lock, so a concurrent status change and Reserve serialize and the
     * first committed transaction decides (data.md §4).
     */
    public Optional<Warehouse> lockForShare(String warehouseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM warehouse WHERE warehouse_id = ? FOR SHARE", mapper,
                warehouseId).stream().findFirst();
    }

    public boolean exists(String warehouseId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM warehouse WHERE warehouse_id = ?", Integer.class,
                warehouseId);
        return n != null && n > 0;
    }

    public List<Warehouse> list(Boolean active, String afterWarehouseId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM warehouse WHERE true");
        List<Object> args = new ArrayList<>();
        if (active != null) {
            sql.append(" AND is_active = ?");
            args.add(active);
        }
        if (afterWarehouseId != null) {
            sql.append(" AND warehouse_id > ?");
            args.add(afterWarehouseId);
        }
        sql.append(" ORDER BY warehouse_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
