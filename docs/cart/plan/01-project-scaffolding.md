# Cart Service — Module 01: Project Scaffolding

**Document:** 01 | **Module:** Project Scaffolding & Build System
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành
**Phụ thuộc:** Không có
**Ngày hoàn thành:** 2026-05-24

---

## 1. Mục tiêu Module

Tạo bộ khung project Go hoàn chỉnh theo chuẩn [golang-common-layout](../../common/golang/golang-common-layout.md). Module này chỉ tạo các file rỗng và cấu hình build — không viết logic nghiệp vụ.

---

## 2. Go Module (`go.mod`)

### 2.1. Nội dung `go.mod`

```go
module cart-service

go 1.22

require (
    // HTTP Framework
    github.com/gin-gonic/gin v1.9.1

    // Redis Client
    github.com/redis/go-redis/v9 v9.5.1

    // PostgreSQL
    github.com/jackc/pgx/v5 v5.5.5
    gorm.io/gorm v1.25.7
    gorm.io/driver/postgres v1.5.7

    // Kafka
    github.com/IBM/sarama v1.43.1

    // gRPC
    google.golang.org/grpc v1.63.2
    google.golang.org/protobuf v1.33.0
    github.com/fullstorydev/grpcurl v1.9.1
    google.golang.org/grpc/cmd/protoc-gen-go-grpc v1.4.0

    // Circuit Breaker
    github.com/sony/gobreaker/v2 v2.5.4

    // Config
    github.com/spf13/viper v1.18.2

    // Logging
    go.uber.org/zap v1.27.0

    // UUID
    github.com/google/uuid v1.6.0

    // Cron Scheduler
    github.com/robfig/cron/v3 v3.0.1

    // OpenTelemetry (tracing)
    go.opentelemetry.io/otel v1.24.0
    go.opentelemetry.io/otel/trace v1.24.0
    go.opentelemetry.io/otel/sdk v1.24.0

    // Validation
    github.com/go-playground/validator/v10 v10.19.0

    // Test containers
    github.com/testcontainers/testcontainers-go v0.31.0

    // Linting
    github.com/golangci/golangci-lint v1.58.2
)

require (
    // Transitive dependencies
    github.com/bytedance/sonic v1.11.2
    github.com/cespare/xxhash/v2 v2.2.0
    github.com/containerd/platforms v0.2.1
    github.com/davecgh/go-spew v1.1.2-0.20180830191138-d8f796af33cc
    github.com/dgryski/go-rendezvous v0.0.0-20200823014737-9f7001d12a5f
    github.com/docker/docker v26.0.0+incompatible
    github.com/eapache/go-resiliency v1.6.0
    github.com/eapache/go-xerial-snappy v0.0.0-20230731223053-c322873962e3
    github.com/eapache/queue v1.1.0
    github.com/gabriel-vasile/mimetype v1.4.3
    github.com/go-logr/logr v1.4.1
    github.com/go-logr/stdr v1.2.2
    github.com/go-playground/locales v0.14.1
    github.com/go-playground/universal-translator v0.18.1
    github.com/goccy/go-json v0.10.2
    github.com/golang/snappy v0.0.4
    github.com/hashicorp/errwrap v1.1.0
    github.com/hashicorp/go-multierror v1.1.1
    github.com/hashicorp/go-uuid v1.0.3
    github.com/hashicorp/yamux v0.1.1
    github.com/jackc/pgpassfile v1.0.0
    github.com/jackc/pgservicefile v0.0.0-20231201235250-de7065d80cb9
    github.com/jackc/puddle/v2 v2.2.1
    github.com/jcmturner/aescts/v2 v2.0.0
    github.com/jcmturner/dnsutils/v2 v2.0.0
    github.com/jcmturner/gofork v1.7.6
    github.com/jcmturner/gokrb5/v8 v8.4.4
    github.com/jcmturner/rpc/v2 v2.0.3
    github.com/json-iterator/go v1.1.12
    github.com/klauspost/compress v1.17.7
    github.com/klauspost/cpuid/v2 v2.2.7
    github.com/leodido/go-urn v1.4.0
    github.com/magiconair/properties v1.8.7
    github.com/mattn/go-isatty v0.0.20
    github.com/mitchellh/mapstructure v1.5.0
    github.com/modern-go/concurrent v0.0.0-20180306012644-bacd9c7ef1dd
    github.com/modern-go/reflect2 v1.0.2
    github.com/pelletier/go-toml/v2 v2.1.1
    github.com/pierrec/lz4/v4 v4.1.21
    github.com/rcrowley/go-metrics v0.0.0-20201227073835-cf1acfcdf475
    github.com/sagikazarmark/locafero v0.4.0
    github.com/sagikazarmark/slog-shim v0.1.0
    github.com/sourcegraph/conc v0.3.0
    github.com/spf13/afero v1.11.0
    github.com/spf13/cast v1.6.0
    github.com/spf13/pflag v1.0.5
    github.com/subosito/gotenv v1.6.0
    github.com/twitchyliquid64/golang-asm v0.15.1
    github.com/ugorji/go/codec v1.2.12
    go.opentelemetry.io/otel/metric v1.24.0
    go.uber.org/multierr v1.11.0
    golang.org/x/arch v0.7.0
    golang.org/x/crypto v0.21.0
    golang.org/x/exp v0.0.0-20240318143956-a85f2c67cd81
    golang.org/x/net v0.22.0
    golang.org/x/sync v0.6.0
    golang.org/x/sys v0.18.0
    golang.org/x/text v0.14.0
    golang.org/x/time v0.5.0
    google.golang.org/genproto/googleapis/rpc v0.0.0-20240311173647-c811ad7063a7
    gopkg.in/ini.v1 v1.67.0
    gopkg.in/yaml.v3 v3.0.1
)
```

