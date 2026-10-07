package com.platform.paymentservice.application.port.out;

public interface FinancialCommitPort {
    void commit(CommitBatch batch);
}
