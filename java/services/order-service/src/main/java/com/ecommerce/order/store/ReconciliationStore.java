package com.ecommerce.order.store;

import com.ecommerce.order.domain.RefundTypes.ObligationType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One reconciliation envelope per order with typed, independent obligations (ADR-018). */
@Repository
public class ReconciliationStore {

    public record CaseRow(long orderId, UUID caseId, long revision, String status, Instant openedAt,
                          Instant updatedAt, Instant closedAt) {
    }

    public record ObligationRow(UUID obligationId, UUID caseId, long orderId, ObligationType type, String resourceId,
                                String status, long revision, String detail, String resolutionType,
                                String accountingCaseReference, Instant openedAt, Instant updatedAt,
                                Instant closedAt) {

        public boolean open() {
            return !"CLOSED".equals(status);
        }
    }

    public record EvidenceRow(UUID evidenceId, UUID obligationId, String source, String reference, String sha256,
                              Long version, String detailJson, Instant recordedAt) {
    }

    public record DecisionRow(UUID decisionId, UUID obligationId, String requestDigest, String responseJson) {
    }

    private static final RowMapper<CaseRow> CASE = (rs, i) -> new CaseRow(rs.getLong("order_id"),
            Db.uuid(rs, "case_id"), rs.getLong("case_revision"), rs.getString("case_status"),
            Db.instant(rs, "opened_at"), Db.instant(rs, "updated_at"), Db.instant(rs, "closed_at"));

    private static final RowMapper<ObligationRow> OBLIGATION = (rs, i) -> new ObligationRow(
            Db.uuid(rs, "obligation_id"), Db.uuid(rs, "case_id"), rs.getLong("order_id"),
            ObligationType.valueOf(rs.getString("obligation_type")), rs.getString("resource_id"),
            rs.getString("status"), rs.getLong("revision"), rs.getString("detail"), rs.getString("resolution_type"),
            rs.getString("accounting_case_reference"), Db.instant(rs, "opened_at"), Db.instant(rs, "updated_at"),
            Db.instant(rs, "closed_at"));

