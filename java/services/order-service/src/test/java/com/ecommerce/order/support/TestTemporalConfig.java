package com.ecommerce.order.support;

import com.ecommerce.order.client.Gateways;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.config.TemporalConfig;
import com.ecommerce.order.worker.TemporalWorkflowGateway;
import com.ecommerce.order.worker.WorkflowGateway;
import com.ecommerce.order.workflow.OrderActivities;
import io.temporal.testing.TestWorkflowEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * In-memory Temporal (time only advances through {@code env.sleep}) wired to the real
 * activity bean, plus contract fakes for the three outbound services. The application
 * Clock follows Temporal test time so DB deadlines and workflow timers agree.
 */
@TestConfiguration
public class TestTemporalConfig {

    @Bean(destroyMethod = "close")
    public TestWorkflowEnvironment testWorkflowEnvironment() {
        return TestWorkflowEnvironment.newInstance();
    }

    @Bean
    @Primary
    public Clock temporalClock(TestWorkflowEnvironment env) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(env.currentTimeMillis());
            }
        };
    }

    @Bean
    public WorkflowGateway testWorkflowGateway(TestWorkflowEnvironment env, OrderProperties properties,
                                               OrderActivities activities) {
        TemporalConfig.registerWorkers(env.getWorkerFactory(), properties, activities);
        env.start();
        return new TemporalWorkflowGateway(env.getWorkflowClient());
    }

    @Bean
    public FakeRemotes fakeRemotes(Clock clock) {
        return new FakeRemotes(clock);
    }

    @Bean
    @Primary
    public Gateways.Inventory fakeInventory(FakeRemotes fakes) {
        return fakes.inventory();
    }

    @Bean
    @Primary
    public Gateways.Promotion fakePromotion(FakeRemotes fakes) {
        return fakes.promotion();
    }

    @Bean
    @Primary
    public Gateways.Payment fakePayment(FakeRemotes fakes) {
        return fakes.payment();
    }
}
