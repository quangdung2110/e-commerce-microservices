package com.platform.paymentservice.integration;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.platform.paymentservice.adapter.out.persistence.JdbcFinancialCommitAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcCancelPaymentAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcIdempotencyAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcInitiatePaymentAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcPaymentClientDataAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcProviderOperationAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcProviderRecoveryAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcRefundPaymentAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcRefundPollingAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcRetentionAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcReconciliationAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcBalanceLedgerAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcReviewCaseAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcWebhookInboxAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcWebhookProcessingAdapter;
import com.platform.paymentservice.adapter.out.persistence.JdbcWebhookPaymentQueryAdapter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

@Testcontainers
abstract class AbstractPaymentIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_service")
            .withUsername("payment")
            .withPassword("payment");

    JdbcTemplate jdbc;
    JdbcClient jdbcClient;
    TransactionTemplate transactions;
    JdbcFinancialCommitAdapter commits;
    JdbcIdempotencyAdapter idempotency;
    JdbcProviderOperationAdapter providerOperations;
    JdbcProviderRecoveryAdapter providerRecovery;
    JdbcRefundPaymentAdapter refundPayments;
    JdbcRefundPollingAdapter refundPolling;
    JdbcRetentionAdapter retention;
    JdbcReconciliationAdapter reconciliation;
    JdbcBalanceLedgerAdapter balanceLedger;
    JdbcReviewCaseAdapter reviewCases;
    JdbcInitiatePaymentAdapter initiatePayments;
    JdbcPaymentClientDataAdapter clientData;
    JdbcCancelPaymentAdapter cancelPayments;
    JdbcWebhookInboxAdapter webhookInbox;
    JdbcWebhookProcessingAdapter webhookProcessing;
    JdbcWebhookPaymentQueryAdapter webhookPayments;

    @BeforeEach
    void migrateAndClean() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = transactionTemplate(dataSource);
        this.jdbcClient = JdbcClient.create(dataSource);
        this.commits = new JdbcFinancialCommitAdapter(jdbcClient, transactions);
        this.idempotency = new JdbcIdempotencyAdapter(jdbcClient, transactions);
        this.providerOperations = new JdbcProviderOperationAdapter(jdbcClient, transactions);
        this.providerRecovery = new JdbcProviderRecoveryAdapter(jdbcClient, transactions);
        this.refundPayments = new JdbcRefundPaymentAdapter(
                jdbcClient, transactions, JsonMapper.builder().findAndAddModules().build());
        this.refundPolling = new JdbcRefundPollingAdapter(jdbcClient, transactions);
        this.retention = new JdbcRetentionAdapter(jdbcClient, transactions);
        this.reconciliation = new JdbcReconciliationAdapter(jdbcClient, transactions);
        this.balanceLedger = new JdbcBalanceLedgerAdapter(jdbcClient, transactions);
        this.reviewCases = new JdbcReviewCaseAdapter(jdbcClient, transactions);
        this.initiatePayments = new JdbcInitiatePaymentAdapter(
                jdbcClient, transactions, JsonMapper.builder().findAndAddModules().build());
        this.clientData = new JdbcPaymentClientDataAdapter(jdbcClient);
        this.cancelPayments = new JdbcCancelPaymentAdapter(
                jdbcClient, transactions, JsonMapper.builder().findAndAddModules().build());
        this.webhookInbox = new JdbcWebhookInboxAdapter(jdbcClient, transactions);
        this.webhookProcessing = new JdbcWebhookProcessingAdapter(
                jdbcClient, transactions, JsonMapper.builder().findAndAddModules().build());
        this.webhookPayments = new JdbcWebhookPaymentQueryAdapter(jdbcClient);
    }

    private static TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
