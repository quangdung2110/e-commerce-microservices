package com.platform.productservice.infrastructure.grpc;

import com.platform.productservice.domain.exception.CategoryNotFoundException;
import com.platform.productservice.domain.exception.ProductNotFoundException;
import com.platform.productservice.domain.exception.SkuNotFoundException;
import io.grpc.*;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global exception interceptor for all gRPC calls.
 * Catches exceptions and maps them to appropriate gRPC Status codes.
 */
@GrpcGlobalServerInterceptor
public class GrpcExceptionInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
        return new ExceptionHandlingListener<>(delegate, call, headers);
    }

    private class ExceptionHandlingListener<ReqT, RespT> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

        private final ServerCall<ReqT, RespT> call;
        private final Metadata headers;

        ExceptionHandlingListener(ServerCall.Listener<ReqT> delegate,
                                  ServerCall<ReqT, RespT> call,
                                  Metadata headers) {
            super(delegate);
            this.call = call;
            this.headers = headers;
        }

        @Override
        public void onMessage(ReqT message) {
            try {
                super.onMessage(message);
            } catch (RuntimeException e) {
                handleException(e);
                throw e;
            }
        }

        private void handleException(Throwable e) {
            Status status;
            String description;

            if (e instanceof SkuNotFoundException || e instanceof ProductNotFoundException || e instanceof CategoryNotFoundException) {
                status = Status.NOT_FOUND;
                description = e.getMessage();
                log.warn("gRPC NOT_FOUND: {}", description);
            } else if (e instanceof IllegalArgumentException) {
                status = Status.INVALID_ARGUMENT;
                description = e.getMessage();
                log.warn("gRPC INVALID_ARGUMENT: {}", description);
            } else if (e instanceof io.grpc.StatusRuntimeException) {
                io.grpc.StatusRuntimeException grpcEx = (io.grpc.StatusRuntimeException) e;
                status = grpcEx.getStatus();
                description = grpcEx.getMessage();
                log.warn("gRPC StatusRuntimeException: status={}, message={}", status, description);
            } else {
                status = Status.INTERNAL;
                description = "Internal server error";
                log.error("gRPC INTERNAL error: {}", e.getMessage(), e);
            }

            call.close(status.withDescription(description), headers);
        }
    }
}
