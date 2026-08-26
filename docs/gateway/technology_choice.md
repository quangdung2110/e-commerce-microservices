# Quyết định công nghệ cho API Gateway

## Quyết định

Dùng **Traefik Proxy v3.7.1** làm edge gateway, service **`gateway-auth` ForwardAuth** độc lập cho JWT validation và identity enrichment, **Redis-backed distributed rate limiting**, và **OpenTelemetry Collector** làm telemetry ingress. Hành vi vận hành nằm trong [runtime-contract.md](runtime-contract.md).

## Bối cảnh

Nền tảng cần dynamic routing, TLS, health-aware forwarding, bảo vệ chống abuse, JWT/JWKS validation kèm revocation, và request observability. Thiết kế Traefik v3.0 cũ phụ thuộc vào Go plugin `.so` compiled, không phải deployment model phù hợp/được hỗ trợ cho Traefik middleware plugins và tạo coupling không cần thiết giữa security logic với proxy runtime.

## Vì sao chọn Traefik

Traefik cung cấp cloud-native discovery, routing, TLS, health checks, edge middleware chuẩn, Prometheus metrics và OpenTelemetry tracing, đồng thời giữ routing tách khỏi application services. Đây là edge traffic manager, không phải nơi đặt domain logic.

## Quyết định xác thực

| Lựa chọn | Quyết định | Lý do |
|---|---|---|
| Go plugin `.so` compiled | Loại bỏ | Loading model không hợp lệ/khó ổn định, ABI/build coupling |
| Local/catalog plugin | Không dùng cho auth | Experimental loader và interpreter/dependency restrictions không phù hợp security-critical path |
| `gateway-auth` qua ForwardAuth | Chọn | Service lifecycle chuẩn, test độc lập, timeout policy rõ ràng, telemetry và rollback |
| Chỉ validate ở từng service | Loại bỏ | Lặp public-edge checks, dù service vẫn phải authorization business actions |

`gateway-auth` sở hữu validation issuer, audience, algorithm, expiry, JWKS, denylist và revocation; nó chỉ trả identity headers tối thiểu sau khi thành công.

## Quyết định giới hạn tốc độ

| Lựa chọn | Quyết định | Lý do |
|---|---|---|
| In-memory limiter theo instance | Loại bỏ khi scale-out | Mỗi gateway replica cấp quota riêng |
| Redis-backed Traefik rate limit | Chọn | Shared quota ở edge |
| Dedicated rate-limit service | Phương án dự phòng | Dùng khi quota dimensions/algorithms vượt native support |

Cần upgrade khỏi Traefik v3.0 vì RateLimit configuration của phiên bản này không có Redis-backed distributed state. Limits và failure behavior do runtime contract kiểm soát.

## Quyết định khả năng quan sát và triển khai

Traefik và services export OTLP tới OpenTelemetry Collector thay vì coupling từng producer trực tiếp với Jaeger. Điều này tập trung batching, routing, thay đổi backend và policy enforcement. Access logs là JSON; Prometheus scrape private metrics endpoint.

Docker Compose vẫn là local integration tooling. Kubernetes Gateway API/CRDs là production route source. Production images dùng exact tags kèm digests; cấm `latest`.

## Hệ quả

Lợi ích: edge cloud-native nhẹ, authentication độc lập và dễ test, quota đúng khi nhiều replica, một correlation model cho logs/metrics/traces, không phụ thuộc Docker socket ở production.

Chi phí: `gateway-auth`, Redis, OTel Collector là operational components; protected traffic fail closed khi authentication dependency lỗi; limits cần load test; downstream services phải tự authorization và telemetry.

Spring Cloud Gateway vẫn là lựa chọn trong tương lai nếu programmable gateway tích hợp Java sâu quan trọng hơn footprint của Traefik. Nó không được chọn trong refactor này.
