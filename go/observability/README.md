# Shared backend observability

This module owns W3C propagation, bounded request IDs, HTTP request/error/latency metrics, Go/process collectors and optional OTLP export. Service-specific metrics and business context remain in their existing packages.

Call `Init(serviceName)` once at startup and defer the returned shutdown function. Configure `OTEL_EXPORTER_OTLP_ENDPOINT` (HTTP) and optionally `OTEL_TRACES_SAMPLER_ARG` in `[0,1]`. No collector connection is required to start the service.

Gin middleware precedes service correlation and recovery middleware. Its defer observes the final response after recovery; route templates and `unmatched` are used instead of raw URLs. Combine `HTTPMetrics.Handler(businessRegistry)` with the business registry at the service's private `/metrics` route. The net/http wrapper uses ServeMux route patterns and preserves normalized response IDs.

Run tests with `go test ./...` from this module. Service modules reference it through `replace ... => ../../observability`. Their Docker build context is `go/`, for example:

```powershell
docker build -f go/services/cart-service/Dockerfile -t cart-service:local go
```

See [the local observability runbook](../../docs/observability/runbook.md) for the full Compose pipeline and verification commands.
