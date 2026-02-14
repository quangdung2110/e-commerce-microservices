# Golang Microservices - Event Sourcing Platform

This directory contains high-performance Golang microservices for the e-commerce platform.

## Architecture

The Golang ecosystem implements 7 high-concurrency, performance-critical services:

1. **search-service** (Port 8087) - Full-text search with Vietnamese support
2. **cart-service** (Port 8081) - Real-time shopping cart with WebSocket
3. **inventory-service** (Port 8088) - High-concurrency stock management
4. **shipping-service** (Port 8089) - Carrier API integrations and tracking
5. **notification-service** (Port 8093) - Multi-channel notification delivery
6. **chat-service** (Port 8094) - Real-time messaging with ScyllaDB

## Directory Structure

```
go/
├── go.mod                      # Root Go module
├── go.sum                      # Dependency checksums
├── Makefile                    # Build automation
├── README.md                   # This file
├── pkg/                        # Shared packages (reusable across services)
│   ├── events/                 # Event definitions
│   │   └── base_event.go       # BaseEvent struct (equivalent to Java BaseEvent)
│   ├── kafka/                  # Kafka client wrappers
│   │   ├── publisher.go        # Event publisher
│   │   └── consumer.go         # Event consumer with idempotency
│   ├── eventstore/             # Event Store implementation
│   │   └── store.go            # PostgreSQL event store
│   ├── cache/                  # Redis cache manager
│   │   └── redis.go            # Cache operations
│   ├── temporal/               # Temporal workflow helpers
│   │   └── client.go           # Temporal client wrapper
│   └── config/                 # Configuration loading
│       └── loader.go           # Viper-based config with env vars
├── services/                   # Individual microservices
│   ├── inventory-service/      # Stock management service
│   │   ├── cmd/
│   │   │   └── main.go         # Service entry point
│   │   ├── internal/
│   │   │   ├── domain/         # Domain models and business logic
│   │   │   ├── handler/        # HTTP/gRPC handlers
│   │   │   └── repository/     # Data access layer
│   │   ├── config/
│   │   │   └── application.yaml
│   │   ├── go.mod              # Service-specific dependencies (optional)
│   │   └── Dockerfile
│   ├── shipping-service/       # (Similar structure)
│   ├── cart-service/
│   ├── search-service/
│   ├── notification-service/
│   └── chat-service/
└── bin/                        # Compiled binaries (gitignored)
    ├── inventory-service
    ├── shipping-service
    └── ...
```

## Shared Packages (pkg/)

### 1. events/ - Event Definitions

**File**: `pkg/events/base_event.go`

Equivalent to Java's `com.example.common.event.BaseEvent`:

```go
type BaseEvent struct {
    EventID     string    `json:"eventId"`
    AggregateID string    `json:"aggregateId"`
    EventType   string    `json:"eventType"`
    OccurredOn  time.Time `json:"occurredOn"`
    Version     int       `json:"version"`
}
```

**Usage**:
```go
import "github.com/yourusername/spring-event-sourcing/go/pkg/events"

// Create domain event extending BaseEvent
type InventoryReservedEvent struct {
    events.BaseEvent
    ProductID string  `json:"productId"`
    Quantity  int     `json:"quantity"`
    OrderID   string  `json:"orderId"`
}
```

### 2. kafka/ - Kafka Client

**Files**: 
- `pkg/kafka/publisher.go` - Event publisher (equivalent to Java `KafkaEventPublisher`)
- `pkg/kafka/consumer.go` - Event consumer (equivalent to Java `KafkaEventConsumer`)

**Publisher Usage**:
```go
import "github.com/yourusername/spring-event-sourcing/go/pkg/kafka"

// Create publisher
publisher, err := kafka.NewKafkaEventPublisher(
    "localhost:9092",
    "inventory-events",
    logger,
)

// Publish event
event := &InventoryReservedEvent{...}
err = publisher.Publish(event, event.AggregateID)

// Cleanup
defer publisher.Close()
```

**Consumer Usage**:
```go
// Define event handler
handler := func(event events.DomainEvent) error {
    // Process event
    return nil
}

// Create consumer
consumer, err := kafka.NewKafkaEventConsumer(kafka.ConsumerConfig{
    Brokers:         []string{"localhost:9092"},
    Topics:          []string{"order-events"},
    GroupID:         "inventory-service-group",
    DeadLetterTopic: "order-events-dlt",
    Handler:         handler,
    Logger:          logger,
})

// Start consuming
ctx := context.Background()
err = consumer.Start(ctx)
```

