package com.ecommerce.checkout.observability;

import com.ecommerce.checkout.problem.ApiException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.function.Function;

/**
 * Child spans for the work only Checkout knows about (operations.md, "Quan sat"): the four use cases
 * {@code checkout.preview}, {@code checkout.voucher_options}, {@code checkout.submit} and
 * {@code checkout.recovery}, one {@code checkout.dependency} per guarded call and one
 * {@code checkout.redis} per script or command.
 *
 * <p>The span is the Micrometer {@link Observation} of a registry that carries only the tracing handler.
 * A span-only registry keeps these spans out of the meters: the metric names of operations.md stay the
 * contract, and a second timer named {@code checkout.redis} cannot collide with the one Checkout already
 * records. The observation is also published in the Reactor context under the key Spring's server and
 * {@code WebClient} instrumentation read, so the HTTP client span of a dependency call nests under its
 * {@code checkout.dependency} span, which nests under the use case, which nests under the server span.
 *
 * <p>No thread-local scope is opened: the work hops threads, and a scope held across a hop leaks the span
 * to whatever runs next on the event loop.
 */
@Component
public class Spans {

    private final ObservationRegistry registry = ObservationRegistry.create();

    public Spans(ObjectProvider<DefaultTracingObservationHandler> tracing) {
        DefaultTracingObservationHandler handler = tracing.getIfAvailable();
        if (handler != null) {
            registry.observationConfig().observationHandler(handler);
        }
    }

    /**
     * Runs {@code body} inside a span named {@code name}.
     *
     * @param keyValues alternating key and value; bounded values only, as for a metric label
     */
    public <T> Mono<T> in(String name, Mono<T> body, String... keyValues) {
        return in(name, span -> body, keyValues);
    }

    /** Like {@link #in(String, Mono, String...)}, for a body that adds key values once it knows its outcome. */
    public <T> Mono<T> in(String name, Function<Observation, Mono<T>> body, String... keyValues) {
        return Mono.deferContextual(context -> {
            Observation span = Observation.createNotStarted(name, registry)
                    .parentObservation(context.getOrDefault(ObservationThreadLocalAccessor.KEY, null));
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                span.lowCardinalityKeyValue(keyValues[i], keyValues[i + 1]);
            }
            span.start();
            return body.apply(span)
                    .contextWrite(inner -> span.isNoop() ? inner : inner.put(ObservationThreadLocalAccessor.KEY, span))
                    .doOnError(error -> fail(span, error))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            span.lowCardinalityKeyValue("cancelled", "true");
                        }
                        span.stop();
                    });
        });
    }

    /** A business problem is an answer, not a failure of the span; a 5xx problem or any other error is one. */
    private static void fail(Observation span, Throwable error) {
        if (error instanceof ApiException api) {
            span.lowCardinalityKeyValue("problem", api.problem().code().name());
            if (api.problem().status() < 500) {
                return;
            }
        }
        span.error(error);
    }
}
