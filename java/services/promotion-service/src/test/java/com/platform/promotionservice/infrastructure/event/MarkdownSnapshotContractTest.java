package com.platform.promotionservice.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.platform.promotionservice.config.PromotionProperties;
import com.platform.promotionservice.domain.entity.MarkdownRuleEntity;
import com.platform.promotionservice.domain.enums.DiscountType;
import com.platform.promotionservice.domain.enums.InactiveReason;
import com.platform.promotionservice.domain.enums.ScheduleTrigger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: CloudEvent markdown snapshot phải hợp lệ với JSON Schema trong
 * contracts/events/promotion/v1 cho cả ACTIVE (full rule) lẫn INACTIVE (tombstone).
 */
class MarkdownSnapshotContractTest {

    private static final String SCHEMA_ID_PREFIX = "https://contracts.ecommerce.local/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonSchema schema;
    private MarkdownSnapshotEvents events;

    @BeforeEach
    void setUp() {
        Path contracts = Paths.get("").toAbsolutePath().resolve("../../../contracts").normalize();
        Assumptions.assumeTrue(Files.isDirectory(contracts), "contracts/ chỉ có mặt khi test chạy trong repository");
        String contractsUri = contracts.toUri().toString();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaMappers(mappers -> mappers.mapPrefix(SCHEMA_ID_PREFIX, contractsUri)));
        schema = factory.getSchema(SchemaLocation.of(
                SCHEMA_ID_PREFIX + "events/promotion/v1/schemas/product-markdown-snapshot.schema.json"));
        events = new MarkdownSnapshotEvents(objectMapper, new PromotionProperties());
    }

    @Test
    @DisplayName("ACTIVE snapshot carries promotion_rule_id, discount, starts_at, ends_at and no inactive_reason")
    void activeSnapshotMatchesSchema() throws Exception {
        MarkdownRuleEntity winner = new MarkdownRuleEntity();
        winner.setId(42L);
        winner.setDiscountType(DiscountType.PERCENTAGE);
        winner.setDiscountValue(1000);
        winner.setMaxDiscountVnd(50000L);
        UUID productId = UUID.randomUUID();
        MarkdownSnapshotEvents.Snapshot snapshot = MarkdownSnapshotEvents.Snapshot.active(productId, 12, winner,
                Instant.parse("2026-09-02T09:30:00Z"), Instant.parse("2026-09-05T09:30:00Z"));
        JsonNode event = objectMapper.readTree(events.toCloudEventJson(UUID.randomUUID(), snapshot,
                Instant.parse("2026-09-02T09:30:00Z")));

        assertValid(event);
        assertThat(event.path("sequence").asLong()).isEqualTo(12);
        assertThat(event.path("data").path("promotion_rule_id").asLong()).isEqualTo(42);
        assertThat(event.path("data").path("discount").path("discount_bps").asInt()).isEqualTo(1000);
        assertThat(event.path("data").has("inactive_reason")).isFalse();
    }

    @Test
    @DisplayName("FIXED_VND discount has fixed_discount_vnd only")
    void fixedDiscountShape() throws Exception {
        MarkdownRuleEntity winner = new MarkdownRuleEntity();
        winner.setId(7L);
        winner.setDiscountType(DiscountType.FIXED_VND);
        winner.setDiscountValue(30000);
        MarkdownSnapshotEvents.Snapshot snapshot = MarkdownSnapshotEvents.Snapshot.active(UUID.randomUUID(), 3, winner,
                Instant.parse("2026-09-02T09:30:00Z"), Instant.parse("2026-09-05T09:30:00Z"));
        JsonNode event = objectMapper.readTree(events.toCloudEventJson(UUID.randomUUID(), snapshot, Instant.now()));
        assertValid(event);
        assertThat(event.path("data").path("discount").path("fixed_discount_vnd").asLong()).isEqualTo(30000);
        assertThat(event.path("data").path("discount").has("discount_bps")).isFalse();
    }

    @Test
    @DisplayName("INACTIVE tombstone carries inactive_reason only")
    void inactiveSnapshotMatchesSchema() throws Exception {
        MarkdownSnapshotEvents.Snapshot snapshot = MarkdownSnapshotEvents.Snapshot.inactive(UUID.randomUUID(), 13,
                InactiveReason.EXPIRED);
        JsonNode event = objectMapper.readTree(events.toCloudEventJson(UUID.randomUUID(), snapshot, Instant.now()));
        assertValid(event);
        assertThat(event.path("data").path("state").asText()).isEqualTo("INACTIVE");
        assertThat(event.path("data").has("discount")).isFalse();
        assertThat(event.path("data").has("promotion_rule_id")).isFalse();
    }

    @Test
    @DisplayName("dedup key is causal: product + campaign + revision + trigger, no state")
    void dedupKeyShape() {
        UUID productId = UUID.fromString("0198e0b3-97e4-7d66-8a2c-997d792ae826");
        assertThat(MarkdownSnapshotEvents.dedupKey(productId, 5, 7, ScheduleTrigger.START))
                .isEqualTo("markdown:0198e0b3-97e4-7d66-8a2c-997d792ae826:campaign:5:rev:7:trigger:START");
    }

    private void assertValid(JsonNode event) {
        Set<ValidationMessage> errors = schema.validate(event);
        assertThat(errors).as(errors.toString()).isEmpty();
    }
}
