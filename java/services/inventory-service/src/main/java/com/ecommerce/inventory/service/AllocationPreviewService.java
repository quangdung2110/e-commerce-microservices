package com.ecommerce.inventory.service;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.domain.AllocationPolicy;
import com.ecommerce.inventory.web.dto.WorkflowDtos;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Advisory, stateless allocation preview (interfaces.md §2, ADR-021). It reads the
 * authoritative primary without locks, persists nothing and creates no hold;
 * {@code allocation_plan_id} is an opaque correlation identifier with no expiry.
 */
@Service
public class AllocationPreviewService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AllocationPreviewService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Object preview(WorkflowDtos.AllocationPreviewRequest request) {
        List<String> skuIds = request.lines().stream().map(WorkflowDtos.PreviewLine::skuId).distinct().toList();
        Map<String, List<AllocationPolicy.Candidate>> candidates;
        try {
            candidates = candidates(skuIds);
        } catch (DataAccessException e) {
            throw ApiException.unavailable("The authoritative store is unavailable");
        }
        List<AllocationPolicy.Line> lines = request.lines().stream()
                .map(line -> new AllocationPolicy.Line(line.lineReference(), line.skuId(), line.quantity()))
                .toList();
        List<Optional<AllocationPolicy.Choice>> plan = AllocationPolicy.plan(lines, candidates);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        List<WorkflowDtos.RejectedPreviewLine> rejected = new ArrayList<>();
        List<WorkflowDtos.PlannedLine> planned = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            AllocationPolicy.Line line = lines.get(i);
            Optional<AllocationPolicy.Choice> choice = plan.get(i);
            if (choice.isEmpty()) {
                rejected.add(new WorkflowDtos.RejectedPreviewLine(line.reference(), line.skuId(), line.quantity(),
                        "NO_ELIGIBLE_WAREHOUSE"));
            } else {
                AllocationPolicy.Candidate warehouse = choice.get().warehouse();
                planned.add(new WorkflowDtos.PlannedLine(line.reference(), line.skuId(), line.quantity(),
                        warehouse.warehouseId(), warehouse.fulfillmentPriority(), "FULFILLMENT_PRIORITY"));
            }
        }
        if (!rejected.isEmpty()) {
            return new WorkflowDtos.RejectAllPreview("REJECT_ALL", now, rejected);
        }
        return new WorkflowDtos.AllocatablePreview("ALLOCATABLE", Ids.newId(), now, planned);
    }

    private Map<String, List<AllocationPolicy.Candidate>> candidates(List<String> skuIds) {
        Map<String, List<AllocationPolicy.Candidate>> bySku = new HashMap<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT p.sku_id, p.warehouse_id, w.fulfillment_priority, w.is_active, w.operational_state,
                           p.available_to_sell
                    FROM stock_position p JOIN warehouse w ON w.warehouse_id = p.warehouse_id
                    WHERE p.sku_id = ANY (?)
                    """);
            Array array = con.createArrayOf("varchar", skuIds.toArray());
            statement.setArray(1, array);
            return statement;
        }, rs -> {
            bySku.computeIfAbsent(rs.getString("sku_id"), k -> new ArrayList<>()).add(new AllocationPolicy.Candidate(
                    rs.getString("warehouse_id"), rs.getInt("fulfillment_priority"), rs.getBoolean("is_active"),
                    "OUTAGE".equals(rs.getString("operational_state")), rs.getLong("available_to_sell")));
        });
        return bySku;
    }
}
