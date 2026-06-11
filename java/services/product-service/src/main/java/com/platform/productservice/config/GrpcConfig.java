package com.platform.productservice.config;

import com.platform.productservice.infrastructure.grpc.GrpcExceptionInterceptor;
import com.platform.productservice.infrastructure.grpc.GrpcLoggingInterceptor;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.services.HealthServiceManager;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * gRPC server configuration.
 * The gRPC server auto-starts via grpc-spring-boot-starter with port from application.yml.
 * This configuration registers interceptors and health services.
 */
@Configuration
public class GrpcConfig {

    @Value("${grpc.server.port:9090}")
    private int grpcPort;

    @Value("${grpc.server.reflection-service-enabled:true}")
    private boolean reflectionEnabled;

    /**
     * Provides the gRPC health service for Kubernetes probes.
     */
    @Bean
    public HealthServiceManager healthServiceManager() {
        return new HealthServiceManager();
    }

    /**
     * Logs all registered gRPC service beans for debugging purposes.
     */
    @Autowired
    void logGrpcServices(ApplicationContext context) {
        Map<String, Object> grpcServices = context.getBeansWithAnnotation(GrpcService.class);
        grpcServices.forEach((name, bean) -> {
            // Interceptors are applied via @GrpcGlobalInterceptor annotation on each interceptor class
        });
    }
}
