# Cart Service — Module 24: Deployment Plan

**Document:** 24 | **Module:** Deployment — Docker, Kubernetes, Observability  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Module 01 (scaffolding), Module 22 (cmd/main)

---

## 1. Mục tiêu Module

Cung cấp production-ready deployment artifacts: Kubernetes manifests, Helm charts, Prometheus metrics, và operational runbooks.

---

## 2. Kubernetes Deployment

### 2.1. Deployment Manifest (`deployments/k8s/deployment.yaml`)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cart-service
  labels:
    app: cart-service
    version: v1.0.0
spec:
  replicas: 3
  selector:
    matchLabels:
      app: cart-service
  template:
    metadata:
      labels:
        app: cart-service
        version: v1.0.0
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/metrics"
    spec:
      terminationGracePeriodSeconds: 60

      containers:
        - name: cart-service
          image: cart-service:v1.0.0
          imagePullPolicy: IfNotPresent

          ports:
            - name: http
              containerPort: 8080
              protocol: TCP

          env:
            - name: APP_ENV
              value: "production"
            - name: REDIS_ADDR
              valueFrom:
                configMapKeyRef:
                  name: cart-config
                  key: redis.addrs
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: cart-secrets
                  key: redis-password
            - name: DB_HOST
              valueFrom:
                configMapKeyRef:
                  name: cart-config
                  key: db.host
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: cart-secrets
                  key: db-password
            - name: KAFKA_BROKERS
              valueFrom:
                configMapKeyRef:
                  name: cart-config
                  key: kafka.brokers
            - name: PRODUCT_GRPC_ADDR
              valueFrom:
                configMapKeyRef:
                  name: cart-config
                  key: grpc.product_addr

          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi

          readinessProbe:
            httpGet:
              path: /readyz
              port: http
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3

          livenessProbe:
            httpGet:
              path: /healthz
              port: http
            initialDelaySeconds: 10
            periodSeconds: 15
            timeoutSeconds: 3
            failureThreshold: 3

          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 10"]

      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: cart-service
                topologyKey: kubernetes.io/hostname
```

### 2.2. Horizontal Pod Autoscaler (`deployments/k8s/hpa.yaml`)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: cart-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: cart-service
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: External
      external:
        metric:
          name: kafka_consumer_lag
          selector:
            matchLabels:
              consumer_group: cart-db-sync
        target:
          type: AverageValue
          averageValue: "1000"
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30
      policies:
        - type: Percent
          value: 100
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 2
          periodSeconds: 120
```

### 2.3. ConfigMap & Secrets (`deployments/k8s/config.yaml`)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: cart-config
data:
  redis.addrs: "redis-headless:6379"
  db.host: "postgres-svc:5432"
  db.name: "cart_db"
  kafka.brokers: "kafka-headless:29092"
  grpc.product_addr: "product-service:9090"
---
apiVersion: v1
kind: Secret
metadata:
  name: cart-secrets
type: Opaque
stringData:
  redis-password: "changeme"
  db-password: "changeme"
```

---

## 3. Prometheus Metrics

### 3.1. Metrics Endpoint (`internal/api/handler/health_handler.go` addition)

```go
// Metrics returns Prometheus metrics.
func (h *HealthHandler) Metrics(c *gin.Context) {
    // In production, use github.com/prometheus/client_golang
    // import (
    //   "github.com/prometheus/client_golang/prometheus/promhttp"
    // )
    // promhttp.Handler().ServeHTTP(c.Writer, c.Request)
    c.JSON(http.StatusOK, gin.H{"message": "metrics endpoint"})
}
```

### 3.2. Key Metrics to Expose

```go
// internal/common/metrics/metrics.go
package metrics

import (
    "github.com/prometheus/client_golang/prometheus"
    "github.com/prometheus/client_golang/prometheus/promauto"
)

