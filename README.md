# E-Commerce Microservices Platform

Hệ thống E-Commerce được xây dựng theo kiến trúc **Microservices**, sử dụng hai
hệ sinh thái chính là **Java** và **Go**. Event-driven, CQRS, Saga, Outbox hoặc
Event Sourcing được chọn theo nhu cầu của từng service, không phải yêu cầu bắt
buộc cho toàn hệ thống.

## Kiến trúc tổng quan

```
                          ┌──────────────────┐
                          │   API Gateway    │
                          │  (Traefik v3.x)  │
                          └────────┬─────────┘
                                   │
                 ┌─────────────────┼─────────────────┐
                 │                 │                  │
        ┌────────▼───────┐ ┌──────▼───────┐ ┌───────▼────────┐
        │  Java Services │ │  Go Services │ │  Infrastructure│
        │  (Spring Boot) │ │   (Gin/gRPC) │ │  (Kafka, Redis)│
        └────────────────┘ └──────────────┘ └────────────────┘
```

### Nguyên tắc thiết kế

- **Contract-first**: HTTP, gRPC và event dùng nguồn contract độc lập Go/Java.
- **Bounded context**: Mỗi service sở hữu dữ liệu và capability của mình.
- **Pattern theo nhu cầu**: Chỉ dùng CQRS/Event Sourcing/Saga khi độ phức tạp được chứng minh.
- **Reliable messaging**: Với dual-write quan trọng, cân nhắc Outbox và idempotent consumer.
- **Observable by default**: Propagate trace context và dùng structured log/metrics.

Machine-readable contract và hướng dẫn migration nằm tại [`contracts/`](contracts/README.md).

## Cấu trúc thư mục

```
e-commerce-microservices/
├── contracts/                   # Contract Go/Java dùng chung
│   ├── catalog/                 # Service và interaction catalog
│   ├── http/                    # OpenAPI
│   ├── grpc/                    # Protobuf
│   └── events/                  # AsyncAPI + JSON Schema
│
├── go/                          # Golang microservices
│   ├── services/
│   │   ├── api-gateway/         # API Gateway (Traefik v3.x)
│   │   ├── cart-service/        # Giỏ hàng (WebSocket real-time)
│   │   └── user-service/        # Quản lý người dùng
│   ├── Makefile                 # Build automation
│   └── README.md
│
├── java/                        # Java microservices
│   ├── common/                  # Shared library (Event Sourcing base)
│   │   ├── pom.xml
│   │   └── src/
│   └── services/
│       ├── promotion-service/   # Khuyến mãi & voucher
│       └── (shipping-service removed in Phase 14)
│
└── docs/                        # Tài liệu thiết kế chi tiết
    ├── golang-common-layout.md  # Go project layout reference
    ├── auth/                    # Authentication & Authorization
    ├── user/                    # User management
    ├── product/                 # Product catalog
    ├── cart/                    # Shopping cart
    ├── order/                   # Order management
    ├── checkout/                # Checkout flow
    ├── payment/                 # Payment processing
    ├── inventory/               # Inventory management
    ├── promotion/               # Promotions & coupons
    ├── search/                  # Full-text search
    ├── review/                  # Product reviews
    ├── notification/            # Notifications
    ├── chat/                    # Real-time chat
    ├── analytics/               # Analytics & reporting
    ├── gateway/                 # API Gateway design (Traefik, north-south)
    └── mesh/                    # Service mesh design (Istio ambient, east-west; target)
```

## Microservices

### Go Services

| Service | Mô tả | Port |
|---------|--------|------|
| **API Gateway** | Traefik v3.x - routing, rate limiting, JWT auth, tracing | 80, 8090 |
| **Cart Service** | Giỏ hàng real-time với WebSocket, idempotency, outbox pattern | 8080 |
| **User Service** | Quản lý người dùng, GDPR compliance, Debezium CDC | 8080 |

### Java Services (Spring Boot 4 / Java 21)

| Service | Mô tả |
|---------|--------|
| **Common Module** | Shared library - BaseEvent, EventPublisher, EventStore, Kafka config |
| **Promotion Service** | Rule engine, quản lý coupon/voucher, chiến dịch khuyến mãi |

