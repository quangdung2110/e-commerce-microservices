package com.platform.inventoryservice.worker.ledger;

import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaLedgerWorker {

    private final KafkaLedgerConsumer consumer;
    private final MutationEventProcessor processor;
    private final InventoryMetrics metrics;

    // KafkaLedgerConsumer uses @PostConstruct and @PreDestroy to manage its own 
    // background thread lifecycle. This worker class serves as a high-level 
    // orchestrator/module marker as defined in the system architecture.
    
}
