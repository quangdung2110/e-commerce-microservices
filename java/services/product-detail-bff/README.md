# Product Detail BFF

Read composition for the product detail page: one `GET /api/v1/storefront/products/{slug}`
that combines Product buyer-read, the Inventory availability batch and the Review PDP
summary. No database, no Kafka, no cache. Design, contracts and runbooks live in
[docs/product-detail-bff](../../../docs/product-detail-bff/README.md); configuration is in
[operations.md](../../../docs/product-detail-bff/operations.md#cấu-hình).

| Package | Role |
|---|---|
| `api` | HTTP endpoint, request/trace ID normalisation, problem+json |
| `compose` | WF-PDP-001: Product, then Inventory and Review in parallel, then compose; media URLs; PDP metrics |
| `client` | One `WebClient` per dependency and tolerant-reader payloads with contract checks |
| `resilience` | `DependencyGuard`: flags, deadline budget, timeout, circuit breaker, bulkhead, outcome classification |
| `config` | `PdpProperties`, validated at startup |

## Build and test

There is no JDK on the development machine; build in the Maven container. Tests use
WireMock for the three dependencies and read the OpenAPI contracts from `contracts/`, so
mount the repository root.

```bash
docker run --rm -v "//c/Users/ASUS/MyProjects/e-commerce-microservices://repo" -v "pdp-bff-mvn-repo://root/.m2" -w //repo/java/services/product-detail-bff maven:3.9-eclipse-temurin-21 mvn -B test
```