### 3. eventstore/ - Event Persistence

**File**: `pkg/eventstore/store.go`

Equivalent to Java's `com.example.common.repository.EventStore`:

```go
// Create event store
store, err := eventstore.NewPostgreSQLEventStore(eventstore.EventStoreConfig{
    Host:     "localhost",
    Port:     5432,
    Database: "inventory_db",
    User:     "postgres",
    Password: "password",
    SSLMode:  "disable",
    Logger:   logger,
})

// Save event
err = store.Save(ctx, event)

// Retrieve events
events, err := store.FindByAggregateID(ctx, "order-12345")
```

### 4. cache/ - Redis Cache

**File**: `pkg/cache/redis.go`

Equivalent to Java's `com.example.common.util.CacheManager`:

```go
// Create cache manager
cache, err := cache.NewRedisCacheManager(cache.RedisConfig{
    Host: "localhost",
    Port: 6379,
    DB:   0,
    Logger: logger,
})

// Set cache with TTL
err = cache.Set(ctx, "product:123", product, 5*time.Minute)

// Get cache
var product Product
err = cache.Get(ctx, "product:123", &product)

// Delete cache
err = cache.Delete(ctx, "product:123")
```

### 5. config/ - Configuration Loading

**File**: `pkg/config/loader.go`

Viper-based configuration with environment variable override:

```go
// Load configuration
config, err := config.LoadConfig("inventory-service")

// Access configuration
fmt.Println(config.Server.Port)        // 8088
fmt.Println(config.Kafka.Brokers)      // [localhost:9092]
fmt.Println(config.Database.Host)      // localhost
```

**Configuration Priority** (highest to lowest):
1. Environment variables (e.g., `KAFKA_BROKERS=kafka:9092`)
2. Config file (`services/inventory-service/config/application.yaml`)
3. Defaults in `config/loader.go`

### 6. temporal/ - Temporal Client

**File**: `pkg/temporal/client.go`

Wrapper for Temporal SDK:

```go
// Create Temporal client
client, err := temporal.NewTemporalClient(temporal.TemporalConfig{
    HostPort:  "localhost:7233",
    Namespace: "default",
    Logger:    logger,
})

// Use underlying Temporal client
temporalClient := client.GetClient()

// Cleanup
defer client.Close()
```

## Building Services

### Prerequisites

- Go 1.22 or higher
- PostgreSQL (for event store)
- Redis (for caching)
- Kafka (for event streaming)
- Temporal (for workflow orchestration)

### Install Dependencies

```bash
cd go
go mod download
```

### Build All Services

```bash
# Build all services
make build

# Or build individually
go build -o bin/inventory-service ./services/inventory-service/cmd
go build -o bin/shipping-service ./services/shipping-service/cmd
```

### Run a Service

```bash
# Set environment variables
export KAFKA_BROKERS=localhost:9092
export REDIS_HOST=localhost
export DATABASE_HOST=localhost
export DATABASE_NAME=inventory_db

# Run service
./bin/inventory-service
```

## Testing

### Unit Tests

```bash
# Test shared packages
go test -v ./pkg/...

# Test specific service
go test -v ./services/inventory-service/...
```

### Integration Tests

```bash
# Run with integration tag
go test -v -tags=integration ./services/inventory-service/...
```

## Docker Support

### Build Docker Image

```bash
# From go/ directory
docker build -t inventory-service:latest -f services/inventory-service/Dockerfile .
```

### Run with Docker Compose

```bash
# From root directory
docker-compose up inventory-service
```

## Environment Variables

All services support these common environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP server port | 8080 |
| `KAFKA_BROKERS` | Kafka broker addresses (comma-separated) | localhost:9092 |
| `KAFKA_GROUP_ID` | Kafka consumer group ID | default-group |
| `KAFKA_TOPICS` | Topics to subscribe (comma-separated) | - |
| `REDIS_HOST` | Redis host | localhost |
| `REDIS_PORT` | Redis port | 6379 |
| `REDIS_PASSWORD` | Redis password | - |
| `DATABASE_HOST` | PostgreSQL host | localhost |
| `DATABASE_PORT` | PostgreSQL port | 5432 |
| `DATABASE_NAME` | Database name | - |
| `DATABASE_USER` | Database user | postgres |
| `DATABASE_PASSWORD` | Database password | - |
| `TEMPORAL_HOST_PORT` | Temporal server address | localhost:7233 |
| `TEMPORAL_NAMESPACE` | Temporal namespace | default |

