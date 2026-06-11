package com.platform.productservice.config;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Nullable;

/**
 * Security configuration for gRPC server.
 * <p>
 * For internal service-to-service communication, uses a shared secret header validation.
 * Each internal service must include the shared secret in the gRPC metadata.
 * mTLS can be enabled by configuring TLS certificates in the application properties.
 */
@Configuration
public class GrpcSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcSecurityConfig.class);

    public static final String INTERNAL_SECRET_HEADER = "x-internal-service-secret";

    @Value("${grpc.security.internal-secret:change-me-in-production}")
    private String internalSecret;

    /**
     * Global server interceptor that validates internal service requests.
     * Each service-to-service call must include the shared secret in metadata.
     */
    @Bean
    @GrpcGlobalServerInterceptor
    public ServerInterceptor internalServiceAuthInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {

                String methodName = call.getMethodDescriptor().getFullMethodName();
                String serviceName = methodName.contains("/") ? methodName.split("/")[0] : methodName;

                String providedSecret = headers.get(Metadata.Key.of(INTERNAL_SECRET_HEADER, Metadata.ASCII_STRING_MARSHALLER));

                if (providedSecret == null || !internalSecret.equals(providedSecret)) {
                    log.warn("gRPC auth failed for method {} - missing or invalid internal secret", methodName);
                    call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid internal service secret"), headers);
                    return new ServerCall.Listener<>() {};
                }

                log.debug("gRPC auth succeeded for method {}", methodName);
                return next.startCall(call, headers);
            }
        };
    }
}
