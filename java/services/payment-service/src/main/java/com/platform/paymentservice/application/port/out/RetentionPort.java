package com.platform.paymentservice.application.port.out;

import java.time.Instant;

public interface RetentionPort {
    Result trim(Policy policy, Instant now, int batchSize);

    record Policy(int outboxDays, int webhookRawDays, int webhookMetadataMonths,
                  int reconciliationRunMonths, int financialYears, String outboxSlotName) {
        public Policy {
            if (outboxDays < 15 || webhookRawDays < 1 || webhookMetadataMonths < 1
                    || reconciliationRunMonths < 1 || financialYears < 7)
                throw new IllegalArgumentException("invalid retention policy");
            if (outboxSlotName == null || outboxSlotName.isBlank())
                throw new IllegalArgumentException("outboxSlotName is required");
        }
    }

    record Result(int clientSecretsShredded, int webhookPayloadsShredded,
                  int webhookMetadataDeleted, int idempotencyDeleted,
                  int reconciliationRunsDeleted, int financialPaymentsDeleted, int outboxDeleted,
                  boolean outboxWatermarkAvailable) {
        public int affectedRows() {
            return clientSecretsShredded + webhookPayloadsShredded + webhookMetadataDeleted
                    + idempotencyDeleted + reconciliationRunsDeleted
                    + financialPaymentsDeleted + outboxDeleted;
        }
    }
}