## Service-Specific Details

### inventory-service (Port 8088)
- High-concurrency stock reservations (1000+ req/min)
- ACID-compliant reservation system
- Multi-warehouse support
- Expiry-based reservation cleanup

### shipping-service (Port 8089)
- Multi-carrier API integration
- Webhook processing for tracking updates
- Geo-based warehouse selection
- Real-time tracking (100K+ updates/day)

### cart-service (Port 8081)
- WebSocket for real-time cart sync
- Redis-backed storage (50K+ ops/hour)
- Guest cart merging on login
- Price validation integration

### search-service (Port 8087)
- Elasticsearch integration
- Vietnamese text tokenization
- Hybrid search (vector + keyword)
- Sub-second response time (<200ms)

### notification-service (Port 8093)
- Multi-channel delivery (Email, SMS, Push)
- SendGrid/Twilio/FCM integration
- Asynchronous processing (500K+ notifications/day)
- Rate limiting per user

### chat-service (Port 8094)
- WebSocket server for real-time messaging
- ScyllaDB backend (1M+ messages/day)
- Bot-to-human escalation
- Presence tracking with Redis pub/sub

## Comparing with Java Common Module

| Java (common/) | Go (pkg/) | Notes |
|----------------|-----------|-------|
| `event.BaseEvent` | `events.BaseEvent` | Struct with JSON tags |
| `publisher.KafkaEventPublisher` | `kafka.KafkaEventPublisher` | Uses confluent-kafka-go |
| `consumer.KafkaEventConsumer` | `kafka.KafkaEventConsumer` | Includes idempotency check |
| `repository.EventStore` | `eventstore.EventStore` | Interface + PostgreSQL impl |
| `util.CacheManager` | `cache.CacheManager` | Interface + Redis impl |
| `config.*Properties` | `config.LoadConfig()` | Viper-based with env override |
| `TemporalConfig` | `temporal.TemporalClient` | SDK wrapper |

## Performance Characteristics

| Service | Throughput | Latency (p99) | Memory |
|---------|------------|---------------|--------|
| inventory-service | 1000+ req/s | <10ms | ~50MB |
| shipping-service | 500+ req/s | <20ms | ~40MB |
| cart-service | 2000+ req/s | <5ms | ~60MB |
| search-service | 5000+ req/s | <50ms | ~100MB |
| notification-service | 10000+ events/s | <100ms | ~80MB |
| chat-service | 5000+ messages/s | <10ms | ~120MB |

## Development Workflow

1. **Install dependencies**: `go mod download`
2. **Run linter**: `golangci-lint run ./...`
3. **Format code**: `gofmt -s -w .`
4. **Run tests**: `go test -v ./...`
5. **Build**: `make build`
6. **Run**: `./bin/{service-name}`

## Troubleshooting

### Import errors
```bash
# Ensure module path is correct in go.mod
go mod edit -module github.com/yourusername/spring-event-sourcing/go

# Update dependencies
go mod tidy
```

### Kafka connection issues
```bash
# Verify Kafka is running
docker ps | grep kafka

# Test with kafkacat
kafkacat -b localhost:9092 -L
```

### Redis connection issues
```bash
# Test Redis connection
redis-cli ping

# Check if Redis is accepting connections
telnet localhost 6379
```

## Contributing

When adding new services:

1. Follow the directory structure: `services/{service-name}/cmd/main.go`
2. Reuse shared packages from `pkg/`
3. Add configuration to `services/{service-name}/config/application.yaml`
4. Document service-specific environment variables
5. Add Dockerfile and update docker-compose.yml
6. Write unit and integration tests

## Resources

- [Go Module Documentation](https://go.dev/doc/modules)
- [Confluent Kafka Go Client](https://github.com/confluentinc/confluent-kafka-go)
- [Temporal Go SDK](https://docs.temporal.io/dev-guide/go)
- [Viper Configuration](https://github.com/spf13/viper)
- [Go-Redis](https://redis.uptrace.dev/)
