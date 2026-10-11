package com.platform.paymentservice;

import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.CancelPaymentPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.IdempotencyPort;
import com.platform.paymentservice.application.port.out.InitiatePaymentPort;
import com.platform.paymentservice.application.port.out.PaymentClientDataPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.PaymentQueryPort;
import com.platform.paymentservice.application.port.out.RefundPaymentPort;
import com.platform.paymentservice.application.port.out.RefundPollingPort;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.ProviderRecoveryPort;
import com.platform.paymentservice.application.port.out.RetentionPort;
import com.platform.paymentservice.application.port.out.ReconciliationPort;
import com.platform.paymentservice.application.port.out.ProviderBalancePort;
import com.platform.paymentservice.application.port.out.BalanceLedgerPort;
import com.platform.paymentservice.application.port.out.ReviewCasePort;
import com.platform.paymentservice.application.port.out.SecretCipherPort;
import com.platform.paymentservice.application.port.out.VerifiedWebhookPublisherPort;
import com.platform.paymentservice.application.port.out.WebhookInboxPort;
import com.platform.paymentservice.application.port.out.WebhookVerificationPort;
import com.platform.paymentservice.application.port.out.FinancialCommitPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEventParserPort;
import com.platform.paymentservice.application.port.out.WebhookEventHandlerPort;
import com.platform.paymentservice.application.port.out.WebhookPaymentQueryPort;
import com.platform.paymentservice.application.port.out.WebhookProcessingPort;
import com.platform.paymentservice.application.service.InitiatePaymentService;
import com.platform.paymentservice.application.service.IdempotencyExecutor;
import com.platform.paymentservice.application.service.CancelPaymentService;
import com.platform.paymentservice.application.service.DeclinePaymentService;
import com.platform.paymentservice.domain.DeclinePolicy;
import com.platform.paymentservice.application.service.PaymentClientDataService;
import com.platform.paymentservice.application.service.PaymentQueryService;
import com.platform.paymentservice.application.service.RefundPaymentService;
import com.platform.paymentservice.application.service.RefundPollerService;
import com.platform.paymentservice.application.service.ProviderRecoveryService;
import com.platform.paymentservice.application.service.RetentionTrimmerService;
import com.platform.paymentservice.application.service.ReconciliationService;
import com.platform.paymentservice.application.service.BalanceReconciliationService;
import com.platform.paymentservice.application.service.ReconciliationOperationsService;
import com.platform.paymentservice.application.service.ReviewCaseService;
import com.platform.paymentservice.application.service.ManualCorrectionService;
import com.platform.paymentservice.application.service.WebhookIngressService;
import com.platform.paymentservice.application.service.StripeWebhookEventHandler;
import com.platform.paymentservice.application.service.WebhookProcessorService;
import com.platform.paymentservice.application.service.WebhookReplayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.domain.PaymentStateMachine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class PaymentUseCaseConfiguration {
    @Bean CanonicalFingerprint canonicalFingerprint() { return new CanonicalFingerprint(); }
    @Bean PaymentStateMachine paymentStateMachine() { return new PaymentStateMachine(); }
    @Bean IdempotencyExecutor idempotencyExecutor(IdempotencyPort port, ClockPort clock) {
        return new IdempotencyExecutor(port, clock);
    }

    @Bean
    WebhookReplayService webhookReplayService(
            WebhookProcessingPort inbox,
            IdempotencyExecutor idempotency,
            ClockPort clock,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.retention.webhook-raw-days:90}") long retentionDays) {
        return new WebhookReplayService(inbox, idempotency, clock, leaseTtl,
                Duration.ofDays(retentionDays));
    }

    @Bean
    @ConditionalOnProperty(name = {
            "payment.stripe.secret-key",
            "payment.stripe.publishable-key",
            "payment.crypto.local-key"})
    InitiatePaymentService initiatePaymentService(
            InitiatePaymentPort persistence,
            ProviderOperationPort operations,
            PaymentProviderPort provider,
            SecretCipherPort cipher,
            IdGeneratorPort ids,
            ClockPort clock,
            CanonicalFingerprint fingerprints,
            PaymentStateMachine stateMachine,
            @Value("${payment.stripe.publishable-key}") String publishableKey,
            @Value("${payment.session.ttl:PT15M}") Duration sessionTtl,
            @Value("${payment.session.client-secret-margin:PT30S}") Duration clientSecretMargin,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.provider.safe-replay-horizon:PT23H}") Duration safeReplayHorizon,
            @Value("${payment.scheduler.recovery.backoff:PT30S}") Duration retryDelay) {
        return new InitiatePaymentService(
                persistence, operations, provider, cipher, ids, clock, fingerprints,
                stateMachine, publishableKey, sessionTtl, clientSecretMargin, leaseTtl,
                safeReplayHorizon, sessionTtl.plus(Duration.ofHours(24)), retryDelay);
    }

    @Bean
    PaymentQueryService paymentQueryService(PaymentQueryPort queries) {
        return new PaymentQueryService(queries);
    }

    @Bean
    @ConditionalOnProperty(name = {
            "payment.stripe.publishable-key",
            "payment.crypto.local-key"})
    PaymentClientDataService paymentClientDataService(
            PaymentClientDataPort data,
            SecretCipherPort cipher,
            ClockPort clock,
            @Value("${payment.stripe.publishable-key}") String publishableKey) {
        return new PaymentClientDataService(data, cipher, clock, publishableKey);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    CancelPaymentService cancelPaymentService(
            CancelPaymentPort persistence,
            ProviderOperationPort operations,
            PaymentProviderPort provider,
            IdGeneratorPort ids,
            ClockPort clock,
            CanonicalFingerprint fingerprints,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.provider.safe-replay-horizon:PT23H}") Duration safeReplayHorizon,
            @Value("${payment.provider.cancel-recovery-window:P7D}") Duration recoveryWindow,
            @Value("${payment.scheduler.recovery.backoff:PT30S}") Duration retryDelay) {
        return new CancelPaymentService(persistence, operations, provider, ids, clock,
                fingerprints, leaseTtl, safeReplayHorizon, recoveryWindow, retryDelay);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    RefundPaymentService refundPaymentService(
            RefundPaymentPort persistence,
            ProviderOperationPort operations,
            PaymentProviderPort provider,
            IdGeneratorPort ids,
            ClockPort clock,
            CanonicalFingerprint fingerprints,
            ObjectMapper json,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.provider.safe-replay-horizon:PT23H}") Duration safeReplayHorizon,
            @Value("${payment.provider.refund-recovery-window:P7D}") Duration recoveryWindow,
            @Value("${payment.scheduler.recovery.backoff:PT30S}") Duration retryDelay) {
        return new RefundPaymentService(persistence, operations, provider, ids, clock,
                fingerprints, json, leaseTtl, safeReplayHorizon, recoveryWindow, retryDelay);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    RefundPollerService refundPollerService(
            RefundPollingPort refunds,
            ProviderCurrentFactPort provider,
            FinancialCommitPort commits,
            IdGeneratorPort ids,
            ClockPort clock,
            ObjectMapper json,
            @Value("${payment.scheduler.refund.minimum-age:PT1M}") Duration minimumAge) {
        return new RefundPollerService(refunds, provider, commits, ids, clock, json, minimumAge);
    }

    @Bean
    @ConditionalOnProperty(name = {
            "payment.stripe.secret-key",
            "payment.crypto.local-key"})
    ProviderRecoveryService providerRecoveryService(
            ProviderRecoveryPort recovery,
            ProviderOperationPort operations,
            PaymentProviderPort provider,
            InitiatePaymentPort initiations,
            CancelPaymentPort cancellations,
            RefundPaymentPort refunds,
            SecretCipherPort cipher,
            IdGeneratorPort ids,
            ClockPort clock,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.provider.safe-replay-horizon:PT23H}") Duration safeReplayHorizon,
            @Value("${payment.scheduler.recovery.backoff:PT30S}") Duration baseBackoff,
            @Value("${payment.scheduler.recovery.max-backoff:PT5M}") Duration maximumBackoff,
            @Value("${payment.scheduler.recovery.max-attempts:20}") int maximumAttempts,
            @Value("${payment.session.client-secret-margin:PT30S}") Duration clientSecretMargin) {
        return new ProviderRecoveryService(recovery, operations, provider, initiations,
                cancellations, refunds, cipher, ids, clock, leaseTtl, safeReplayHorizon,
                baseBackoff, maximumBackoff, maximumAttempts, clientSecretMargin);
    }

    @Bean
    RetentionTrimmerService retentionTrimmerService(
            RetentionPort retention,
            ClockPort clock,
            @Value("${payment.retention.outbox-days:15}") int outboxDays,
            @Value("${payment.retention.webhook-raw-days:90}") int webhookRawDays,
            @Value("${payment.retention.webhook-metadata-months:12}") int webhookMetadataMonths,
            @Value("${payment.retention.reconciliation-run-months:12}") int reconciliationRunMonths,
            @Value("${payment.retention.financial-years:7}") int financialYears,
            @Value("${payment.retention.outbox-slot-name:payment_outbox_slot}") String slotName) {
        return new RetentionTrimmerService(retention, clock, new RetentionPort.Policy(
                outboxDays, webhookRawDays, webhookMetadataMonths,
                reconciliationRunMonths, financialYears, slotName));
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    ReconciliationService reconciliationService(
            ReconciliationPort persistence,
            ProviderCurrentFactPort provider,
            FinancialCommitPort commits,
            IdGeneratorPort ids,
            ClockPort clock,
            ObjectMapper json,
            @Value("${payment.reconciliation.refund-success-lookback:P35D}")
            java.time.Duration refundSuccessLookback) {
        return new ReconciliationService(persistence, provider, commits, ids, clock, json,
                refundSuccessLookback);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    BalanceReconciliationService balanceReconciliationService(
            ReconciliationPort runs,
            ProviderBalancePort provider,
            BalanceLedgerPort ledger,
            IdGeneratorPort ids,
            ClockPort clock,
            ObjectMapper json) {
        return new BalanceReconciliationService(runs, provider, ledger, ids, clock, json);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    ReconciliationOperationsService reconciliationOperationsService(
            ReconciliationService objectState,
            BalanceReconciliationService balanceLedger,
            ReconciliationPort runs,
            IdempotencyExecutor idempotency,
            ClockPort clock,
            ObjectMapper json,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.review.idempotency-retention:P7D}") Duration retention,
            @Value("${payment.reconciliation.batch-size:500}") int batchSize,
            @Value("${payment.reconciliation.auto-correction-frozen:false}") boolean frozen) {
        return new ReconciliationOperationsService(objectState, balanceLedger, runs,
                idempotency, clock, json, leaseTtl, retention, batchSize, frozen);
    }

    @Bean
    ReviewCaseService reviewCaseService(
            ReviewCasePort cases,
            IdempotencyExecutor idempotency,
            ClockPort clock,
            ObjectMapper json,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.review.idempotency-retention:P7D}") Duration retention) {
        return new ReviewCaseService(cases, idempotency, clock, json, leaseTtl, retention);
    }

    @Bean
    ManualCorrectionService manualCorrectionService(
            ReviewCasePort cases,
            WebhookPaymentQueryPort queries,
            FinancialCommitPort commits,
            IdempotencyExecutor idempotency,
            IdGeneratorPort ids,
            ClockPort clock,
            ObjectMapper json,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.review.idempotency-retention:P7D}") Duration retention) {
        return new ManualCorrectionService(cases, queries, commits, idempotency,
                ids, clock, json, leaseTtl, retention);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.secret-key")
    DeclinePaymentService declinePaymentService(
            CancelPaymentPort persistence,
            CancelPaymentService cancels,
            IdGeneratorPort ids,
            ClockPort clock,
            @Value("${payment.stripe.decline.max-per-attempt:5}") int maximumDeclines,
            @Value("${payment.command.lease-ttl:PT45S}") Duration leaseTtl,
            @Value("${payment.provider.cancel-recovery-window:P7D}") Duration recoveryWindow) {
        return new DeclinePaymentService(persistence, cancels, ids, clock,
                new DeclinePolicy(maximumDeclines), leaseTtl, recoveryWindow);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.stripe.webhook-secrets")
    WebhookIngressService webhookIngressService(
            WebhookVerificationPort verifier,
            WebhookInboxPort inbox,
            VerifiedWebhookPublisherPort publisher,
            SecretCipherPort cipher,
            IdGeneratorPort ids,
            ClockPort clock) {
        return new WebhookIngressService(verifier, inbox, publisher, cipher, ids, clock);
    }

    @Bean
    @ConditionalOnProperty(name = {"payment.stripe.webhook-secrets", "payment.crypto.local-key",
            "payment.stripe.secret-key"})
    WebhookEventHandlerPort stripeWebhookEventHandler(
            StripeWebhookEventParserPort parser,
            WebhookPaymentQueryPort payments,
            WebhookProcessingPort inbox,
            FinancialCommitPort commits,
            PaymentStateMachine stateMachine,
            IdGeneratorPort ids,
            ObjectMapper json,
            ProviderCurrentFactPort currentFacts,
            DeclinePaymentService declines) {
        return new StripeWebhookEventHandler(
                parser, payments, inbox, commits, stateMachine, ids, json, currentFacts, declines);
    }

    @Bean
    @ConditionalOnProperty(name = {"payment.stripe.webhook-secrets", "payment.crypto.local-key",
            "payment.stripe.secret-key"})
    WebhookProcessorService webhookProcessorService(
            WebhookProcessingPort inbox,
            WebhookEventHandlerPort handler,
            SecretCipherPort cipher,
            IdGeneratorPort ids,
            ClockPort clock,
            @Value("${payment.webhook.lease-ttl:PT1M}") Duration leaseTtl,
            @Value("${payment.webhook.retry.base-delay:PT30S}") Duration retryBaseDelay,
            @Value("${payment.webhook.retry.max:8}") int maximumAttempts) {
        return new WebhookProcessorService(
                inbox, handler, cipher, ids, clock, leaseTtl, retryBaseDelay, maximumAttempts);
    }
}
