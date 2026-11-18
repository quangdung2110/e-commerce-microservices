package com.platform.productservice.infrastructure.grpc;

import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.platform.productservice.config.ProductProperties;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.function.Supplier;

/**
 * Caller check for {@code ProductService} (contracts ADR-004, Product ADR-022).
 *
 * <p>The mesh authorizes the calling principal and is the real boundary; this is the second layer, the
 * same as the allowlists of the HTTP routes. The caller names itself in {@code x-service-identity}: a missing
 * value is {@code UNAUTHENTICATED}, a name outside {@code product.internal.grpc-clients} is
 * {@code PERMISSION_DENIED}. Only {@code ProductService} is checked, so the health and any other service on
 * the same port are untouched. The value is attribution, not proof of identity.
 */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class GrpcServiceIdentityInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> IDENTITY =
            Metadata.Key.of("x-service-identity", Metadata.ASCII_STRING_MARSHALLER);
    static final String REJECTED_COUNTER = "product.grpc.server.identity_rejected";

    private static final Logger log = LoggerFactory.getLogger(GrpcServiceIdentityInterceptor.class);

    private final ProductProperties properties;
    private final Supplier<MeterRegistry> meters;

    @Autowired
    public GrpcServiceIdentityInterceptor(ProductProperties properties, ObjectProvider<MeterRegistry> meters) {
        this.properties = properties;
        this.meters = meters::getIfAvailable;
    }

    GrpcServiceIdentityInterceptor(ProductProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = () -> meters;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                              ServerCallHandler<ReqT, RespT> next) {
        if (!ProductServiceGrpc.SERVICE_NAME.equals(call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        String caller = headers.get(IDENTITY);
        caller = caller == null ? null : caller.trim();
        if (caller == null || caller.isEmpty()) {
            return reject(call, "missing", ProductGrpcErrors.close(ProductGrpcError.SERVICE_IDENTITY_MISSING,
                    "Missing x-service-identity"));
        }
        if (!properties.getInternal().getGrpcClients().contains(caller)) {
            return reject(call, "forbidden", ProductGrpcErrors.close(ProductGrpcError.SERVICE_IDENTITY_NOT_ALLOWED,
                    "Service identity is not allowed on ProductService"));
        }
        return next.startCall(call, headers);
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, String reason,
                                                          ProductGrpcErrors.Result error) {
        MeterRegistry registry = meters.get();
        if (registry != null) {
            registry.counter(REJECTED_COUNTER, "reason", reason).increment();
        }
        log.warn("ProductService call rejected reason={} method={}", reason,
                call.getMethodDescriptor().getBareMethodName());
        call.close(error.status(), error.trailers());
        return new ServerCall.Listener<>() {
        };
    }
}
