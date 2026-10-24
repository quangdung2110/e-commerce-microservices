package observability

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	collector "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	"google.golang.org/protobuf/proto"
)

func TestRequestIDContract(t *testing.T) {
	for _, input := range []string{"", "bad", "bad id with spaces", strings.Repeat("x", 129), "bad/script"} {
		actual := NormalizeRequestID(input)
		if !requestIDPattern.MatchString(actual) || actual == input {
			t.Fatalf("invalid input %q produced %q", input, actual)
		}
	}
	if NormalizeRequestID("checkout:req.1234") != "checkout:req.1234" {
		t.Fatal("valid ID changed")
	}
}

type closingTransport struct{ closed bool }

func (t *closingTransport) RoundTrip(*http.Request) (*http.Response, error) { return nil, nil }
func (t *closingTransport) CloseIdleConnections()                           { t.closed = true }

func TestInstrumentedTransportClosesTheOriginalPool(t *testing.T) {
	base := &closingTransport{}
	client := &http.Client{Transport: Transport(base)}
	client.CloseIdleConnections()
	if !base.closed {
		t.Fatal("instrumentation hid connection pool cleanup")
	}
	if client.Transport.(interface{ Unwrap() http.RoundTripper }).Unwrap() != base {
		t.Fatal("underlying pool changed")
	}
}

func TestHTTPStatusAndCardinality(t *testing.T) {
	m := NewHTTPMetrics()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /orders/{id}", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(503) })
	h := m.Wrap(mux)
	for _, path := range []string{"/orders/user-secret-1", "/orders/user-secret-2", "/unmatched/private-1", "/unmatched/private-2"} {
		r := httptest.NewRequest("GET", path, nil)
		r.Header.Set("X-Request-Id", "invalid id")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if !requestIDPattern.MatchString(w.Header().Get("X-Request-Id")) {
			t.Fatal("no normalized response ID")
		}
	}
	w := httptest.NewRecorder()
	m.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/metrics", nil))
	text := w.Body.String()
	for _, secret := range []string{"user-secret", "private-1", "private-2", "invalid id"} {
		if strings.Contains(text, secret) {
			t.Fatalf("unbounded identifier leaked into metrics: %s", secret)
		}
	}
	for _, expected := range []string{`route="/orders/{id}",status="503"} 2`, `route="unmatched",status="404"} 2`, "go_goroutines", "process_cpu_seconds_total", "http_server_request_duration_seconds_bucket"} {
		if !strings.Contains(text, expected) {
			t.Errorf("missing signal %s", expected)
		}
	}
}

func TestServerSpanContinuesW3CParent(t *testing.T) {
	old := otel.GetTracerProvider()
	defer otel.SetTracerProvider(old)
	recorder := tracetest.NewSpanRecorder()
	provider := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(recorder))
	defer provider.Shutdown(context.Background())
	otel.SetTracerProvider(provider)
	r := httptest.NewRequest("GET", "/orders/secret", nil)
	r.Header.Set("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
	r, span := StartHTTP(r)
	if TraceID(r.Context()) != "4bf92f3577b34da6a3ce929d0e0e4736" {
		t.Fatal("trace disconnected")
	}
	FinishHTTP(span, "GET", "/orders/{id}", 503)
	ended := recorder.Ended()
	if len(ended) != 1 || ended[0].Parent().SpanID().String() != "00f067aa0ba902b7" || ended[0].Name() != "GET /orders/{id}" {
		t.Fatal("incorrect span parent or name")
	}
}

func TestOTLPExportAndShutdownDrain(t *testing.T) {
	old := otel.GetTracerProvider()
	defer otel.SetTracerProvider(old)
	received := make(chan *collector.ExportTraceServiceRequest, 2)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/traces" {
			t.Errorf("wrong OTLP path: %s", r.URL.Path)
		}
		data, _ := io.ReadAll(r.Body)
		var payload collector.ExportTraceServiceRequest
		if err := proto.Unmarshal(data, &payload); err != nil {
			t.Errorf("invalid OTLP protobuf: %v", err)
		}
		received <- &payload
		w.Header().Set("Content-Type", "application/x-protobuf")
		w.WriteHeader(200)
	}))
	defer server.Close()
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", server.URL)
	t.Setenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "")
	t.Setenv("OTEL_TRACES_SAMPLER_ARG", "1")
	shutdown, err := Init("test-backend")
	if err != nil {
		t.Fatal(err)
	}
	_, span := StartHTTP(httptest.NewRequest("GET", "/", nil))
	FinishHTTP(span, "GET", "/", 200)
	shutdown()
	select {
	case payload := <-received:
		if len(payload.ResourceSpans) != 1 || len(payload.ResourceSpans[0].ScopeSpans) == 0 {
			t.Fatal("span lost during export")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no OTLP request")
	}
}