### Services được thiết kế (Documentation)

Ngoài các service đã implement, hệ thống có tài liệu thiết kế chi tiết cho:

| Service | Tài liệu |
|---------|-----------|
| **Auth Service** | JWT + Redis Denylist, JWKS, token rotation |
| **Product Service** | Catalog management, state machine, caching |
| **Order Service** | Temporal checkout/refund Saga, PostgreSQL read/search, durable inbox + multi-destination outbox; implemented in `java/services/order-service` ([docs/order](docs/order/README.md)) |
| **Checkout Service** | Idempotency, security analysis, caching |
| **Payment Service** | Stripe direct, payment/refund state machine, Outbox CDC và financial facts AsyncAPI v2 |
| **Inventory Service** | PostgreSQL authoritative stock, exact-plan whole-order reservation, returns/quarantine, transactional outbox + commit-ordered export ([docs/inventory](docs/inventory/README.md)) |
| **Search Service** | Elasticsearch schema, Vietnamese full-text search |
| **Review Service** | Rating aggregation, moderation workflow |
| **Notification Service** | Multi-channel delivery (email, SMS, push) |
| **Chat Service** | Real-time messaging, ScyllaDB storage |
| **Analytics Service** | Event-driven analytics pipeline |

## Tech Stack

### Backend

| Công nghệ | Vai trò |
|------------|---------|
| **Go** | High-concurrency services (cart, user, gateway) |
| **Java 21 / Spring Boot 4** | Business logic services (promotion) |
| **Traefik v3.x** | API Gateway, reverse proxy, load balancing |
| **Istio (ambient)** | Service mesh east-west trên Kubernetes: mTLS, workload identity, `AuthorizationPolicy` — target, chưa có manifest ([docs/mesh](docs/mesh/technology_choice.md)) |
| **gRPC + Protobuf** | Inter-service communication |

### Data & Messaging

| Công nghệ | Vai trò |
|------------|---------|
| **PostgreSQL** | Primary database, Event Store |
| **Redis** | Caching, session, rate limiting (không phải nguồn sự thật tồn kho) |
| **Apache Kafka** | Event streaming, async communication |
| **Elasticsearch** | Full-text search, CQRS read model |
| **ScyllaDB** | Chat message storage (high-throughput) |

### Infrastructure

| Công nghệ | Vai trò |
|------------|---------|
| **Docker / Docker Compose** | Containerization |
| **Temporal** | Saga orchestration, distributed workflows |
| **Debezium** | Change Data Capture (CDC) |
| **Jaeger** | Distributed tracing |
| **Prometheus + Grafana** | Monitoring & alerting |

## Bắt đầu

### Yêu cầu

- **Go** >= 1.21
- **Java** 21
- **Maven** >= 3.9
- **Docker** & **Docker Compose**

### Build Go Services

```bash
cd go
make install    # Cài đặt dependencies
make build      # Build tất cả services
make test       # Chạy tests
make lint       # Chạy linter
```

### Build Java Services

```bash
# Build common module trước
cd java/common
./mvnw clean install

# Build từng service
cd java/services/promotion-service
mvn clean package
```

### Chạy với Docker Compose

```bash
# API Gateway
cd go/services/api-gateway
docker-compose up -d

# User Service
cd go/services/user-service
docker-compose up -d

# Promotion Service
cd java/services/promotion-service
docker-compose up -d
```

## Tài liệu

Tài liệu thiết kế chi tiết cho từng service nằm trong thư mục `docs/`. Mỗi service bao gồm:

- **Database Design** - Schema và quan hệ giữa các bảng
- **Workflow** - Luồng xử lý nghiệp vụ
- **Events** - Domain events và event flow
- **Workers** - Background workers và async processing
- **Caching Schema** - Chiến lược cache với Redis
- **State Machine** - Quản lý trạng thái (nếu có)

## Đóng góp

1. Fork repository
2. Tạo feature branch (`git checkout -b feature/amazing-feature`)
3. Commit thay đổi (`git commit -m 'Add amazing feature'`)
4. Push lên branch (`git push origin feature/amazing-feature`)
5. Tạo Pull Request

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for more information.

hello