---

## 3. Cấu trúc Thư mục (Tạo thư mục rỗng)

```bash
mkdir -p cmd/server
mkdir -p internal/api/handler
mkdir -p internal/api/middleware
mkdir -p internal/api/router
mkdir -p internal/api/dto/request
mkdir -p internal/api/dto/response
mkdir -p internal/domain/entity
mkdir -p internal/domain/service
mkdir -p internal/domain/port
mkdir -p internal/domain/event
mkdir -p internal/infrastructure/redis/scripts
mkdir -p internal/infrastructure/postgres/migrations
mkdir -p internal/infrastructure/kafka
mkdir -p internal/infrastructure/grpc
mkdir -p internal/infrastructure/config
mkdir -p internal/workers/outbox_relay
mkdir -p internal/workers/postgres_sync
mkdir -p internal/workers/abandoned_marker
mkdir -p internal/workers/archival_cleaner
mkdir -p internal/workers/stream_trimmer
mkdir -p internal/workers/order_event_consumer
mkdir -p internal/common/constants
mkdir -p internal/common/errors
mkdir -p internal/common/keybuilder
mkdir -p internal/common/circuitbreaker
mkdir -p internal/common/logging
mkdir -p configs
mkdir -p scripts
mkdir -p test/mocks
mkdir -p test/fixtures
mkdir -p test/integration
```

---

## 4. File cấu hình (`configs/application.yaml`)

