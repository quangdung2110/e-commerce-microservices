package com.ecommerce.checkout.client;

import io.grpc.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

/** Transport duration includes connection/queue/network/server work; it is not server CPU time. */
final class ProductCallTiming implements ClientInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ProductCallTiming.class);
    private final MeterRegistry meters;
    private final RequestContext context;

    ProductCallTiming(MeterRegistry meters, RequestContext context) {
        this.meters = meters;
        this.context = context;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                            CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                long started = System.nanoTime();
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                    private long headersNanos = -1;

                    @Override
                    public void onHeaders(Metadata metadata) {
                        headersNanos = System.nanoTime() - started;
                        Timer.builder("checkout.product.grpc.headers").register(meters)
                                .record(headersNanos, TimeUnit.NANOSECONDS);
                        super.onHeaders(metadata);
                    }

                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        long elapsed = System.nanoTime() - started;
                        Timer.builder("checkout.product.grpc.transport").tag("status", status.getCode().name())
                                .register(meters).record(elapsed, TimeUnit.NANOSECONDS);
                        log.info("Product RPC completed", kv("method", "GetBatchSkus"),
                                kv("grpc_status", status.getCode().name()),
                                kv("duration_ms", TimeUnit.NANOSECONDS.toMillis(elapsed)),
                                kv("headers_ms", headersNanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(headersNanos)),
                                kv("request_id", context.requestId()), kv("trace_id", context.traceId()));
                        super.onClose(status, trailers);
                    }
                }, headers);
            }
        };
    }
}
