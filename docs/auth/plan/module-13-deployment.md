# Module 13: Deployment

> **Module:** Deployment  
> **File:** `module-13-deployment.md`  
> **Phase:** 4 - Workers & Operations  
> **Estimated Time:** 2-3 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Docker Configuration](#2-docker-configuration)
3. [Kubernetes Deployment](#3-kubernetes-deployment)
4. [Health Checks](#4-health-checks)
5. [Graceful Shutdown](#5-graceful-shutdown)
6. [Observability](#6-observability)
7. [Deployment Checklist](#7-deployment-checklist)
8. [Checklist](#8-checklist)

---

## 1. Overview

Module này thiết lập triển khai:
- Docker containerization
- Kubernetes deployment manifests
- Health checks và readiness probes
- Graceful shutdown
- Observability (metrics, logging, tracing)

### Deployment Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     DEPLOYMENT ARCHITECTURE                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐                                                │
│  │   GitHub    │                                                │
│  │   Actions   │                                                │
│  └──────┬──────┘                                                │
│         │                                                        │
│         ▼                                                        │
│  ┌─────────────────────────────────────────────┐                │
│  │           Container Registry                │                │
│  │              (Docker Hub/GCR)              │                │
│  └──────────────────┬────────────────────────┘                │
│                     │                                          │
│         ┌───────────┴───────────┐                              │
│         │                       │                              │
│         ▼                       ▼                              │
│  ┌─────────────┐       ┌─────────────┐                        │
│  │  Staging    │       │ Production │                        │
│  │  (K8s)     │       │  (K8s)    │                        │
│  └─────────────┘       └─────────────┘                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Docker Configuration

### 2.1 Dockerfile

```dockerfile
# Build stage
FROM golang:1.22-alpine AS builder

# Install build dependencies
RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

# Copy go mod files first for caching
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build \
    -ldflags="-w -s" \
    -o auth-service \
    ./cmd/server

# Final stage
FROM alpine:3.19

# Install runtime dependencies
RUN apk --no-cache add ca-certificates tzdata

WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Copy binary from builder
COPY --from=builder /app/auth-service .

# Copy configs
COPY --from=builder /app/configs ./configs

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Run the application
CMD ["./auth-service"]
```

### 2.2 .dockerignore

```dockerignore
# Git
.git
.gitignore

# IDE
.idea
.vscode
*.swp

# Documentation
docs
*.md

# Test files
**/*_test.go
test/
*_test.go

# Scripts
scripts/

# Local configs
.env
.env.local

# Build artifacts
bin/
*.exe
*.dll
*.so
*.dylib

# Docker files (not needed in image)
Dockerfile
docker-compose.yml
.dockerignore

# Misc
Makefile
```

### 2.3 docker-compose.yaml

```yaml
version: '3.8'

services:
  auth-service:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - DB_PASSWORD=postgres
      - REDIS_PASSWORD=redis
      - KAFKA_BROKERS=kafka:9092
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 10s
    restart: unless-stopped
    networks:
      - auth-network

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: auth_db
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - auth-network

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass redis --appendonly yes
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "redis", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - auth-network

  kafka:
    image: bitnami/kafka:3.6
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 0@kafka:9093
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      ALLOW_PLAINTEXT_LISTENER: "yes"
    ports:
      - "9092:9092"
    volumes:
      - kafka_data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 30s
      timeout: 10s
      retries: 5
    networks:
      - auth-network

  kafka-init:
    image: bitnami/kafka:3.6
    depends_on:
      kafka:
        condition: service_healthy
    entrypoint: [ '/bin/sh', '-c' ]
    command: |
      "
      # Create topics
      kafka-topics.sh --create --if-not-exists --bootstrap-server kafka:9092 --partitions 6 --replication-factor 1 --topic t.auth.events
      kafka-topics.sh --create --if-not-exists --bootstrap-server kafka:9092 --partitions 6 --replication-factor 1 --topic t.admin.events
      echo 'Topics created successfully'
      "
    networks:
      - auth-network

volumes:
  postgres_data:
  redis_data:
  kafka_data:

networks:
  auth-network:
    driver: bridge
```

---

## 3. Kubernetes Deployment

### 3.1 Deployment Manifest

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: auth-service
  namespace: auth
  labels:
    app: auth-service
    tier: backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: auth-service
  template:
    metadata:
      labels:
        app: auth-service
        tier: backend
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/metrics"
    spec:
      serviceAccountName: auth-service
      securityContext:
        runAsNonRoot: true
        runAsUser: 1001
        fsGroup: 1001
      containers:
        - name: auth-service
          image: gcr.io/project/auth-service:latest
          imagePullPolicy: Always
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          env:
            - name: APP_ENV
              value: "production"
            - name: DB_HOST
              valueFrom:
                secretKeyRef:
                  name: auth-secrets
                  key: db-host
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: auth-secrets
                  key: db-password
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: auth-secrets
                  key: redis-password
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /live
              port: http
            initialDelaySeconds: 10
            periodSeconds: 30
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /ready
              port: http
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
          startupProbe:
            httpGet:
              path: /health
              port: http
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 30
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 10"]
          volumeMounts:
            - name: config
              mountPath: /app/configs
              readOnly: true
      volumes:
        - name: config
          configMap:
            name: auth-config
```

### 3.2 Service Manifest

```yaml
# k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: auth-service
  namespace: auth
  labels:
    app: auth-service
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 80
      targetPort: http
      protocol: TCP
  selector:
    app: auth-service
```

### 3.3 Horizontal Pod Autoscaler

```yaml
# k8s/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: auth-service
  namespace: auth
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: auth-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
        - type: Percent
          value: 100
          periodSeconds: 15
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 10
          periodSeconds: 60
```

### 3.4 ConfigMap and Secrets

```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: auth-config
  namespace: auth
data:
  application.yaml: |
    app:
      name: "auth-service"
      host: "0.0.0.0"
      port: 8080
      env: "production"
    
    server:
      read_timeout: "30s"
      write_timeout: "30s"
      idle_timeout: "120s"
      shutdown_timeout: "30s"
    
    database:
      host: "postgres.auth.svc.cluster.local"
      port: 5432
      name: "auth_db"
      username: "postgres"
      sslmode: "require"
    
    redis:
      host: "redis.auth.svc.cluster.local"
      port: 6379
      db: 0
    
    kafka:
      brokers:
        - "kafka.auth.svc.cluster.local:9092"
      topic_prefix: "t.auth"
      consumer_group: "auth-service"
      admin_topic: "t.admin"
    
    jwt:
      access_token_ttl: "15m"
      refresh_token_ttl: "720h"
      grace_period: "10s"
      issuer: "auth-service"
      algorithm: "RS256"
    
    security:
      argon2:
        memory: 65536
        iterations: 3
        parallelism: 4
        salt_length: 16
        key_length: 32
      rate_limit:
        login_max_attempts: 5
        login_lockout_duration: "30m"
        login_window: "60m"
    
    logging:
      level: "info"
      format: "json"
---
apiVersion: v1
kind: Secret
metadata:
  name: auth-secrets
  namespace: auth
type: Opaque
stringData:
  db-host: "postgres.auth.svc.cluster.local"
  db-password: "your-db-password"
  redis-password: "your-redis-password"
```

### 3.5 PodDisruptionBudget

```yaml
# k8s/pdb.yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: auth-service
  namespace: auth
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: auth-service
```

---

## 4. Health Checks

### 4.1 Health Handler

```go
// internal/api/handler/health_handler.go
package handler

import (
    "context"
    "net/http"
    "time"

    "github.com/gin-gonic/gin"
)

type HealthHandler struct {
    db    DatabaseChecker
    redis RedisChecker
}

type DatabaseChecker interface {
    Ping(ctx context.Context) error
}

type RedisChecker interface {
    Ping(ctx context.Context) error
}

// HealthResponse represents the health check response
type HealthResponse struct {
    Status    string            `json:"status"`
    Timestamp time.Time         `json:"timestamp"`
    Checks    map[string]string `json:"checks,omitempty"`
}

// Health handles GET /health
func (h *HealthHandler) Health(c *gin.Context) {
    c.JSON(http.StatusOK, HealthResponse{
        Status:    "ok",
        Timestamp: time.Now().UTC(),
    })
}

// Ready handles GET /ready
func (h *HealthHandler) Ready(c *gin.Context) {
    checks := make(map[string]string)
    allHealthy := true

    // Create timeout context
    ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
    defer cancel()

    // Check database
    if h.db != nil {
        if err := h.db.Ping(ctx); err != nil {
            checks["database"] = "unhealthy: " + err.Error()
            allHealthy = false
        } else {
            checks["database"] = "healthy"
        }
    }

    // Check Redis
    if h.redis != nil {
        if err := h.redis.Ping(ctx); err != nil {
            checks["redis"] = "unhealthy: " + err.Error()
            allHealthy = false
        } else {
            checks["redis"] = "healthy"
        }
    }

    status := "ok"
    statusCode := http.StatusOK
    if !allHealthy {
        status = "degraded"
        statusCode = http.StatusServiceUnavailable
    }

    c.JSON(statusCode, HealthResponse{
        Status:    status,
        Timestamp: time.Now().UTC(),
        Checks:    checks,
    })
}

// Live handles GET /live
func (h *HealthHandler) Live(c *gin.Context) {
    c.JSON(http.StatusOK, HealthResponse{
        Status:    "ok",
        Timestamp: time.Now().UTC(),
    })
}
```

---

## 5. Graceful Shutdown

### 5.1 Main Entry Point with Graceful Shutdown

```go
// cmd/server/main.go
package main

import (
    "context"
    "flag"
    "fmt"
    "log"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/tylerb/graceful"
    "go.uber.org/zap"
)

func main() {
    // Parse flags
    configPath := flag.String("config", "", "Path to config file")
    flag.Parse()

    // Initialize logger
    logger, err := initLogger()
    if err != nil {
        log.Fatalf("Failed to initialize logger: %v", err)
    }
    defer logger.Sync()

    // Load configuration
    cfg, err := config.Load(*configPath)
    if err != nil {
        logger.Fatal("Failed to load configuration", zap.Error(err))
    }

    // Initialize dependencies
    db, err := postgres.NewConnection(cfg.Database)
    if err != nil {
        logger.Fatal("Failed to connect to database", zap.Error(err))
    }
    defer db.Close()

    redisClient, err := redis.NewClient(cfg.Redis)
    if err != nil {
        logger.Fatal("Failed to connect to Redis", zap.Error(err))
    }
    defer redisClient.Close()

    // Initialize services and handlers
    // ... (see other modules)

    // Setup router
    r := router.NewRouter(/* ... */)
    r.SetupRoutes()

    // Create graceful HTTP server
    server := &graceful.Server{
        Server: &http.Server{
            Addr:         fmt.Sprintf("%s:%d", cfg.App.Host, cfg.App.Port),
            Handler:      r.Engine(),
            ReadTimeout:  cfg.Server.ReadTimeout,
            WriteTimeout: cfg.Server.WriteTimeout,
            IdleTimeout:  cfg.Server.IdleTimeout,
        },
        Timeout: cfg.Server.ShutdownTimeout,
        PreShutdownHook: func() error {
            logger.Info("Pre-shutdown: stopping accepting new connections")
            
            // Stop workers
            workerRunner.Stop()
            
            return nil
        },
        ShutdownInitiatedHook: func(internalCtx context.Context) {
            logger.Info("Shutdown initiated: waiting for in-flight requests")
            
            // Wait for workers to finish
            select {
            case <-internalCtx.Done():
                logger.Warn("Shutdown timeout reached")
            default:
                // Continue with shutdown
            }
        },
    }

    // Start server in goroutine
    go func() {
        logger.Info("Starting HTTP server",
            zap.String("addr", server.Addr),
        )
        if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            logger.Fatal("Failed to start server", zap.Error(err))
        }
    }()

    // Wait for interrupt signal
    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    sig := <-quit

    logger.Info("Received shutdown signal",
        zap.String("signal", sig.String()),
    )

    // Create shutdown context with timeout
    shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
    defer cancel()

    // Shutdown server
    logger.Info("Shutting down server...")

    if err := server.Shutdown(shutdownCtx); err != nil {
        logger.Error("Server forced to shutdown", zap.Error(err))
    }

    logger.Info("Server exited properly")
}
```

---

## 6. Observability

### 6.1 Metrics Endpoint

```go
// internal/api/handler/metrics_handler.go
package handler

import (
    "net/http"

    "github.com/gin-gonic/gin"
    "github.com/prometheus/client_golang/prometheus"
    "github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
    // HTTP metrics
    httpRequestsTotal = prometheus.NewCounterVec(
        prometheus.CounterOpts{
            Name: "http_requests_total",
            Help: "Total number of HTTP requests",
        },
        []string{"method", "path", "status"},
    )

    httpRequestDuration = prometheus.NewHistogramVec(
        prometheus.HistogramOpts{
            Name:    "http_request_duration_seconds",
            Help:    "HTTP request duration in seconds",
            Buckets: prometheus.DefBuckets,
        },
        []string{"method", "path"},
    )

    // Auth metrics
    loginTotal = prometheus.NewCounterVec(
        prometheus.CounterOpts{
            Name: "auth_login_total",
            Help: "Total number of login attempts",
        },
        []string{"status"},
    )

    tokenRefreshTotal = prometheus.NewCounterVec(
        prometheus.CounterOpts{
            Name: "auth_token_refresh_total",
            Help: "Total number of token refresh attempts",
        },
        []string{"status"},
    )

    activeSessions = prometheus.NewGauge(
        prometheus.GaugeOpts{
            Name: "auth_active_sessions_total",
            Help: "Total number of active sessions",
        },
    )
)

func init() {
    prometheus.MustRegister(httpRequestsTotal)
    prometheus.MustRegister(httpRequestDuration)
    prometheus.MustRegister(loginTotal)
    prometheus.MustRegister(tokenRefreshTotal)
    prometheus.MustRegister(activeSessions)
}

type MetricsHandler struct{}

func NewMetricsHandler() *MetricsHandler {
    return &MetricsHandler{}
}

func (h *MetricsHandler) Metrics(c *gin.Context) {
    promhttp.Handler().ServeHTTP(c.Writer, c.Request)
}
```

### 6.2 Structured Logging with Zap

```go
// internal/common/logging/logger.go
package logging

import (
    "go.uber.org/zap"
    "go.uber.org/zap/zapcore"
)

func NewLogger(env string) (*zap.Logger, error) {
    var config zap.Config

    if env == "production" {
        config = zap.NewProductionConfig()
    } else {
        config = zap.NewDevelopmentConfig()
    }

    config.EncoderConfig.TimeKey = "timestamp"
    config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

    return config.Build()
}

// RequestLogger middleware
func RequestLogger(logger *zap.Logger) gin.HandlerFunc {
    return func(c *gin.Context) {
        start := time.Now()
        path := c.Request.URL.Path
        query := c.Request.URL.RawQuery

        c.Next()

        end := time.Now()
        latency := end.Sub(start)

        if query != "" {
            path = path + "?" + query
        }

        logger.Info("HTTP request",
            zap.Int("status", c.Writer.Status()),
            zap.String("method", c.Request.Method),
            zap.String("path", path),
            zap.String("ip", c.ClientIP()),
            zap.Duration("latency", latency),
            zap.Int("size", c.Writer.Size()),
            zap.String("user_agent", c.Request.UserAgent()),
        )
    }
}
```

### 6.3 Distributed Tracing

```go
// internal/common/tracing/tracing.go
package tracing

import (
    "github.com/gin-gonic/gin"
    "go.opentelemetry.io/otel"
    "go.opentelemetry.io/otel/exporters/jaeger"
    "go.opentelemetry.io/otel/propagation"
    "go.opentelemetry.io/otel/sdk/resource"
    "go.opentelemetry.io/otel/sdk/trace"
    semconv "go.opentelemetry.io/otel/semconv/v1.21.0"
)

func InitTracer(serviceName, jaegerEndpoint string) (func(), error) {
    exporter, err := jaeger.New(
        jaeger.WithCollectorEndpoint(jaegerEndpoint),
    )
    if err != nil {
        return nil, err
    }

    tp := trace.NewTracerProvider(
        trace.WithBatcher(exporter),
        trace.WithResource(resource.NewWithAttributes(
            semconv.SchemaURL,
            semconv.ServiceName(serviceName),
        )),
    )

    otel.SetTracerProvider(tp)
    otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
        propagation.TraceContext{},
        propagation.Baggage{},
    ))

    return func() {
        tp.Shutdown()
    }, nil
}

func TracingMiddleware(serviceName string) gin.HandlerFunc {
    return func(c *gin.Context) {
        tracer := otel.Tracer(serviceName)

        ctx, span := tracer.Start(c.Request.Context(), c.Request.Method+" "+c.FullPath())
        defer span.End()

        // Add span attributes
        span.SetAttributes(
            semconv.HTTPMethod(c.Request.Method),
            semconv.HTTPURL(c.Request.URL.String()),
            semconv.HTTPRoute(c.FullPath()),
        )

        c.Request = c.Request.WithContext(ctx)
        c.Next()

        span.SetAttributes(semconv.HTTPStatusCode(c.Writer.Status()))
    }
}
```

---

## 7. Deployment Checklist

### Pre-Deployment

- [ ] All unit tests passing (>= 80% coverage)
- [ ] All integration tests passing
- [ ] Load tests completed and within thresholds
- [ ] Security scan completed (no critical vulnerabilities)
- [ ] Code review approved
- [ ] Migration scripts tested

### Docker Build

- [ ] Dockerfile builds successfully
- [ ] Image size optimized (< 100MB)
- [ ] Health check configured
- [ ] Non-root user configured
- [ ] All secrets externalized

### Kubernetes Deployment

- [ ] Namespace created
- [ ] ConfigMaps created
- [ ] Secrets created (externally managed)
- [ ] Deployment created
- [ ] Service created
- [ ] HPA configured
- [ ] PDB configured
- [ ] Network policies configured

### Post-Deployment

- [ ] Pods running successfully
- [ ] Health checks passing
- [ ] Logs accessible
- [ ] Metrics available in Prometheus
- [ ] Traces available in Jaeger
- [ ] Smoke tests passed
- [ ] Rollback plan documented

---

## 8. Checklist

### Docker Configuration

- [ ] Create optimized Dockerfile
- [ ] Create .dockerignore
- [ ] Create docker-compose.yaml for local development
- [ ] Test Docker build
- [ ] Verify health check works

### Kubernetes Deployment

- [ ] Create deployment manifest
- [ ] Create service manifest
- [ ] Create HPA manifest
- [ ] Create ConfigMap
- [ ] Create Secrets
- [ ] Create PDB
- [ ] Create NetworkPolicy (optional)
- [ ] Test deployment
- [ ] Verify autoscaling

### Health Checks

- [ ] Implement /health endpoint
- [ ] Implement /ready endpoint
- [ ] Implement /live endpoint
- [ ] Add database connectivity check
- [ ] Add Redis connectivity check
- [ ] Configure Kubernetes probes

### Graceful Shutdown

- [ ] Implement signal handling
- [ ] Stop workers before shutdown
- [ ] Wait for in-flight requests
- [ ] Close database connections
- [ ] Close Redis connections
- [ ] Close Kafka producer
- [ ] Test shutdown timeout

### Observability

- [ ] Add Prometheus metrics
- [ ] Add structured logging with Zap
- [ ] Add distributed tracing with OpenTelemetry
- [ ] Configure log levels
- [ ] Create Grafana dashboards
- [ ] Create alerts

### CI/CD

- [ ] Create GitHub Actions workflow
- [ ] Add build step
- [ ] Add test step
- [ ] Add Docker build and push
- [ ] Add Kubernetes deployment step
- [ ] Configure environment variables
- [ ] Add rollback capability

---

**Previous:** [module-12-testing.md](module-12-testing.md)  
**End of Implementation Plan**