```yaml
app:
  name: "cart-service"
  env: "${APP_ENV:development}"
  host: "0.0.0.0"
  port: 8080
  shutdown_timeout: 30s

redis:
  addrs:
    - "${REDIS_ADDR:localhost:6379}"
  password: "${REDIS_PASSWORD:}"
  db: 0
  pool_size: 100
  min_idle_conns: 20
  read_timeout: 3s
  write_timeout: 3s
  num_shards: 4

database:
  host: "${DB_HOST:localhost}"
  port: ${DB_PORT:5432}
  user: "${DB_USER:postgres}"
  password: "${DB_PASSWORD:postgres}"
  name: "${DB_NAME:cart_db}"
  max_open_conns: 50
  max_idle_conns: 20
  conn_max_lifetime: 30m

kafka:
  brokers:
    - "${KAFKA_BROKERS:localhost:9092}"
  topic_cart_events: "t.cart.events"
  topic_order_events: "t.order.events"
  consumer_group_cart_sync: "cart-db-sync"
  consumer_group_order: "cart-order-consumer"
  min_insync_replicas: 1
  required_acks: "all"
  max_poll_records: 50

grpc:
  product_service_addr: "${PRODUCT_GRPC_ADDR:localhost:9090}"
  dial_timeout: 5s
  max_call_timeout: 200ms
  circuit_breaker:
    name: "product-service"
    max_requests: 3
    interval: 10s
    timeout: 30s

cart:
  ttl: 604800          # 7 days in seconds
  max_skus_per_cart: 50
  max_qty_per_item: 100
  negative_cache_ttl: 60  # seconds
  rehydrate_ttl: 604800

worker:
  outbox_relay:
    count: 4              # one per shard
    block_timeout: 5s
    batch_size: 100
  postgres_sync:
    enabled: true
    batch_size: 50
    max_retries: 3
  abandoned_marker:
    enabled: true
    cron: "0 2 * * *"   # 2:00 AM daily
    batch_size: 1000
    inactive_threshold_days: 30
  archival_cleaner:
    enabled: true
    cron: "0 3 * * 0"   # 3:00 AM every Sunday
    deleted_ttl_days: 90
    checked_out_ttl_days: 90
    abandoned_ttl_days: 180
  stream_trimmer:
    enabled: true
    interval: 5m
    pending_threshold: 30m

logging:
  level: "${LOG_LEVEL:info}"
  format: "json"
  output: "stdout"

tracing:
  enabled: ${TRACING_ENABLED:false}
  endpoint: "${OTEL_ENDPOINT:localhost:4317}"
  service_name: "cart-service"
```

---

## 5. File `.env.example`

```bash
# Application
APP_ENV=development
LOG_LEVEL=debug

# Server
SERVER_PORT=8080

# Redis
REDIS_ADDR=localhost:6379
REDIS_PASSWORD=

# PostgreSQL
DB_HOST=localhost
DB_PORT=5432
DB_USER=postgres
DB_PASSWORD=postgres
DB_NAME=cart_db

# Kafka
KAFKA_BROKERS=localhost:9092

# gRPC (Product Service)
PRODUCT_GRPC_ADDR=localhost:9090

# Tracing
TRACING_ENABLED=false
OTEL_ENDPOINT=localhost:4317
```

---

## 6. Makefile

```make
.PHONY: all build test lint clean run docker-build docker-up docker-down

BINARY_NAME=cart-service
DOCKER_IMAGE=cart-service:latest

all: build

# Build
build:
	go build -o bin/$(BINARY_NAME) ./cmd/server

# Test
test:
	go test -v -race -coverprofile=coverage.out ./...

test-unit:
	go test -v -race -short ./...

test-integration:
	go test -v -race -tags=integration ./test/integration/...

test-coverage: test
	go tool cover -html=coverage.out -o coverage.html

# Lint
lint:
	golangci-lint run ./...

lint-fix:
	golangci-lint run --fix ./...

# Generate (protobuf, mocks)
generate:
	cd proto && protoc --go_out=../internal/infrastructure/grpc --go-grpc_out=../internal/infrastructure/grpc *.proto
	cd proto && protoc --grpc-web_out=../internal/infrastructure/grpc --js_out=import_style=commonjs:../internal/infrastructure/grpc *.proto

# Database migrations
migrate-create:
	@read -p "Migration name: " name && \
	migrate create -ext .up.sql -dir internal/infrastructure/postgres/migrations -seq $$name

migrate-up:
	migrate -path internal/infrastructure/postgres/migrations -database "postgres://$(DB_USER):$(DB_PASSWORD)@$(DB_HOST):$(DB_PORT)/$(DB_NAME)?sslmode=disable" up

migrate-down:
	migrate -path internal/infrastructure/postgres/migrations -database "postgres://$(DB_USER):$(DB_PASSWORD)@$(DB_HOST):$(DB_PORT)/$(DB_NAME)?sslmode=disable" down

# Docker
docker-build:
	docker build -t $(DOCKER_IMAGE) .

docker-up:
	docker-compose up -d

docker-down:
	docker-compose down -v

docker-logs:
	docker-compose logs -f

# Development
run:
	go run ./cmd/server/main.go

dev:
	air -c .air.toml

# Dependencies
deps:
	go mod download
	go mod tidy

# Security
security:
	govulncheck ./...

# Health
health:
	curl -s http://localhost:8080/healthz | jq .

# Proto (if using gRPC)
proto-install:
	@which protoc || (echo "Install protoc: https://grpc.io/docs/protoc-installation/" && exit 1)
	@which protoc-gen-go || go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
	@which protoc-gen-go && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Cleanup
clean:
	rm -rf bin/
	rm -f coverage.out coverage.html
	docker system prune -f
```

