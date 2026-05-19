# E-Commerce Microservices Platform

Hệ thống E-Commerce được xây dựng theo kiến trúc **Microservices** với **Event Sourcing** và **CQRS**, sử dụng hai hệ sinh thái chính: **Java (Spring Boot 4)** và **Go**.

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

- **Event Sourcing**: Mọi thay đổi trạng thái được lưu dưới dạng chuỗi events bất biến
- **CQRS**: Tách biệt luồng đọc và ghi để tối ưu hiệu năng
- **Saga Pattern**: Quản lý transaction phân tán qua Temporal workflows
- **Outbox Pattern**: Đảm bảo tính nhất quán giữa database và message broker

## Cấu trúc thư mục

```
e-commerce-microservices/
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
│       └── shipping-service/    # Vận chuyển & theo dõi đơn hàng
│
└── docs/                        # Tài liệu thiết kế chi tiết
    ├── auth/                    # Authentication & Authorization
    ├── user/                    # User management
    ├── product/                 # Product catalog
    ├── cart/                    # Shopping cart
    ├── order/                   # Order management
    ├── checkout/                # Checkout flow
    ├── payment/                 # Payment processing
    ├── inventory/               # Inventory management
    ├── shipping/                # Shipping & delivery
    ├── promotion/               # Promotions & coupons
    ├── search/                  # Full-text search
    ├── review/                  # Product reviews
    ├── notification/            # Notifications
    ├── chat/                    # Real-time chat
    ├── analytics/               # Analytics & reporting
    ├── gateway/                 # API Gateway design
    └── golang-common-layout.md  # Go project layout guidelines
```

## Microservices

### Go Services

| Service | Mô tả | Port |
|---------|--------|------|
| **API Gateway** | Traefik v3.x - routing, rate limiting, JWT auth, tracing | 80 (web), 8090 (metrics) |
| **Cart Service** | Giỏ hàng real-time với WebSocket, idempotency, outbox pattern | 8080 |
| **User Service** | Quản lý người dùng, GDPR compliance, Debezium CDC | 8080 |

### Java Services (Spring Boot 4 / Java 21)

| Service | Mô tả |
|---------|--------|
| **Common Module** | Shared library - BaseEvent, EventPublisher, EventStore, Kafka config |
| **Promotion Service** | Rule engine, quản lý coupon/voucher, chiến dịch khuyến mãi |
| **Shipping Service** | Tích hợp nhà vận chuyển, theo dõi đơn hàng, state machine |

### Services được thiết kế (Documentation)

Ngoài các service đã implement, hệ thống có tài liệu thiết kế chi tiết cho:

| Service | Tài liệu |
|---------|-----------|
| **Auth Service** | JWT + Redis Denylist, JWKS, token rotation |
| **Product Service** | Catalog management, state machine, caching |
| **Order Service** | Temporal Saga, CQRS + Elasticsearch, cold data archiving |
| **Checkout Service** | Idempotency, security analysis, caching |
| **Payment Service** | State machine, refund boundary, multi-provider |
| **Inventory Service** | Smart routing engine, Redis reservation, SLO observability |
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
| **Java 21 / Spring Boot 4** | Business logic services (promotion, shipping) |
| **Traefik v3.x** | API Gateway, reverse proxy, load balancing |
| **gRPC + Protobuf** | Inter-service communication |

### Data & Messaging

| Công nghệ | Vai trò |
|------------|---------|
| **PostgreSQL** | Primary database, Event Store |
| **Redis** | Caching, session, rate limiting, inventory reservation |
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

cd java/services/shipping-service
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

# Shipping Service
cd java/services/shipping-service
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

Project license chưa được xác định. (Chưa có file `LICENSE` trong repository.)