    private static final RowMapper<EvidenceRow> EVIDENCE = (rs, i) -> new EvidenceRow(Db.uuid(rs, "evidence_id"),
            Db.uuid(rs, "obligation_id"), rs.getString("source"), rs.getString("reference"), rs.getString("sha256"),
            Db.nullableLong(rs, "version"), rs.getString("detail"), Db.instant(rs, "recorded_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the envelope on first use, reopening a closed one; caller holds the order lock. */
    public CaseRow ensureCase(long orderId) {
        jdbc.update("""
                INSERT INTO order_financial_reconciliation_cases (order_id, case_id, case_status)
                VALUES (:order, :case, 'OPEN')
                ON CONFLICT (order_id) DO UPDATE SET
                    case_status = CASE WHEN order_financial_reconciliation_cases.case_status = 'CLOSED'
                                       THEN 'OPEN' ELSE order_financial_reconciliation_cases.case_status END,
                    closed_at = NULL
                """, Map.of("order", orderId, "case", UUID.randomUUID()));
        return lockCase(orderId).orElseThrow();
    }

    public Optional<CaseRow> findCase(long orderId) {
        return jdbc.query("SELECT * FROM order_financial_reconciliation_cases WHERE order_id = :order",
                Map.of("order", orderId), CASE).stream().findFirst();
    }

    public Optional<CaseRow> lockCase(long orderId) {
        return jdbc.query("SELECT * FROM order_financial_reconciliation_cases WHERE order_id = :order FOR UPDATE",
                Map.of("order", orderId), CASE).stream().findFirst();
    }

    public void bumpCase(long orderId, String status) {
        jdbc.update("""
                UPDATE order_financial_reconciliation_cases SET case_revision = case_revision + 1,
                    case_status = :status, updated_at = NOW(),
                    closed_at = CASE WHEN :status = 'CLOSED' THEN NOW() ELSE NULL END
                WHERE order_id = :order
                """, Map.of("order", orderId, "status", status));
    }

    public Optional<ObligationRow> activeObligation(long orderId, ObligationType type, String resourceId) {
        return jdbc.query("""
                SELECT * FROM order_reconciliation_obligations
                WHERE order_id = :order AND obligation_type = :type AND resource_id = :resource AND status <> 'CLOSED'
                """, Map.of("order", orderId, "type", type.name(), "resource", resourceId), OBLIGATION)
                .stream().findFirst();
    }

    public Optional<ObligationRow> obligation(UUID obligationId) {
        return jdbc.query("SELECT * FROM order_reconciliation_obligations WHERE obligation_id = :id",
                Map.of("id", obligationId), OBLIGATION).stream().findFirst();
    }

    public Optional<ObligationRow> lockObligation(UUID obligationId) {
        return jdbc.query("SELECT * FROM order_reconciliation_obligations WHERE obligation_id = :id FOR UPDATE",
                Map.of("id", obligationId), OBLIGATION).stream().findFirst();
    }

    public List<ObligationRow> obligations(long orderId) {
        return jdbc.query("SELECT * FROM order_reconciliation_obligations WHERE order_id = :order ORDER BY opened_at",
                Map.of("order", orderId), OBLIGATION);
    }

    public void insertObligation(UUID obligationId, UUID caseId, long orderId, ObligationType type, String resourceId,
                                 String detail) {
        jdbc.update("""
                INSERT INTO order_reconciliation_obligations (obligation_id, case_id, order_id, obligation_type,
                    resource_id, status, detail)
                VALUES (:id, :case, :order, :type, :resource, 'OPEN', :detail)
                """, new MapSqlParameterSource().addValue("id", obligationId).addValue("case", caseId)
                .addValue("order", orderId).addValue("type", type.name()).addValue("resource", resourceId)
                .addValue("detail", detail));
    }

    public void updateObligation(UUID obligationId, String status, String resolutionType, String accountingRef) {
        jdbc.update("""
                UPDATE order_reconciliation_obligations SET status = :status, revision = revision + 1,
                    resolution_type = COALESCE(:resolution, resolution_type),
                    accounting_case_reference = COALESCE(:accounting, accounting_case_reference),
                    updated_at = NOW(), closed_at = CASE WHEN :status = 'CLOSED' THEN NOW() ELSE closed_at END
                WHERE obligation_id = :id
                """, new MapSqlParameterSource().addValue("id", obligationId).addValue("status", status)
                .addValue("resolution", resolutionType).addValue("accounting", accountingRef));
    }

    public void addEvidence(UUID obligationId, String source, String reference, String sha256, Long version,
                            String detailJson) {
        jdbc.update("""
                INSERT INTO order_reconciliation_evidence (evidence_id, obligation_id, source, reference, sha256,
                    version, detail)
                VALUES (:id, :obligation, :source, :reference, :sha, :version, CAST(:detail AS jsonb))
                ON CONFLICT (obligation_id, source, reference) DO NOTHING
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("obligation", obligationId)
                .addValue("source", source).addValue("reference", reference).addValue("sha", sha256)
                .addValue("version", version).addValue("detail", detailJson));
    }

    public List<EvidenceRow> evidence(UUID obligationId) {
        return jdbc.query("SELECT * FROM order_reconciliation_evidence WHERE obligation_id = :id ORDER BY recorded_at",
                Map.of("id", obligationId), EVIDENCE);
    }

    public Optional<DecisionRow> decision(UUID decisionId) {
        return jdbc.query("""
                SELECT decision_id, obligation_id, request_digest, response FROM order_reconciliation_decisions
                WHERE decision_id = :id
                """, Map.of("id", decisionId), (rs, i) -> new DecisionRow(Db.uuid(rs, "decision_id"),
                Db.uuid(rs, "obligation_id"), rs.getString("request_digest"), rs.getString("response")))
                .stream().findFirst();
    }

    public void insertDecision(UUID decisionId, UUID obligationId, UUID caseId, long orderId, long expectedRevision,
                               String resolution, String reasonCode, String requestDigest, String requestJson,
                               UUID actorId, String principal, String accountingRef, String responseJson) {
        jdbc.update("""
                INSERT INTO order_reconciliation_decisions (decision_id, obligation_id, case_id, order_id,
                    expected_case_revision, resolution_type, reason_code, request_digest, request, actor_id,
                    workload_principal, accounting_case_reference, response)
                VALUES (:id, :obligation, :case, :order, :revision, :resolution, :reason, :digest,
                    CAST(:request AS jsonb), :actor, :principal, :accounting, CAST(:response AS jsonb))
                """, new MapSqlParameterSource().addValue("id", decisionId).addValue("obligation", obligationId)
                .addValue("case", caseId).addValue("order", orderId).addValue("revision", expectedRevision)
                .addValue("resolution", resolution).addValue("reason", reasonCode).addValue("digest", requestDigest)
                .addValue("request", requestJson).addValue("actor", actorId).addValue("principal", principal)
                .addValue("accounting", accountingRef).addValue("response", responseJson));
    }
}
