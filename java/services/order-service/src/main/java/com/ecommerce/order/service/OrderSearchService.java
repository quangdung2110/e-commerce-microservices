package com.ecommerce.order.service;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.domain.BuyerTab;
import com.ecommerce.order.domain.NameNormalizer;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.PaymentStatus;
import com.ecommerce.order.store.Db;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * WF-ORDER-002 search directly on PostgreSQL (ADR-004/017): class A filters on
 * {@code orders}; class B adds an accent-insensitive substring match on the pinned
 * {@code snapshot_name_search}. One card per order, keyset cursor on (created_at, id) DESC,
 * total count without cursor/limit, default 90-day window.
 */
@Service
public class OrderSearchService {

    public record Criteria(UUID buyerId, BuyerTab tab, String q, Instant createdFrom, Instant createdTo,
                           List<OrderStatus> statuses, List<PaymentStatus> paymentStatuses, Long minAmount,
                           Long maxAmount, String cursor, int limit) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final Clock clock;
    private final OrderProperties.Search config;

    public OrderSearchService(NamedParameterJdbcTemplate jdbc, Json json, Clock clock, OrderProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.config = properties.search();
    }

    public ObjectNode search(Criteria criteria) {
        if (criteria.minAmount() != null && criteria.maxAmount() != null && criteria.minAmount() > criteria.maxAmount()) {
            throw ApiException.invalid("min_amount_minor must not exceed max_amount_minor");
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (criteria.buyerId() != null) {
            where.append(" AND o.user_id = :buyer");
            params.addValue("buyer", criteria.buyerId());
        }
        Instant from = criteria.createdFrom();
        Instant to = criteria.createdTo();
        if (from == null && to == null) {
            from = clock.instant().minus(config.defaultWindow());
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.invalid("created_from must not be after created_to");
        }
        if (from != null) {
            where.append(" AND o.created_at >= :from");
            params.addValue("from", Db.ts(from));
        }
        if (to != null) {
            where.append(" AND o.created_at <= :to");
            params.addValue("to", Db.ts(to));
        }
        if (criteria.statuses() != null && !criteria.statuses().isEmpty()) {
            where.append(" AND o.status IN (:statuses)");
            params.addValue("statuses", criteria.statuses().stream().map(Enum::name).toList());
        }
        if (criteria.paymentStatuses() != null && !criteria.paymentStatuses().isEmpty()) {
            where.append(" AND o.payment_status IN (:payments)");
            params.addValue("payments", criteria.paymentStatuses().stream().map(Enum::name).toList());
        }
        if (criteria.minAmount() != null) {
            where.append(" AND o.final_amount_minor >= :min");
            params.addValue("min", criteria.minAmount());
        }
        if (criteria.maxAmount() != null) {
            where.append(" AND o.final_amount_minor <= :max");
            params.addValue("max", criteria.maxAmount());
        }
        if (criteria.tab() != null) {
            where.append(" AND v.buyer_tab = :tab");
            params.addValue("tab", criteria.tab().name());
        }
        if (criteria.q() != null) {
            String normalized = NameNormalizer.normalize(criteria.q()).trim();
            if (normalized.isEmpty()) {
                throw ApiException.invalid("q must contain searchable characters");
            }
            where.append(" AND o.id IN (SELECT DISTINCT s.order_id FROM order_item_lines s"
                    + " WHERE s.snapshot_name_search LIKE :pattern ESCAPE '\\')");
            params.addValue("pattern", "%" + escapeLike(normalized) + "%");
        }
        String from_ = " FROM orders o JOIN v_orders_buyer_tab v ON v.order_id = o.id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from_ + where, params, Long.class);
        StringBuilder pageWhere = new StringBuilder(where);
        if (criteria.cursor() != null) {
            Cursor cursor = decode(criteria.cursor());
            pageWhere.append(" AND (o.created_at, o.id) < (:cursorAt, :cursorId)");
            params.addValue("cursorAt", Db.ts(cursor.createdAt())).addValue("cursorId", cursor.orderId());
        }
        params.addValue("limit", criteria.limit() + 1);
        String sql = """
                SELECT o.id, o.user_id, o.status, o.created_at, o.final_amount_minor, v.buyer_tab,
                       p.snapshot_name, p.snapshot_image_url,
                       (SELECT COUNT(DISTINCT c.id) FROM order_item_lines c WHERE c.order_id = o.id) AS item_count
                """ + from_ + """
                 CROSS JOIN LATERAL (SELECT l.snapshot_name, l.snapshot_image_url FROM order_item_lines l
                                     WHERE l.order_id = o.id ORDER BY l.order_item_id, l.id LIMIT 1) p
                """ + pageWhere + " ORDER BY o.created_at DESC, o.id DESC LIMIT :limit";
        List<ObjectNode> cards = new ArrayList<>();
        List<Cursor> keys = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            ObjectNode card = json.object();
            long id = rs.getLong("id");
            Instant createdAt = Db.instant(rs, "created_at");
            OrderStatus status = OrderStatus.valueOf(rs.getString("status"));
            BuyerTab tab = BuyerTab.valueOf(rs.getString("buyer_tab"));
            long count = rs.getLong("item_count");
            card.put("order_id", Long.toString(id));
            card.put("buyer_id", rs.getString("user_id"));
            card.put("buyer_tab", tab.name());
            card.put("ui_primary_status", BuyerTab.uiPrimaryStatus(status, tab));
            card.put("ui_status_rank", tab.rank());
            card.put("created_at", createdAt.toString());
            card.put("primary_item_name", rs.getString("snapshot_name"));
            card.put("primary_item_image_url", rs.getString("snapshot_image_url"));
            card.put("primary_store_name", "PLATFORM");
            card.put("item_count", count);
            card.put("remaining_item_count", Math.max(count - 1, 0));
            ObjectNode money = card.putObject("final_amount");
            money.put("amount_minor", rs.getLong("final_amount_minor"));
            money.put("currency", "VND");
            cards.add(card);
            keys.add(new Cursor(createdAt, id));
        });
        ObjectNode page = json.object();
        ArrayNode items = page.putArray("items");
        boolean more = cards.size() > criteria.limit();
        cards.stream().limit(criteria.limit()).forEach(items::add);
        page.put("total_orders", total == null ? 0 : total);
        page.put("next_cursor", more ? encode(keys.get(criteria.limit() - 1)) : null);
        return page;
    }

    record Cursor(Instant createdAt, long orderId) {
    }

    static String encode(Cursor cursor) {
        String raw = cursor.createdAt().toString() + "|" + cursor.orderId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0) {
                throw ApiException.invalid("Invalid cursor");
            }
            return new Cursor(Instant.parse(raw.substring(0, separator)), Long.parseLong(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw ApiException.invalid("Invalid cursor");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
