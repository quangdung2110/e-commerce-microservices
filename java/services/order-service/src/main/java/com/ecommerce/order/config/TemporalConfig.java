package com.ecommerce.order.config;

import com.ecommerce.order.worker.TemporalWorkflowGateway;
import com.ecommerce.order.worker.WorkflowGateway;
import com.ecommerce.order.workflow.CheckoutWorkflowImpl;
import com.ecommerce.order.workflow.FinalizationWorkflowImpl;
import com.ecommerce.order.workflow.OrderActivities;
import com.ecommerce.order.workflow.ReconciliationWorkflowImpl;
import com.ecommerce.order.workflow.RefundWorkflowImpl;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
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
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Temporal client and the worker for the three Order task queues (operations.md §8). */
@Configuration
@ConditionalOnProperty(prefix = "order.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(OrderProperties properties, Environment environment)
            throws IOException {
        var temporal = properties.temporal();
        var options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporal.target());
        configureTransport(options, temporal, environment.matchesProfiles("production"));
        return WorkflowServiceStubs.newServiceStubs(options.build());
    }

    static void configureTransport(WorkflowServiceStubsOptions.Builder options, OrderProperties.Temporal temporal,
                                   boolean production) throws IOException {
        var transport = temporal.transport();
        if (transport == null || transport.mode() == null) {
            throw new IllegalArgumentException("Temporal TLS mode must be configured");
        }
        if (transport.mode() == OrderProperties.TemporalTlsMode.PLAINTEXT) {
            if (production) {
                throw new IllegalArgumentException("Temporal TLS is required in the production profile");
            }
            return;
        }
        if (!StringUtils.hasText(temporal.target()) || !StringUtils.hasText(temporal.namespace())) {
            throw new IllegalArgumentException("Temporal TLS requires a target and namespace");
        }
        if (transport.mode() == OrderProperties.TemporalTlsMode.TLS) {
            String apiKey = Files.readString(requiredFile(transport.apiKeyFile(), "API key")).strip();
            if (apiKey.isEmpty()) {
                throw new IllegalArgumentException("Temporal API key file is empty");
            }
            options.addApiKey(() -> apiKey);
            if (!StringUtils.hasText(transport.caFile())) {
                options.setEnableHttps(true);
                return;
            }
        }

        SslContextBuilder context = GrpcSslContexts.forClient();
        if (StringUtils.hasText(transport.caFile())) {
            context.trustManager(requiredFile(transport.caFile(), "CA").toFile());
        }
        if (transport.mode() == OrderProperties.TemporalTlsMode.MTLS) {
            context.keyManager(requiredFile(transport.certFile(), "client certificate").toFile(),
                    requiredFile(transport.keyFile(), "client key").toFile());
        }
        options.setSslContext(context.build());
    }

    private static Path requiredFile(String value, String purpose) {
        Path file = StringUtils.hasText(value) ? Path.of(value) : null;
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalArgumentException("A readable Temporal " + purpose + " file is required");
        }
        return file;
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
