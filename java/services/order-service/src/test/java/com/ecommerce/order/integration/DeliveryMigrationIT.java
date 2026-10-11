package com.ecommerce.order.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Upgrade a real V1 database, rather than testing V2 only on an empty schema. */
class DeliveryMigrationIT {

    @Test
    void historicalDeliveriesSurviveUpgradeWithoutWeakeningNewConfirmations() {
        String externalUrl = System.getenv("TEST_DB_URL");
        try (PostgreSQLContainer<?> postgres = externalUrl == null
                ? new PostgreSQLContainer<>("postgres:16-alpine") : null) {
            if (postgres != null) postgres.start();
            String url = postgres != null ? postgres.getJdbcUrl() : externalUrl;
            String user = postgres != null ? postgres.getUsername()
                    : System.getenv().getOrDefault("DB_USERNAME", "postgres");
            String password = postgres != null ? postgres.getPassword()
                    : System.getenv().getOrDefault("DB_PASSWORD", "postgres");
            String schema = "delivery_migration_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            // Keep the shared extension outside the disposable, test-owned schema.
            admin.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public");
            admin.execute("CREATE SCHEMA " + schema);
            try {
                Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                        .target("1").load().migrate();
                String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
                JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(scopedUrl, user, password));
                jdbc.execute("""
                        INSERT INTO orders (id, order_code, user_id, checkout_token, pricing_hash, items_hash,
                            promotion_campaign_revision, total_amount_minor, total_discount_minor, final_amount_minor,
                            inventory_allocation_plan_id, status, payment_status, saga_phase, payment_deadline_at)
                        VALUES (1, 'MIGRATION', '11111111-1111-4111-8111-111111111111', 'migration', 'hash', 'hash',
                            0, 3, 0, 3, 'plan', 'COMPLETED', 'SUCCESS', 'DONE', NOW());
                        INSERT INTO order_items (id, order_id, warehouse_id, status, delivery_address_snapshot)
                        VALUES (1, 1, 'warehouse', 'DELIVERED', '{}');
                        """);
                insertLine(jdbc, 1, "DELIVERED", "delivery_proof_id", null, false);
                insertLine(jdbc, 2, "DELIVERED", "delivery_proof_id", "OLD-REFERENCE", false);
                insertLine(jdbc, 3, "DISPATCHED", "delivery_proof_id", null, false);
                Object oldTime = jdbc.queryForObject("SELECT delivered_at FROM order_item_lines WHERE id=1", Object.class);

                Flyway flyway = Flyway.configure().dataSource(url, user, password).schemas(schema)
                        .defaultSchema(schema).load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                flyway.validate();
                assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=1", String.class)).isEqualTo("COMPLETED");
                assertThat(jdbc.queryForObject("SELECT delivered_at FROM order_item_lines WHERE id=1", Object.class)).isEqualTo(oldTime);
                assertThat(jdbc.queryForObject("SELECT delivery_reference FROM order_item_lines WHERE id=1", String.class)).isNull();
                assertThat(jdbc.queryForObject("SELECT delivery_reference FROM order_item_lines WHERE id=2", String.class)).isEqualTo("OLD-REFERENCE");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item_lines WHERE delivery_confirmation_legacy", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item_lines WHERE delivery_confirmed_by IS NOT NULL", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();

                // The historical exception cannot be opted into after migration.
                rejects(() -> jdbc.update("UPDATE order_item_lines SET delivery_confirmation_legacy=TRUE WHERE id=3"));
                rejects(() -> jdbc.update("UPDATE order_item_lines SET delivery_confirmation_legacy=FALSE WHERE id=1"));
                rejects(() -> insertLine(jdbc, 4, "DELIVERED", "delivery_reference", null, true));
                rejects(() -> jdbc.update("UPDATE order_item_lines SET delivered_at=NULL WHERE id=1"));

                // Future lines default to non-legacy and need all three Admin record fields.
                insertLine(jdbc, 4, "DISPATCHED", "delivery_reference", null, false);
                assertThat(jdbc.queryForObject("SELECT delivery_confirmation_legacy FROM order_item_lines WHERE id=4", Boolean.class)).isFalse();
                rejects(() -> jdbc.update("UPDATE order_item_lines SET fulfillment_status='DELIVERED', delivered_at=NOW() WHERE id=4"));
                rejects(() -> jdbc.update("UPDATE order_item_lines SET fulfillment_status='DELIVERED', delivered_at=NOW(), delivery_reference='NEW' WHERE id=4"));
                assertThat(jdbc.update("""
                        UPDATE order_item_lines SET fulfillment_status='DELIVERED', delivered_at=NOW(),
                            delivery_reference='NEW', delivery_confirmed_by='99999999-9999-4999-8999-999999999999'
                        WHERE id=4
                        """)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conrelid='order_item_lines'::regclass AND conname='ck_order_item_lines_delivery_record'", Boolean.class)).isTrue();
            } finally {
                // Only the randomly named schema created by this test is removed.
                admin.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void insertLine(JdbcTemplate jdbc, long id, String status, String referenceColumn,
                                   String reference, boolean legacy) {
        jdbc.update("""
                INSERT INTO order_item_lines (id, order_id, order_item_id, inventory_allocation_plan_id,
                    allocation_plan_line_reference, allocated_warehouse_id, sku_id, product_id, quantity,
                    unit_price_minor, discount_share_minor, net_amount_minor, fulfillment_status,
                    snapshot_name, snapshot_name_search, delivered_at,
                """ + referenceColumn + (legacy ? ", delivery_confirmation_legacy" : "") + """
                ) VALUES (?, 1, 1, 'plan', ?, 'warehouse', ?, 'product', 1, 1, 0, 1, ?, 'Product', 'product',
                    CASE WHEN ? = 'DELIVERED' THEN '2026-10-04T16:07:43.599121Z'::timestamptz ELSE NULL END, ?
                """ + (legacy ? ", TRUE" : "") + ")", id, "line-" + id, "sku-" + id, status, status, reference);
    }

    private static void rejects(Runnable mutation) {
        DataIntegrityViolationException failure = catchThrowableOfType(mutation::run, DataIntegrityViolationException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.getRootCause()).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure.getRootCause()).getSQLState()).isEqualTo("23514");
    }
}
