package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.ReviewCasePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class JdbcReviewCaseAdapter implements ReviewCasePort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcReviewCaseAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<Case> list(String status, int limit) {
        return jdbc.sql("""
                SELECT id,payment_transaction_id,refund_transaction_id,reason_code,case_status,
                       evidence::text AS evidence,resolution_type,resolution_note,opened_at,
                       assigned_to,resolved_by,resolved_at
                  FROM payment_review_cases
                 WHERE (:status IS NULL OR case_status=:status)
                 ORDER BY opened_at,id LIMIT :limit
                """).param("status", status).param("limit", limit)
                .query((rs, ignored) -> map(rs)).list();
    }

    @Override
    public Optional<Case> find(String caseId) {
        return jdbc.sql("""
                SELECT id,payment_transaction_id,refund_transaction_id,reason_code,case_status,
                       evidence::text AS evidence,resolution_type,resolution_note,opened_at,
                       assigned_to,resolved_by,resolved_at
                  FROM payment_review_cases WHERE id=:id
                """).param("id", caseId).query((rs, ignored) -> map(rs)).optional();
    }

    @Override
    public Mutation claim(String caseId, String actor, Instant at) {
        return transactions.execute(ignored -> {
            List<Map<String, Object>> rows = jdbc.sql("""
                    SELECT case_status,assigned_to FROM payment_review_cases WHERE id=:id FOR UPDATE
                    """).param("id", caseId).query().listOfRows();
            if (rows.isEmpty()) return Mutation.MISSING;
            Map<String, Object> row = rows.getFirst();
            if ("IN_PROGRESS".equals(row.get("case_status")) && actor.equals(row.get("assigned_to")))
                return Mutation.ALREADY_APPLIED;
            if (!"OPEN".equals(row.get("case_status"))) return Mutation.CONFLICT;
            int changed = jdbc.sql("""
                    UPDATE payment_review_cases SET case_status='IN_PROGRESS',assigned_to=:actor
                     WHERE id=:id AND case_status='OPEN'
                    """).param("actor", actor).param("id", caseId).update();
            return changed == 1 ? Mutation.APPLIED : Mutation.CONFLICT;
        });
    }

    @Override
    public Mutation resolve(String caseId, String actor, String resolutionType,
                            String note, String causeRef, Instant at) {
        return transactions.execute(ignored -> {
            List<Map<String, Object>> rows = jdbc.sql("""
                    SELECT payment_transaction_id,case_status,assigned_to,resolution_type
                      FROM payment_review_cases WHERE id=:id FOR UPDATE
                    """).param("id", caseId).query().listOfRows();
            if (rows.isEmpty()) return Mutation.MISSING;
            Map<String, Object> row = rows.getFirst();
            if (("RESOLVED".equals(row.get("case_status")) || "ESCALATED".equals(row.get("case_status")))
                    && resolutionType.equals(row.get("resolution_type"))) return Mutation.ALREADY_APPLIED;
            if (!"IN_PROGRESS".equals(row.get("case_status")) || !actor.equals(row.get("assigned_to")))
                return Mutation.CONFLICT;
            if ("CORRECTION_APPLIED".equals(resolutionType)) {
                Integer evidence = jdbc.sql("""
                        SELECT count(*) FROM payment_status_history
                         WHERE payment_transaction_id=:payment_id
                           AND cause_ref=:case_id AND triggered_by IN ('MANUAL','RECONCILIATION')
                        """).param("payment_id", row.get("payment_transaction_id"))
                        .param("case_id", caseId).query(Integer.class).single();
                if (evidence == null || evidence == 0) return Mutation.CORRECTION_EVIDENCE_MISSING;
            }
            String targetStatus = resolutionType.startsWith("ESCALATED_") ? "ESCALATED" : "RESOLVED";
            int changed = jdbc.sql("""
                    UPDATE payment_review_cases
                       SET case_status=:status,resolution_type=:resolution,
                           resolution_note=:note,resolved_by=:actor,resolved_at=:at,
                           evidence=evidence || CAST(:audit AS jsonb)
                     WHERE id=:id AND case_status='IN_PROGRESS' AND assigned_to=:actor
                    """).param("status", targetStatus).param("resolution", resolutionType)
                    .param("note", note).param("actor", actor).param("at", timestamp(at))
                    .param("audit", "[{\"cause_ref\":\"" + escape(causeRef)
                            + "\",\"resolved_by\":\"" + escape(actor)
                            + "\",\"resolved_at\":\"" + at + "\"}]")
                    .param("id", caseId).update();
            if (changed != 1) return Mutation.CONFLICT;
            jdbc.sql("""
                    UPDATE payment_transactions p SET requires_manual_review=EXISTS (
                      SELECT 1 FROM payment_review_cases c
                       WHERE c.payment_transaction_id=p.id AND c.case_status IN ('OPEN','IN_PROGRESS')),
                      version=version+1,updated_at=:at
                     WHERE p.id=:payment_id
                    """).param("at", timestamp(at)).param("payment_id", row.get("payment_transaction_id")).update();
            return Mutation.APPLIED;
        });
    }

    @Override
    public boolean correctionApplied(String caseId) {
        Integer count = jdbc.sql("""
                SELECT count(*) FROM payment_status_history
                 WHERE cause_ref=:case_id AND triggered_by='MANUAL'
                """).param("case_id", caseId).query(Integer.class).single();
        return count != null && count > 0;
    }

    private static Case map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new Case(rs.getString("id"), rs.getString("payment_transaction_id"),
                rs.getString("refund_transaction_id"), rs.getString("reason_code"),
                rs.getString("case_status"), rs.getString("evidence"),
                rs.getString("resolution_type"), rs.getString("resolution_note"),
                rs.getTimestamp("opened_at").toInstant(), rs.getString("assigned_to"),
                rs.getString("resolved_by"), resolved == null ? null : resolved.toInstant());
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
