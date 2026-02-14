package com.example.common.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal Configuration for Saga Orchestration
 * 
 * Architecture Flow:
 * 1. Order Service confirms order → Start Temporal Workflow
 * 2. Workflow executes Activities via RabbitMQ:
 *    - ReserveInventory → inventory-service queue
 *    - ProcessPayment → payment-service queue (timeout 30 min)
 *    - CreateShipment → shipping-service queue
 * 3. Each service processes task → Publish Kafka event (success/failure)
 * 4. Workflow listens to events → Update Order status
 * 5. On failure → Execute compensation (rollback)
 * 
 * Temporal ensures:
 * - Exactly-once execution
 * - Automatic retry with backoff
 * - Timeout handling
 * - Compensation on failure
 */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnProperty(prefix = "temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class TemporalConfig {
    
    private final TemporalProperties properties;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(properties.getHost() + ":" + properties.getPort())
            .build();
        
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
            .setNamespace(properties.getNamespace())
            .build();
        
        return WorkflowClient.newInstance(serviceStubs, options);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        return WorkerFactory.newInstance(workflowClient);
    }
}