---

## 7. Dockerfile (Distroless)

```dockerfile
# ---- Build Stage ----
FROM golang:1.22-alpine AS builder

# Install protoc + grpc tools for proto generation
RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

# Download dependencies first (layer caching)
COPY go.mod go.sum ./
RUN go mod download

# Copy source
COPY . .

# Build with flags for minimal image
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags="-s -w -extldflags '-static'" \
    -o /app/cart-service \
    ./cmd/server

# ---- Runtime Stage (Distroless) ----
FROM gcr.io/distroless/static-debian12:nonroot

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/cart-service /app/cart-service

# Copy configs (can be overridden via env or volume mount)
COPY configs/ /app/configs/

# Non-root user
USER nonroot:nonroot

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["wget", "-q", "--spider", "http://localhost:8080/healthz"]

ENTRYPOINT ["/app/cart-service"]
```

---

## 8. docker-compose.yaml (Development)

```yaml
version: '3.9'

services:
  cart-service:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: cart-service
    ports:
      - "8080:8080"
    environment:
      - APP_ENV=development
      - LOG_LEVEL=debug
      - REDIS_ADDR=redis:6379
      - DB_HOST=postgres
      - DB_PORT=5432
      - DB_USER=postgres
      - DB_PASSWORD=postgres
      - DB_NAME=cart_db
      - KAFKA_BROKERS=kafka:9092
      - PRODUCT_GRPC_ADDR=host.docker.internal:9090
    depends_on:
      redis:
        condition: service_healthy
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy
    volumes:
      - ./configs:/app/configs:ro
    networks:
      - cart-network
    restart: unless-stopped

  redis:
    image: redis:7.2-alpine
    container_name: cart-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy allkeys-lru
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 3
    networks:
      - cart-network

  postgres:
    image: postgres:16-alpine
    container_name: cart-postgres
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: cart_db
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./internal/infrastructure/postgres/migrations:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 3s
      retries: 3
    networks:
      - cart-network

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: cart-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,HOST://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_LOG_RETENTION_HOURS: 168
    volumes:
      - kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - cart-network

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    container_name: cart-kafka-ui
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
    depends_on:
      - kafka
    networks:
      - cart-network

volumes:
  redis-data:
  postgres-data:
  kafka-data:

networks:
  cart-network:
    driver: bridge
```

---

## 9. Checklist triển khai Module 01

```markdown
## 01 - Project Scaffolding
- [x] Tạo `go.mod` với tất cả dependencies
- [x] Tạo cấu trúc thư mục rỗng
- [x] Tạo `configs/application.yaml` với đầy đủ config sections
- [x] Tạo `.env.example`
- [x] Tạo `Makefile` với all targets
- [x] Tạo `Dockerfile` (distroless)
- [x] Tạo `docker-compose.yaml` (Redis + PG + Kafka + Cart Service)
- [x] Chạy `go mod download` xác nhận dependencies resolve
- [ ] Verify `go build ./cmd/server` compile thành công (sẽ fail vì code chưa có nhưng xác nhận module setup đúng) — SKIP theo yêu cầu
- [x] Tạo placeholder file ở mỗi package để pass `go build`
- [ ] Tạo `.air.toml` cho live reload development (optional)
```

---

## 10. Placeholder Files (để `go build` thành công)

Tạo một file placeholder ở mỗi package để project compile được ngay:

```go
// internal/domain/entity/cart.go
package entity

// Placeholder - implement in Module 02
```

```go
// internal/domain/port/cart_repository.go
package port

// Placeholder - implement in Module 03
```

```go
// internal/infrastructure/config/config.go
package config

// Placeholder - implement in Module 10
```

> **Quy tắc:** Mỗi package phải có ít nhất 1 file `.go` hợp lệ. Sau khi hoàn thành module tương ứng, placeholder sẽ được thay thế bằng code thực.

---

*Tiếp theo: [02-domain-models.md](02-domain-models.md) — Domain entities và errors.*
