package com.ecommerce.order.config;

import com.ecommerce.order.worker.TemporalWorkflowGateway;
import com.ecommerce.order.worker.WorkflowGateway;
import com.ecommerce.order.workflow.CheckoutWorkflowImpl;
import com.ecommerce.order.workflow.FinalizationWorkflowImpl;
import com.ecommerce.order.workflow.OrderActivities;
import com.ecommerce.order.workflow.ReconciliationWorkflowImpl;
import com.ecommerce.order.workflow.RefundWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Temporal client and the worker for the three Order task queues (operations.md §8). */
@Configuration
@ConditionalOnProperty(prefix = "order.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(OrderProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.temporal().target())
                .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, OrderProperties properties) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder()
                .setNamespace(properties.temporal().namespace())
                .build());
    }

    @Bean
    public WorkflowGateway workflowGateway(WorkflowClient client) {
        return new TemporalWorkflowGateway(client);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client, OrderProperties properties, OrderActivities activities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        if (properties.temporal().workerEnabled()) {
            registerWorkers(factory, properties, activities);
        }
        return factory;
    }

    /** Registers every workflow type and the activity bean on all three queues. */
    public static void registerWorkers(WorkerFactory factory, OrderProperties properties, OrderActivities activities) {
        for (String queue : List.of(properties.temporal().checkoutQueue(), properties.temporal().refundQueue(),
                properties.temporal().reconciliationQueue())) {
            Worker worker = factory.newWorker(queue);
            worker.registerWorkflowImplementationTypes(CheckoutWorkflowImpl.class, RefundWorkflowImpl.class,
                    FinalizationWorkflowImpl.class, ReconciliationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
        }
    }

    @Bean
    public SmartLifecycle temporalWorkerLifecycle(WorkerFactory factory, OrderProperties properties) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                if (properties.temporal().workerEnabled()) {
                    factory.start();
                }
                running = true;
            }

            @Override
            public void stop() {
                if (properties.temporal().workerEnabled()) {
                    factory.shutdown();
                }
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
