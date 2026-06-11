package com.platform.productservice.infrastructure.grpc;

import io.grpc.*;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Global logging interceptor for all gRPC calls.
 * Logs method name, execution time, and status with MDC context for distributed tracing.
 */
@GrpcGlobalServerInterceptor
public class GrpcLoggingInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcLoggingInterceptor.class);
    private static final String MDC_TRACE_ID = "grpc.traceId";
    private static final String MDC_METHOD = "grpc.method";

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String methodName = call.getMethodDescriptor().getFullMethodName();
        String traceId = UUID.randomUUID().toString().substring(0, 8);

        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_METHOD, methodName);

        long startTime = System.nanoTime();
        log.info("[{}] gRPC call started: method={}", traceId, methodName);

        ServerCall<ReqT, RespT> wrappedCall = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                log.info("[{}] gRPC call completed: method={}, status={}, elapsedMs={}",
                        traceId, methodName, status.getCode(), elapsedMs);
                MDC.remove(MDC_TRACE_ID);
                MDC.remove(MDC_METHOD);
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> listener = next.startCall(wrappedCall, headers);

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            @Override
            public void onComplete() {
                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                log.debug("[{}] gRPC call onComplete: method={}, elapsedMs={}",
                        traceId, methodName, elapsedMs);
                super.onComplete();
            }

            @Override
            public void onReady() {
                log.debug("[{}] gRPC call onReady: method={}", traceId, methodName);
                super.onReady();
            }
        };
    }
}