var (
    // HTTP metrics
    HTTPRequestsTotal = promauto.NewCounterVec(
        prometheus.CounterOpts{
            Name: "cart_http_requests_total",
            Help: "Total HTTP requests",
        },
        []string{"method", "path", "status"},
    )

    HTTPRequestDuration = promauto.NewHistogramVec(
        prometheus.HistogramOpts{
            Name:    "cart_http_request_duration_seconds",
            Help:    "HTTP request duration",
            Buckets: []float64{.005, .01, .025, .05, .1, .25, .5, 1},
        },
        []string{"method", "path"},
    )

    // Redis metrics
    RedisLuaScriptDuration = promauto.NewHistogramVec(
        prometheus.HistogramOpts{
            Name:    "cart_redis_lua_duration_seconds",
            Help:    "Lua script execution duration",
            Buckets: []float64{.001, .002, .005, .01, .025, .05, .1},
        },
        []string{"script"},
    )

    // Worker metrics
    KafkaMessagesProcessed = promauto.NewCounterVec(
        prometheus.CounterOpts{
            Name: "cart_kafka_messages_processed_total",
            Help: "Total Kafka messages processed",
        },
        []string{"topic", "event_type"},
    )

    KafkaConsumerLag = promauto.NewGaugeVec(
        prometheus.GaugeOpts{
            Name: "cart_kafka_consumer_lag",
            Help: "Kafka consumer lag",
        },
        []string{"topic", "partition"},
    )

    OutboxRelayMessages = promauto.NewCounterVec(
        prometheus.CounterOpts{
            Name: "cart_outbox_relay_messages_total",
            Help: "Messages relayed from Redis Stream to Kafka",
        },
        []string{"shard", "status"},
    )

    StreamTrimmerMessages = promauto.NewCounterVec(
        prometheus.CounterOpts{
            Name: "cart_stream_trimmed_messages_total",
            Help: "Messages trimmed from Redis Stream",
        },
        []string{"shard"},
    )

    CircuitBreakerState = promauto.NewGaugeVec(
        prometheus.GaugeOpts{
            Name: "cart_circuit_breaker_state",
            Help: "Circuit breaker state (0=closed, 1=open, 2=half-open)",
        },
        []string{"service"},
    )

    // Cart metrics
    CartItemCount = promauto.NewHistogram(
        prometheus.HistogramOpts{
            Name:    "cart_item_count",
            Help:    "Number of items per cart",
            Buckets: []float64{1, 2, 5, 10, 20, 50},
        },
    )

    CartOperationDuration = promauto.NewHistogramVec(
        prometheus.HistogramOpts{
            Name:    "cart_operation_duration_seconds",
            Help:    "Cart operation duration",
            Buckets: []float64{.001, .002, .005, .01, .025, .05, .1},
        },
        []string{"operation"},
    )
)
```

---

## 4. Service Mesh & Network Policies

### 4.1. Network Policy (`deployments/k8s/network-policy.yaml`)

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cart-service-netpol
spec:
  podSelector:
    matchLabels:
      app: cart-service
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: api-gateway
      ports:
        - protocol: TCP
          port: 8080
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: redis
      ports:
        - protocol: TCP
          port: 6379
    - to:
        - podSelector:
            matchLabels:
              app: postgres
      ports:
        - protocol: TCP
          port: 5432
    - to:
        - podSelector:
            matchLabels:
              app: kafka
      ports:
        - protocol: TCP
          port: 9092
    - to:
        - podSelector:
            matchLabels:
              app: product-service
      ports:
        - protocol: TCP
          port: 9090
```

---

## 5. Runbooks

### 5.1. Redis Cluster Failover

```bash
# Check Redis cluster status
redis-cli -h $REDIS_ADDR cluster nodes

# If a node fails:
kubectl rollout restart statefulset redis

# Verify cluster health
redis-cli -h $REDIS_ADDR cluster info
```

### 5.2. Kafka Consumer Lag Alert Response

```bash
# Check consumer group lag
kafka-consumer-groups.sh --bootstrap-server $KAFKA --describe --group cart-db-sync

# If lag > 10000 for > 5 minutes:
# Scale up Postgres Sync workers
kubectl scale deployment cart-service --replicas=6

# Monitor lag reduction
watch -n 5 'kafka-consumer-groups.sh --bootstrap-server $KAFKA --describe --group cart-db-sync'
```

### 5.3. Circuit Breaker Open Response

