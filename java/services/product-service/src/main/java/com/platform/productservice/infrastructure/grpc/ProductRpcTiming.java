package com.platform.productservice.infrastructure.grpc;

import io.grpc.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/** Correlates server handling with Checkout transport time without logging SKU IDs or bodies. */
@Component
@GlobalServerInterceptor
public final class ProductRpcTiming implements ServerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ProductRpcTiming.class);
    private static final Metadata.Key<String> REQUEST_ID = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACEPARENT = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private final MeterRegistry meters;

    public ProductRpcTiming(MeterRegistry meters) {
        this.meters = meters;
    }

    /** contracts ADR-002 format; anything else is logged as "-" and never rejects the call. */
    private static final java.util.regex.Pattern REQUEST_ID_FORMAT =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final java.util.regex.Pattern TRACEPARENT_FORMAT =
            java.util.regex.Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    static String requestId(String header) {
        return header != null && REQUEST_ID_FORMAT.matcher(header).matches() ? header : "-";
    }

    static String traceId(String traceparent) {
        return traceparent != null && TRACEPARENT_FORMAT.matcher(traceparent).matches()
                ? traceparent.substring(3, 35) : "-";
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                              ServerCallHandler<ReqT, RespT> next) {
        if (!call.getMethodDescriptor().getBareMethodName().equals("GetBatchSkus")) {
            return next.startCall(call, headers);
        }
        long started = System.nanoTime();
        String requestId = requestId(headers.get(REQUEST_ID));
        String traceId = traceId(headers.get(TRACEPARENT));
        ServerCall<ReqT, RespT> timed = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long elapsed = System.nanoTime() - started;
                Timer.builder("product.grpc.server.handling").tag("method", "GetBatchSkus")
                        .tag("status", status.getCode().name()).register(meters).record(elapsed, TimeUnit.NANOSECONDS);
                log.info("Product RPC handled method=GetBatchSkus grpc_status={} duration_ms={} request_id={} trace_id={}",
                        status.getCode().name(), TimeUnit.NANOSECONDS.toMillis(elapsed), requestId, traceId);
                super.close(status, trailers);
            }
        };
        return next.startCall(timed, headers);
    }
}
