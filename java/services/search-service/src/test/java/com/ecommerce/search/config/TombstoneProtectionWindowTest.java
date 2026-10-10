package com.ecommerce.search.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SEARCH-INV-006} / ADR-011 / ADR-022: the purge window outlives the longest time any
 * consumed record can still be on the broker, so no event can be replayed after its document
 * has been physically removed.
 */
class TombstoneProtectionWindowTest {

    /**
     * Longest retention among the consumed topics: {@code t.inventory.stock.events.v1} is
     * exactly 30 days (Inventory ADR-028); the Product, Category, catalog-control and Review
     * topics are 14 days. Raise this when a consumed topic's retention grows.
     */
    private static final Duration LONGEST_CONSUMED_RETENTION = Duration.ofDays(30);

    /**
     * Kafka deletes whole log segments, and a segment is only eligible once its newest record is
     * older than {@code retention.ms}. A record can therefore outlive the retention by up to
     * {@code segment.ms}, which is 7 days unless the topic is provisioned otherwise.
     */
    private static final Duration DEFAULT_SEGMENT_ROLL = Duration.ofDays(7);

    /** Clock skew, consumer backlog and the daily purge schedule. */
    private static final Duration MINIMUM_MARGIN = Duration.ofDays(5);

    private static final Duration REQUIRED = LONGEST_CONSUMED_RETENTION
            .plus(DEFAULT_SEGMENT_ROLL).plus(MINIMUM_MARGIN);

    @Test
    void configuredWindowOutlivesRetentionPlusASegmentRollPlusTheMargin() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));

        Duration window = binder.bind("search.tombstone.protection-window", Duration.class).get();

        assertThat(window).isGreaterThanOrEqualTo(REQUIRED);
    }

    @Test
    void recordDefaultOutlivesRetentionPlusASegmentRollPlusTheMargin() {
        String declared = Arrays.stream(SearchProperties.Tombstone.class.getDeclaredConstructors()[0].getParameters())
                .filter(p -> p.getType() == Duration.class)
                .findFirst().orElseThrow()
                .getAnnotation(DefaultValue.class).value()[0];

        Duration fallback = new Binder(ConfigurationPropertySources.from(
                new MapPropertySource("default", Map.<String, Object>of("window", declared))))
                .bind("window", Duration.class).get();

        assertThat(fallback).isGreaterThanOrEqualTo(REQUIRED);
    }
}