```bash
# Check circuit breaker state via metrics
curl http://cart-service:8080/metrics | grep circuit_breaker_state

# If product-service CB is open:
# 1. Check product service health
kubectl exec -it product-service-pod -- curl localhost:8080/healthz

# 2. If product service is down, scale it up
kubectl scale deployment product-service --replicas=3

# 3. Wait for CB to transition to half-open (automatic)
# 4. Monitor CB state until closed
```

---

## 6. SRE Summary Dashboard

```
┌──────────────────────────────────────────────────────────────┐
│                   Cart Service SLO Dashboard                    │
│                                                               │
│  Availability:    99.9%  (target: 99.95%)                   │
│  Latency p95:    12ms   (target: < 50ms)                    │
│  Latency p99:     45ms   (target: < 100ms)                   │
│  Error rate:      0.1%   (target: < 0.1%)                   │
│                                                               │
│  ─────────────────────────────────────────────────────────   │
│  Key Metrics:                                                │
│  • Kafka consumer lag: < 1000 messages                       │
│  • Outbox relay throughput: > 10,000 msg/s                    │
│  • Redis stream trim rate: stable                           │
│  • Circuit breaker (product): CLOSED                          │
│                                                               │
│  Alert Conditions:                                           │
│  • HTTP p99 > 100ms for 5m → PagerDuty                     │
│  • Consumer lag > 10000 for 5m → PagerDuty                  │
│  • Redis error rate > 1% for 2m → PagerDuty                 │
│  • Circuit breaker OPEN > 30s → PagerDuty                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 7. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `deployment.yaml` | `deployments/k8s/deployment.yaml` | K8s Deployment |
| `hpa.yaml` | `deployments/k8s/hpa.yaml` | Horizontal Pod Autoscaler |
| `service.yaml` | `deployments/k8s/service.yaml` | K8s Service |
| `config.yaml` | `deployments/k8s/config.yaml` | ConfigMap + Secrets |
| `network-policy.yaml` | `deployments/k8s/network-policy.yaml` | Network policies |
| `metrics.go` | `internal/common/metrics/metrics.go` | Prometheus metrics |
| `README.md` | `deployments/k8s/README.md` | K8s deployment guide |

---

## 8. Checklist triển khai Module 24

```markdown
## 24 - Deployment Plan
- [ ] Tạo `deployments/k8s/deployment.yaml` với production settings
- [ ] Tạo `deployments/k8s/hpa.yaml` với HPA + Kafka lag metric
- [ ] Tạo `deployments/k8s/service.yaml` với ClusterIP/LoadBalancer
- [ ] Tạo `deployments/k8s/config.yaml` với ConfigMap + Secrets
- [ ] Tạo `deployments/k8s/network-policy.yaml` với egress rules
- [ ] Tạo `internal/common/metrics/metrics.go` với Prometheus metrics
- [ ] Thêm metrics endpoint vào health handler
- [ ] Tạo Prometheus alerting rules
- [ ] Tạo Grafana dashboard JSON
- [ ] Tạo `deployments/k8s/README.md` với deployment guide
- [ ] Verify: `kubectl apply --dry-run=client` passes
- [ ] Verify: Helm chart template renders correctly
```

---

## 9. Tổng kết toàn bộ Implementation Plan

```markdown
Tổng kết:
- 24 modules được chia thành 8 phases
- Tổng số file code ~ 150-200 files
- Thời gian ước tính: 5-6 tuần (1 developer)
- Thứ tự triển khai tuân thủ dependency graph:
  Tier 0-1: Foundation → Domain Core
  Tier 2:   Infrastructure Adapters  
  Tier 3:   Domain Services
  Tier 4:   API Layer
  Tier 5:   Workers
  Tier 6:   Bootstrap
  Tier 7:   QA & Deployment

Nguyên tắc tuân thủ:
- Dependency Inversion: domain không import infrastructure
- Bottom-up: tầng nền trước, tầng cao sau
- Mỗi module chỉ mô tả ở một file, không trùng lặp
- Checklist đầy đủ cho từng module
```

---

*Hoàn thành! Toàn bộ Implementation Plan gồm 24 modules đã được tạo trong thư mục `docs/cart/plan/`.*
