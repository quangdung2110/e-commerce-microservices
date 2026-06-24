package com.platform.inventoryservice.temporal;

import com.platform.inventoryservice.config.InventoryProperties;
import com.platform.inventoryservice.temporal.activity.DeductActivityImpl;
import com.platform.inventoryservice.temporal.activity.ReserveActivityImpl;
import com.platform.inventoryservice.temporal.activity.RollbackActivityImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TemporalWorkerBootstrap {

    private final InventoryProperties props;
    private final ReserveActivityImpl reserveImpl;
    private final DeductActivityImpl deductImpl;
    private final RollbackActivityImpl rollbackImpl;

    private WorkerFactory factory;

    @PostConstruct
    void start() {
        WorkflowClient client = WorkflowClient.newInstance(
            WorkflowServiceStubs.newInstance(
                WorkflowServiceStubsOptions.newBuilder()
                    .setTarget(props.getTemporal().getTarget())
                    .build()),
            WorkflowClientOptions.newBuilder()
                .setNamespace(props.getTemporal().getNamespace())
                .setIdentity(props.getTemporal().getWorkerIdentity())
                .build());

        factory = WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder()
            .setMaxWorkflowThreadCount(20)
            .build());

        Worker worker = factory.newWorker(props.getTemporal().getTaskQueue());
        worker.registerActivitiesImplementations(reserveImpl, deductImpl, rollbackImpl);

        // factory.start() is non-blocking, it starts polling threads in the background
        try {
            factory.start();
            log.info("Temporal Worker started: target={}, namespace={}, taskQueue={}",
                     props.getTemporal().getTarget(),
                     props.getTemporal().getNamespace(),
                     props.getTemporal().getTaskQueue());
        } catch (Exception e) {
            log.error("Temporal worker failed to start", e);
        }
    }

    @PreDestroy
    void stop() {
        if (factory != null) {
            factory.shutdown();
            log.info("Temporal Worker shutdown complete");
        }
    }
}
