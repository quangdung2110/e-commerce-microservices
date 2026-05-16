# Phân tích Traefik cho API Gateway

Dựa trên thiết kế chi tiết (JWT verification, Redis denylist, JWKS local cache + Pub/Sub invalidation, rate limiting, OpenTelemetry), Traefik mang lại nhiều lợi thế đặc biệt trong môi trường cloud-native.
Traefik thuộc loại Server-side Discovery.
---

## 1. Native Integration với Docker / Kubernetes

**Yêu cầu từ thiết kế:** Dynamic Service Discovery (Consul/Eureka/K8s DNS), load balancing (Round Robin, Least Connection).

Traefik tự động phát hiện endpoints từ Docker labels, Kubernetes Ingress/CRD, Consul, Etcd... mà không cần Service Registry client riêng.

Khi deploy `user-service` lên K8s, chỉ cần gán label `traefik.http.services.user-service.loadbalancer.server.port=8080`, Traefik tự động route `/api/users/**` đến service đó. Không cần viết code định tuyến động như Spring Cloud Gateway.
Client chỉ cần gửi request đến địa chỉ Traefik (ví dụ `http://api-gateway:80/api/users/...`). Traefik tra cứu danh sách instance (đã được đồng bộ) và forward đến một instance khỏe mạnh.

**Lợi ích:** Giảm đáng kể công sức cấu hình và bảo trì, đặc biệt khi hệ thống có nhiều service.

---

## 2. Hiệu Năng Cao và Footprint Thấp

Traefik viết bằng Go, chạy như một binary duy nhất, không cần JVM. So sánh với Spring Cloud Gateway (Java):

| Tiêu chí | Traefik | Spring Cloud Gateway |
|---|:---:|:---:|
| Memory footprint (idle) | ~30-50 MB | ~300-500 MB (JVM heap) |
| Startup time | < 1s | 5-15s |
| Throughput (plain reverse proxy) | ~50k req/s | ~20k req/s |
| CPU cho 10k req/s (JWT verify) | ~0.5 core | ~1-1.5 cores |

Với yêu cầu rate limiting và JWT verify cho mọi request, Traefik giúp tiết kiệm tài nguyên đáng kể.

---

## 3. Middleware Plugin System

Thiết kế yêu cầu các logic tùy chỉnh:

- Xác thực JWT với JWKS cache (go-cache hoặc ristretto)
- Query Redis denylist (`auth:denylist:{jti}`, `auth:revoked_before:{user_id}`)
- Subscribe Redis Pub/Sub để xóa cache JWKS
- Enrich headers (`X-User-Id`, `X-User-Roles`)

Traefik cho phép viết custom middleware bằng Go (dùng plugin system) hoặc gọi đến HTTP service (Pilot mode):

- Viết package Go, build thành `.so`, Traefik load động
- Truy cập được request/response, có thể đọc header, thân request, gọi Redis, cache local
- Hiệu năng gần như native

### Ví dụ Plugin JWT Authentication

```go
func (m *JwtAuth) ServeHTTP(rw http.ResponseWriter, req *http.Request, next http.HandlerFunc) {
    token := extractBearer(req.Header)
    kid := getKid(token)
    pubKey := m.getPublicKey(kid) // từ local cache
    if pubKey == nil {
        pubKey = m.fetchJWKS(kid) // gọi AuthService
    }
    if !verifyJWT(token, pubKey) {
        rw.WriteHeader(401)
        return
    }
    if m.isDenylisted(jti) || m.isRevoked(userId, iat) { // Redis query
        rw.WriteHeader(401)
        return
    }
    enrichHeaders(req, userId, roles)
    next(rw, req)
}
```

> **So sánh:** Spring Cloud Gateway cũng có thể viết filter, nhưng Traefik plugin nhẹ hơn, không bị ràng buộc bởi reactive programming.

---

## 4. Tích Hợp OpenTelemetry Native

Traefik v3.x hỗ trợ OpenTelemetry tracing và metrics out-of-the-box (cấu hình qua file hoặc env). Export trace về Jaeger hoặc Zipkin mà không cần thêm thư viện.

Đặc biệt, Traefik tự động sinh `traceparent` header cho mỗi request và truyền xuống backend. Logging có thể định dạng JSON và thêm `trace_id`, `span_id` thông qua `accessLogs.fields`.

> **So sánh:** Spring Cloud Gateway cũng làm được nhưng cần thêm Sleuth + Brave, phức tạp hơn.

---

## 5. Rate Limiting Linh Hoạt

Traefik có sẵn middleware RateLimit dựa trên local hoặc Redis. Cấu hình đơn giản qua YAML:

```yaml
http:
  middlewares:
    my-ratelimit:
      rateLimit:
        average: 100
        burst: 200
        period: 1s
        sourceCriterion:
          ipStrategy: {}
          redis:
            endpoints: ["redis:6379"]
```

Không cần viết code. Spring Cloud Gateway cũng có RedisRateLimiter nhưng phải cấu hình thông qua Java.

---

## 6. Quản Lý Cấu Hình Dynamic

Traefik đọc cấu hình từ file, Kubernetes CRD, Consul KV, Redis... và reload nóng (không restart). Khi thêm route mới hoặc cập nhật rate limit, Traefik áp dụng ngay.

> **So sánh:** Spring Cloud Gateway cũng có thể refresh context nhưng phức tạp hơn (Spring Cloud Bus, Actuator refresh).

---

## 7. Bảo Mật Mặc Định

Traefik hỗ trợ tự động phát hiện và gia hạn Let's Encrypt SSL. Nếu hệ thống yêu cầu TLS termination tại Gateway, Traefik làm việc này rất tốt.

---

## Hạn Chế Cần Cân Nhắc

| Hạn chế | Mức độ | Giải pháp |
|---|---|---|
| Redis Pub/Sub để invalidate JWKS cache không có sẵn, phải tự code plugin | Trung bình | Viết plugin Go (~200 dòng) |
| Cần build plugin riêng cho mỗi phiên bản Traefik (plugin ABI phụ thuộc version Go) | Thấp | Dùng HTTP middleware thay vì Go plugin |
| Không có sẵn circuit breaker hoặc retry phức tạp như Resilience4j | Thấp | Dùng ForwardAuth middleware hoặc tích hợp service mesh (Linkerd) |
| Team quen Java sẽ khó khăn khi viết plugin Go | Cao | Chọn Spring Cloud Gateway sẽ dễ hơn |

---

## Kết Luận

Traefik phù hợp với hệ thống microservices cloud-native cần:
- Performance cao, resource thấp
- Dynamic configuration không restart
- Custom middleware nhẹ (Go)
- Observability (OpenTelemetry) có sẵn

**Không phù hợp** nếu team chuyên Java và không muốn đầu tư thời gian vào Go plugin.
