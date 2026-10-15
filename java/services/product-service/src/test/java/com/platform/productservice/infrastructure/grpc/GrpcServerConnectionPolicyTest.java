package com.platform.productservice.infrastructure.grpc;

import net.devh.boot.grpc.server.config.GrpcServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contracts ADR-007: Product recycles long-lived client connections so new pods receive calls, and permits the
 * client keepalive that detects dead connections. The values come from {@code application.yml}, so a deleted or
 * misspelled key shows up here.
 */
class GrpcServerConnectionPolicyTest {

    private GrpcServerProperties bind(String... overrides) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        List<org.springframework.core.env.PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("grpc.server", GrpcServerProperties.class).get();
    }

    @Test
    void connectionsAreRecycledAfterFiveMinutesWithAGracePeriod() throws Exception {
        GrpcServerProperties properties = bind();

        assertThat(properties.getMaxConnectionAge()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getMaxConnectionAgeGrace()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void clientKeepaliveIsPermittedOnlyWhileCallsAreActive() throws Exception {
        GrpcServerProperties properties = bind();

        // Cart and Checkout ping every 30 s; a permitted interval above that would answer them with too_many_pings.
        assertThat(properties.getPermitKeepAliveTime()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.isPermitKeepAliveWithoutCalls()).isFalse();
    }
}
