// Package observability provides the common HTTP signals and W3C context for backend services.
package observability

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,128}$`)
var propagator = propagation.TraceContext{}
var accessLogger = slog.New(slog.NewJSONHandler(os.Stdout, nil))

// Init enables export only when an OTLP endpoint is configured. Startup never waits for a collector.
// The returned function drains the batch queue with its own bounded shutdown deadline.
func Init(service string) (func(), error) {
	accessLogger = accessLogger.With("service", service)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagator))
	if os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") == "" && os.Getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") == "" {
		return func() {}, nil
	}
	ratio := 0.1
	if value := os.Getenv("OTEL_TRACES_SAMPLER_ARG"); value != "" {
		parsed, err := strconv.ParseFloat(value, 64)
		if err != nil || parsed < 0 || parsed > 1 {
			return nil, fmt.Errorf("OTEL_TRACES_SAMPLER_ARG must be between 0 and 1")
		}
		ratio = parsed
	}
	exporter, err := otlptracehttp.New(context.Background())
	if err != nil {
		return nil, fmt.Errorf("create trace exporter: %w", err)
	}
	provider := sdktrace.NewTracerProvider(
		sdktrace.WithResource(resource.NewSchemaless(attribute.String("service.name", service))),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(ratio))),
		sdktrace.WithBatcher(exporter, sdktrace.WithBatchTimeout(time.Second)),
	)
	otel.SetTracerProvider(provider)
	return func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = provider.Shutdown(ctx)
	}, nil
}

func NormalizeRequestID(value string) string {
	if requestIDPattern.MatchString(value) {
		return value
	}
	return uuid.NewString()
}

// TraceID returns only a validated W3C trace ID, including in pass-through/no-export mode.
func TraceID(ctx context.Context) string {
	sc := trace.SpanContextFromContext(ctx)
	if sc.IsValid() {
		return sc.TraceID().String()
	}
	return ""
}

func Extract(ctx context.Context, headers http.Header) context.Context {
	return propagator.Extract(ctx, propagation.HeaderCarrier(headers))
}

// EnsureContext also supports independently mounted correlation middleware in slice tests.
func EnsureContext(r *http.Request) *http.Request {
	if TraceID(r.Context()) == "" {
		return r.WithContext(Extract(r.Context(), r.Header))
	}
	return r
}

func Traceparent(ctx context.Context) string {
	carrier := propagation.MapCarrier{}
	propagator.Inject(ctx, carrier)
	return carrier.Get("traceparent")
}

func Transport(base http.RoundTripper) http.RoundTripper {
	if base == nil {
		base = http.DefaultTransport
	}
	return &observedTransport{RoundTripper: otelhttp.NewTransport(base, otelhttp.WithPropagators(propagator)), base: base}
}

// Keep connection-pool lifecycle available through instrumentation wrappers.
type observedTransport struct {
	http.RoundTripper
	base http.RoundTripper
}

func (t *observedTransport) Unwrap() http.RoundTripper { return t.base }
func (t *observedTransport) CloseIdleConnections() {
	if closer, ok := t.base.(interface{ CloseIdleConnections() }); ok {
		closer.CloseIdleConnections()
	}
}

// HTTPMetrics owns an isolated registry; applications can combine it with their business registry.
type HTTPMetrics struct {
	Registry *prometheus.Registry
	requests *prometheus.CounterVec
	duration *prometheus.HistogramVec
}

func NewHTTPMetrics() *HTTPMetrics {
	reg := prometheus.NewRegistry()
	m := &HTTPMetrics{Registry: reg,
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "http_server_requests_total", Help: "HTTP requests by method, route template and status."}, []string{"method", "route", "status"}),
		duration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "http_server_request_duration_seconds", Help: "HTTP server latency in seconds.",
			Buckets: []float64{.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10, 30}}, []string{"method", "route", "status"}),
	}
	reg.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}), m.requests, m.duration)
	return m
}

func (m *HTTPMetrics) Handler(other ...prometheus.Gatherer) http.Handler {
	return promhttp.HandlerFor(prometheus.Gatherers(append([]prometheus.Gatherer{m.Registry}, other...)), promhttp.HandlerOpts{})
}

func (m *HTTPMetrics) Observe(method, route string, status int, duration time.Duration) {
	// A malicious/unknown method must not create a series per arbitrary request value.
	switch method {
	case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT", "TRACE":
	default:
		method = "OTHER"
	}
	if route == "" {
		route = "unmatched"
	}
	code := strconv.Itoa(status)
	m.requests.WithLabelValues(method, route, code).Inc()
	m.duration.WithLabelValues(method, route, code).Observe(duration.Seconds())
}

func StartHTTP(r *http.Request) (*http.Request, trace.Span) {
	ctx := Extract(r.Context(), r.Header)
	ctx, span := otel.Tracer("ecommerce/http").Start(ctx, r.Method, trace.WithSpanKind(trace.SpanKindServer))
	return r.WithContext(ctx), span
}

func FinishHTTP(span trace.Span, method, route string, status int) {
	if route == "" {
		route = "unmatched"
	}
	span.SetName(method + " " + route)
	span.SetAttributes(attribute.String("http.request.method", method), attribute.String("http.route", route), attribute.Int("http.response.status_code", status))
	if status >= 500 {
		span.SetStatus(codes.Error, http.StatusText(status))
	}
	span.End()
}

// Wrap instruments net/http, including unmatched routes, without labelling raw URLs or query strings.
func (m *HTTPMetrics) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/metrics" {
			next.ServeHTTP(w, r)
			return
		}
		started := time.Now()
		r, span := StartHTTP(r)
		rid := NormalizeRequestID(r.Header.Get("X-Request-Id"))
		r.Header.Set("X-Request-Id", rid)
		w.Header().Set("X-Request-Id", rid)
		recorded := &responseWriter{ResponseWriter: w, status: 200}
		defer func() {
			route := r.Pattern
			if _, path, ok := strings.Cut(route, " "); ok {
				route = path
			}
			m.Observe(r.Method, route, recorded.status, time.Since(started))
			accessLogger.Info("request completed", "request_id", rid, "trace_id", TraceID(r.Context()),
				"method", r.Method, "operation", route, "status", recorded.status, "duration_ms", time.Since(started).Milliseconds())
			FinishHTTP(span, r.Method, route, recorded.status)
		}()
		next.ServeHTTP(recorded, r)
	})
}

type responseWriter struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (w *responseWriter) WriteHeader(code int) {
	if !w.wrote {
		w.status, w.wrote = code, true
		w.ResponseWriter.WriteHeader(code)
	}
}
func (w *responseWriter) Write(p []byte) (int, error) {
	if !w.wrote {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(p)
}
func (w *responseWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
