package com.platform.inventoryservice.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("migration-test")
@Testcontainers
class FlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("inventory_service_migration_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Container
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7.2-alpine"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "warehouses",
            "physical_inventory",
            "inventory_reservations",
            "inventory_ledger",
            "inventory_outbox"
    );

    @Test
    @DisplayName("V1 and V3 are applied; V2 is intentionally NOT applied in this profile")
    void appliedMigrations() {
        MigrationInfo[] applied = flyway.info().applied();
        Set<String> versions = Arrays.stream(applied)
                .map(m -> m.getVersion().getVersion())
                .collect(Collectors.toSet());

        assertThat(versions).contains("1", "3");
        assertThat(versions).doesNotContain("2");
    }

    @Test
    @DisplayName("All 5 inventory tables exist in public schema")
    void allTablesCreated() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class
        );

        assertThat(tables).containsAll(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("available_quantity is a GENERATED ALWAYS AS STORED column")
    void availableQuantityIsGenerated() {
        String generationExpression = jdbc.queryForObject(
                "SELECT generation_expression FROM information_schema.columns " +
                        "WHERE table_name = 'physical_inventory' " +
                        "AND column_name = 'available_quantity'",
                String.class
        );

        assertThat(generationExpression).isNotNull();
        assertThat(generationExpression.replace(" ", ""))
                .isEqualTo("(actual_quantity-reserved_quantity)");
    }

    @Test
    @DisplayName("CHECK constraint rejects negative actual_quantity")
    void checkActualNonNegative() {
        long warehouseId = insertWarehouse();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO physical_inventory " +
                        "(sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity) " +
                        "VALUES (?, ?, ?, ?, 0)",
                "SKU-NEG", "PROD-NEG", warehouseId, -1
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK constraint rejects reserved_quantity > actual_quantity")
    void checkActualGteReserved() {
        long warehouseId = insertWarehouse();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO physical_inventory " +
                        "(sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity) " +
                        "VALUES (?, ?, ?, ?, ?)",
                "SKU-INV", "PROD-INV", warehouseId, 5, 10
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Partial unique uq_inventory_reservations_active prevents duplicate RESERVED row")
    void partialUniqueReservations() {
        long warehouseId = insertWarehouse();
        UUID routePlanId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO inventory_reservations " +
                        "(order_id, order_item_id, sku_id, product_id, warehouse_id, " +
                        " quantity, route_plan_id, status, expires_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', NOW() + INTERVAL '30 minutes')",
                1001L, 2001L, "SKU-DUP", "PROD-DUP", warehouseId, 1, routePlanId
        );

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inventory_reservations " +
                        "(order_id, order_item_id, sku_id, product_id, warehouse_id, " +
                        " quantity, route_plan_id, status, expires_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', NOW() + INTERVAL '30 minutes')",
                1001L, 2002L, "SKU-DUP", "PROD-DUP", warehouseId, 1, routePlanId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Partial unique uq_inventory_outbox_return_received prevents duplicate signal")
    void partialUniqueOutboxReturnReceived() {
        UUID orderId = UUID.randomUUID();
        String refundRequestId = "RR-001";

        jdbc.update(
                "INSERT INTO inventory_outbox " +
                        "(event_id, event_type, schema_version, aggregate_type, aggregate_id, " +
                        " partition_key, refund_request_id, payload) " +
                        "VALUES (?, 'INVENTORY_RETURN_RECEIVED_ALL', 1, 'Inventory', ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), orderId.toString(), orderId.toString(), refundRequestId, "{}"
        );

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inventory_outbox " +
                        "(event_id, event_type, schema_version, aggregate_type, aggregate_id, " +
                        " partition_key, refund_request_id, payload) " +
                        "VALUES (?, 'INVENTORY_RETURN_RECEIVED_ALL', 1, 'Inventory', ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), orderId.toString(), orderId.toString(), refundRequestId, "{}"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK constraint rejects unknown outbox event_type")
    void checkOutboxEventType() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inventory_outbox " +
                        "(event_id, event_type, schema_version, aggregate_type, aggregate_id, " +
                        " partition_key, payload) " +
                        "VALUES (?, 'INVALID_TYPE', 1, 'Inventory', 'AGG-1', 'AGG-1', '{}'::jsonb)",
                UUID.randomUUID()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK constraint rejects aggregate_type other than 'Inventory'")
    void checkOutboxAggregateType() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inventory_outbox " +
                        "(event_id, event_type, schema_version, aggregate_type, aggregate_id, " +
                        " partition_key, payload) " +
                        "VALUES (?, 'INVENTORY_STOCK_CHANGED', 1, 'WrongAggregate', 'AGG-1', 'AGG-1', '{}'::jsonb)",
                UUID.randomUUID()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK constraint rejects unknown ledger reference_type")
    void checkLedgerReferenceType() {
        long warehouseId = insertWarehouse();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inventory_ledger " +
                        "(event_id, sku_id, product_id, warehouse_id, reference_type, " +
                        " quantity_change, balance_after, correlation_id) " +
                        "VALUES (?, 'SKU-X', 'PROD-X', ?, 'INVALID_REF', 1, 1, 'cid-1')",
                UUID.randomUUID(), warehouseId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("inventory_ledger.event_id UNIQUE prevents duplicate events")
    void ledgerEventIdUnique() {
        long warehouseId = insertWarehouse();
        UUID eventId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO inventory_ledger " +
                        "(event_id, sku_id, product_id, warehouse_id, reference_type, " +
                        " quantity_change, balance_after, correlation_id) " +
                        "VALUES (?, 'SKU-L', 'PROD-L', ?, 'ADMIN_RESTOCK', 5, 5, 'cid-1')",
                eventId, warehouseId
        );

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO inventory_ledger " +
                        "(event_id, sku_id, product_id, warehouse_id, reference_type, " +
                        " quantity_change, balance_after, correlation_id) " +
                        "VALUES (?, 'SKU-L', 'PROD-L', ?, 'ADMIN_RESTOCK', 5, 5, 'cid-2')",
                eventId, warehouseId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Partial indexes are created (idempotent re-read check)")
    void partialIndexesPresent() {
        List<String> indexDefs = jdbc.query(
                "SELECT indexdef FROM pg_indexes " +
                        "WHERE schemaname = 'public' AND tablename IN " +
                        "('warehouses', 'physical_inventory', 'inventory_reservations', 'inventory_outbox')",
                (ResultSet rs, int rowNum) -> rs.getString(1)
        );

        assertThat(indexDefs)
                .anyMatch(d -> d.contains("available_quantity > 0"))
                .anyMatch(d -> d.contains("status") && d.contains("'RESERVED'"))
                .anyMatch(d -> d.contains("event_type") && d.contains("INVENTORY_RETURN_RECEIVED_ALL"));
    }

    private long insertWarehouse() {
        String name = "WH-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO warehouses (name, city_code, is_active) VALUES (?, 'HCM', TRUE)",
                name
        );
        return jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE name = ?",
                Long.class,
                name
        );
    }
}
