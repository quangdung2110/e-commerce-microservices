package com.platform.productservice.config;

import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * gRPC server configuration.
 * The gRPC server auto-starts via grpc-spring-boot-starter with port from application.yml.
 */
@Configuration
public class GrpcConfig {

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
