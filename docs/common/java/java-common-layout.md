# Java Microservices - Common Layout Design

> **Document Version:** 1.0
> **Date:** June 10, 2026
> **Status:** Proposed Standard
> **Applies To:** All Java/Spring Boot microservices (product-service, order-service, payment-service, inventory-service, promotion-service, search-service, etc.)

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Design Principles](#2-design-principles)
3. [Proposed Standard Layout](#3-proposed-standard-layout)
4. [Directory Details](#4-directory-details)
5. [Comparison: Current vs Proposed](#5-comparisoncurrent-vs-proposed)
6. [Migration Guide](#6-migration-guide)
7. [Code Examples](#7-code-examples)
8. [Best Practices Checklist](#8-best-practices-checklist)

---

## 1. Problem Statement

### Current State

Các service hiện tại có cấu trúc thư mục khác nhau, gây khó khăn cho:

| Issue | Impact |
|-------|--------|
| Khó onboarding developer mới | Phải học cấu trúc riêng của từng service |
| Khó di chuyển code giữa services | Logic chung bị duplicate |
| Khó debug và maintain | Không có convention thống nhất |
| Tốn thời gian code review | Mỗi PR có style khác nhau |

### Example: Structure Differences

```
product-service:                      order-service:
├── src/main/java/                  ├── src/
│   ├── com.platform.product/       │   ├── main/java/.../
│   │   ├── controller/            │   ├── resources/
│   │   ├── service/               │   ├── config/
│   │   │   └── impl/             │   ├── domain/
│   │   ├── repository/            │   ├── service/
│   │   ├── entity/               │   ├── controller/
│   │   ├── dto/                  │   ├── kafka/
│   │   ├── config/               │   ├── workers/
│   │   ├── kafka/               │   ├── adapter/
│   │   ├── worker/               │   └── common/
│   │   └── exception/            │
│   └── resources/                │
└── src/test/                        └── test/
```

---

## 2. Design Principles

### 2.1 Architecture Pattern

Sử dụng kết hợp **Layered Architecture** và **Hexagonal Architecture (Ports & Adapters)**:

```
┌─────────────────────────────────────────────────────────────────┐
│                         ENTRY POINTS                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ HTTP Server │  │ gRPC Server │  │Kafka Consumer│             │
│  │(Tomcat/Jetty)│ │(gRPC Netty)│  │  (Workers)  │             │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘             │
└─────────┼─────────────────┼─────────────────┼─────────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                       API LAYER (Adapters - Primary)              │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │  Controllers │  Middlewares │  DTOs │  Validators       │    │
│  └──────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────┐
│                     APPLICATION LAYER                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │   Services  │  │   Mappers   │  │   Ports     │              │
│  │ (Use Cases)│  │ (DTO ↔ Entity)│  │(Interfaces) │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────┐
│                      DOMAIN LAYER (Core Business Logic)           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  Entities   │  │Value Objects│  │   Events    │              │
│  │ (Aggregate) │  │   (VO)      │  │(Domain Evts)│              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │ Enums       │  │  Exceptions │  │  Constants  │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────┐
│                 INFRASTRUCTURE LAYER (Adapters - Secondary)        │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐     │
│  │ Postgres  │  │   Redis   │  │   Kafka   │  │  gRPC     │     │
│  │Repository│  │Repository │  │Publisher  │  │  Client   │     │
│  │(JPA/Hibernate)│(Lettuce)│  │(Spring K.)│  │(gRPC St.)│     │
│  └───────────┘  └───────────┘  └───────────┘  └───────────┘     │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Key Principles

| Principle | Description |
|-----------|-------------|
| **Structure before Framework** | Business logic không phụ thuộc vào HTTP, Redis, Kafka |
| **Interface as Contract** | Dùng interfaces (Ports) để define contracts, implementations ở infrastructure |
| **Dependency Inversion** | Domain không biết gì về infrastructure |
| **Single Responsibility** | Mỗi package chỉ làm một việc |
| **Predictable Structure** | Developer biết file ở đâu khi cần |
| **Spring-native** | Tận dụng tối đa annotation và convention của Spring Boot |

### 2.3 Dependency Rules

```
controller/   → service/, dto/, domain/
service/      → domain/, port/, dto/, event/
domain/       → domain/vo, domain/enums (KHÔNG ngược lại)
port/         → domain/ (interface defined in domain, implemented in infrastructure)
infrastructure/ → domain/, port/, event/
worker/       → domain/, infrastructure/, port/, event/
common/       → KHÔNG import bất kỳ service-specific code nào
```

---

## 3. Proposed Standard Layout

### 3.1 Complete Directory Structure

```
{service-name}/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── platform/
│   │   │           └── {service-name}/
│   │   │               │
│   │   │               ├── {ServiceName}Application.java    # Main entry
│   │   │               │
│   │   │               ├── config/                          # Spring configuration
│   │   │               │   ├── AppConfig.java               # App-level beans
│   │   │               │   ├── WebConfig.java               # Web (Tomcat/Jetty)
│   │   │               │   ├── KafkaConfig.java             # Kafka producer/consumer
│   │   │               │   ├── RedisConfig.java             # Redis client
│   │   │               │   ├── GrpcConfig.java              # gRPC server
│   │   │               │   ├── YamlPropertySourceFactory.java
│   │   │               │   └── scheduling/
│   │   │               │       └── SchedulerConfig.java    # @EnableScheduling
│   │   │               │
│   │   │               ├── controller/                      # REST API layer (Primary Adapter)
│   │   │               │   ├── {EntityName}Controller.java  # ONE file per entity
│   │   │               │   ├── HealthController.java
│   │   │               │   └── GlobalExceptionHandler.java
│   │   │               │
│   │   │               ├── dto/
│   │   │               │   ├── request/                     # Incoming requests
│   │   │               │   │   ├── Create{Entity}Request.java
│   │   │               │   │   ├── Update{Entity}Request.java
│   │   │               │   │   └── SearchRequest.java
│   │   │               │   ├── response/                   # Outgoing responses
│   │   │               │   │   ├── {Entity}Response.java
│   │   │               │   │   ├── PagedResponse.java
│   │   │               │   │   └── ErrorResponse.java
│   │   │               │   ├── grpc/                       # gRPC request/response
│   │   │               │   │   └── {Entity}ProtoMapper.java
│   │   │               │   └── mapper/                      # MapStruct mappers
│   │   │               │       ├── {Entity}Mapper.java
│   │   │               │       └── {Entity}MapperImpl.java  # Generated
│   │   │               │
│   │   │               ├── service/                         # Application layer (Use Cases)
│   │   │               │   ├── {Entity}Service.java         # Interface (Port)
│   │   │               │   └── impl/
│   │   │               │       └── {Entity}ServiceImpl.java
│   │   │               │
│   │   │               ├── domain/                          # Domain layer (CORE)
│   │   │               │   ├── entity/                     # Aggregate Roots & Entities
│   │   │               │   │   ├── {Entity}.java           # ONE file per aggregate root
│   │   │               │   │   ├── {RelatedEntity}.java
│   │   │               │   │   └── {EntityId}.java         # ID class (if composite/ULID)
│   │   │               │   ├── vo/                         # Value Objects
│   │   │               │   │   ├── Money.java
│   │   │               │   │   ├── PhoneNumber.java
│   │   │               │   │   └── EmailAddress.java
│   │   │               │   ├── enums/                      # Domain enums
│   │   │               │   │   ├── {Entity}Status.java
│   │   │               │   │   └── EventType.java
│   │   │               │   ├── event/                     # Domain events
│   │   │               │   │   ├── {Entity}CreatedEvent.java
│   │   │               │   │   ├── {Entity}UpdatedEvent.java
│   │   │               │   │   └── DomainEvent.java       # Base class
│   │   │               │   ├── exception/                 # Domain exceptions
│   │   │               │   │   ├── {Entity}NotFoundException.java
│   │   │               │   │   ├── InvalidStateTransitionException.java
│   │   │               │   │   └── DomainException.java    # Base class
│   │   │               │   └── statemachine/               # FSM (Spring State Machine)
│   │   │               │       ├── {Entity}StateMachineConfig.java
│   │   │               │       ├── {Entity}State.java
│   │   │               │       ├── {Entity}Event.java
│   │   │               │       └── guards/
│   │   │               │           └── {GuardClass}Guard.java
│   │   │               │
│   │   │               ├── port/                            # Interfaces (Ports in Hexagonal)
│   │   │               │   ├── inbound/                   # Primary ports (driven by outside)
│   │   │               │   │   └── {Entity}Service.java   # Mirrors service interface
│   │   │               │   └── outbound/                   # Secondary ports (driven by us)
│   │   │               │       ├── {Entity}Repository.java
│   │   │               │       ├── {Entity}CacheRepository.java
│   │   │               │       ├── EventPublisher.java
│   │   │               │       └── ExternalServiceClient.java
│   │   │               │
│   │   │               ├── infrastructure/                 # Infrastructure adapters
│   │   │               │   ├── persistence/               # JPA/Hibernate repositories
│   │   │               │   │   ├── entity/               # JPA entity models
│   │   │               │   │   │   ├── {Entity}Entity.java
│   │   │               │   │   │   └── {Entity}EntityPK.java
│   │   │               │   │   ├── repository/            # Spring Data JPA
│   │   │               │   │   │   ├── {Entity}Repository.java
│   │   │               │   │   │   └── {Entity}RepositoryCustom.java
│   │   │               │   │   └── mapper/               # Entity ↔ Domain mapper
│   │   │               │   │       └── {Entity}EntityMapper.java
│   │   │               │   ├── redis/                    # Redis adapters
│   │   │               │   │   ├── {Entity}RedisRepository.java
│   │   │               │   │   └── LuaScriptLoader.java
│   │   │               │   ├── kafka/                    # Kafka producers/consumers
│   │   │               │   │   ├── producer/
│   │   │               │   │   │   └── {Domain}EventProducer.java
│   │   │               │   │   ├── consumer/
│   │   │               │   │   │   ├── {Topic}Consumer.java
│   │   │               │   │   │   └── {ConsumerGroupName}Handler.java
│   │   │               │   │   └── deserializer/
│   │   │               │   │       └── CloudEventDeserializer.java
│   │   │               │   ├── grpc/                     # gRPC clients
│   │   │               │   │   └── {ExternalService}GrpcClient.java
│   │   │               │   └── external/                 # External service adapters
│   │   │               │       └── {Provider}Adapter.java
│   │   │               │
│   │   │               ├── worker/                       # Background workers
│   │   │               │   ├── consumer/                 # Kafka consumer workers
│   │   │               │   │   ├── {WorkerName}Worker.java
│   │   │               │   │   └── {WorkerName}Handler.java
│   │   │               │   ├── scheduler/               # Scheduled jobs (@Scheduled)
│   │   │               │   │   ├── {JobName}Job.java
│   │   │               │   │   └── DistributedLockRunner.java
│   │   │               │   └── processor/               # Batch processors
│   │   │               │       ├── {ProcessorName}Processor.java
│   │   │               │       └── BatchProcessor.java
│   │   │               │
│   │   │               └── common/                       # Shared utilities (service-agnostic)
│   │   │                   ├── constants/                # Magic numbers and strings
│   │   │                   │   └── ServiceConstants.java
│   │   │                   ├── errors/                   # Error codes and messages
│   │   │                   │   └── ErrorCode.java
│   │   │                   ├── keybuilder/               # Redis key builders
│   │   │                   │   └── RedisKeyBuilder.java
│   │   │                   ├── circuitbreaker/           # Resilience4j config
│   │   │                   │   └── CircuitBreakerConfig.java
│   │   │                   ├── retry/                   # Retry policies
│   │   │                   │   └── RetryConfig.java
│   │   │                   ├── utils/                   # Generic utilities
│   │   │                   │   ├── JsonUtils.java
│   │   │                   │   └── DateTimeUtils.java
│   │   │                   └── tracing/                  # Distributed tracing utilities
│   │   │                       └── TracingUtils.java
│   │   │
│   │   └── resources/
│   │       ├── application.yml                  # Main config
│   │       ├── application-{profile}.yml       # Profile-specific config
│   │       ├── db/migration/                   # Flyway migrations
│   │       │   ├── V1__initial_schema.sql
│   │       │   └── V2__add_{entity}.sql
│   │       ├── lua/                           # Redis Lua scripts
│   │       │   ├── inventory_fast_sync.lua
│   │       │   └── cart_batch_delete.lua
│   │       ├── kafka/                        # Kafka consumer configs (optional YAML)
│   │       │   └── consumer.properties
│   │       ├── banner.txt                    # Spring ASCII banner
│   │       └── config.yaml                   # External config reference
│   │
│   └── test/
│       └── java/
│           └── com/
│               └── platform/
│                   └── {service-name}/
│                       ├── controller/         # Controller tests
│                       ├── service/            # Service unit tests
│                       ├── domain/             # Domain tests
│                       ├── infrastructure/     # Repository/integration tests
│                       ├── worker/             # Worker tests
│                       └── {ServiceName}ApplicationTests.java
│
├── proto/                              # gRPC proto files (if using gRPC)
│   └── {service-name}.proto
│
├── docker/                             # Docker files
│   ├── Dockerfile
│   └── docker-compose.yml
│
├── config/                            # Externalized config (for K8s/ConfigMaps)
│   ├── application.yml
│   └── .env.example
│
├── pom.xml                            # Maven build
├── mvnw                               # Maven wrapper
├── mvnw.cmd
├── Makefile                           # Build automation
├── .editorconfig                      # Code style
├── .gitignore
└── README.md                          # Service documentation
```

### 3.2 Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Package | lowercase dot-separated | `com.platform.product.domain.entity` |
| Class | PascalCase | `ProductController`, `ProductService` |
| Interface | PascalCase, starts with I or ends with Port/Repository | `IProductService`, `ProductRepository` |
| Method | camelCase | `findById`, `createProduct` |
| Variable | camelCase | `productId`, `userId` |
| Constant | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT`, `DEFAULT_PAGE_SIZE` |
| Enum | PascalCase | `ProductStatus`, `EventType` |
| Enum value | UPPER_SNAKE_CASE | `ACTIVE`, `DRAFT` |
| DTO | PascalCase, ends with Request/Response | `CreateProductRequest`, `ProductResponse` |
| JPA Entity | PascalCase, ends with Entity | `ProductEntity`, `SkuEntity` |
| Test class | PascalCase, ends with Test | `ProductServiceTest`, `ProductControllerTest` |
| Proto file | snake_case | `product_service.proto` |

---

## 4. Directory Details

### 4.1 `config/` - Spring Configuration

**Purpose:** Tất cả cấu hình Spring Boot, phân loại theo từng infrastructure component.

```
config/
├── AppConfig.java          # @Bean definitions, @Configuration
├── WebConfig.java          # WebMvcConfigurer, CORS, interceptors
├── KafkaConfig.java        # ConsumerFactory, ProducerFactory, ConcurrentKafkaListenerContainerFactory
├── RedisConfig.java        # RedisConnectionFactory, RedisTemplate, LettuceConnectionFactory
├── GrpcConfig.java         # GrpcServerFactory, channel credentials
├── SecurityConfig.java     # Spring Security (if used)
└── scheduling/
    └── SchedulerConfig.java
```

**Key Pattern:**

```java
// KafkaConfig.java
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3); // Tune per topic
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    public NewTopic productEventsTopic() {
        return TopicBuilder.name("t.product.events")
                .partitions(12)
                .replicas(1)
                .build();
    }
}
```

### 4.2 `controller/` - REST API Layer

**Purpose:** Xử lý HTTP requests và responses. Là **Primary Adapter** trong Hexagonal Architecture.

**ONE file per entity:**

```
controller/
├── ProductController.java      # Product-related endpoints
├── SkuController.java         # SKU-related endpoints
├── CategoryController.java    # Category endpoints
├── HealthController.java     # Health/readiness probes
└── GlobalExceptionHandler.java
```

**Pattern: Controller with Service injection:**

```java
// controller/ProductController.java
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductService productService;
    private final ProductMapper productMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody CreateProductRequest request) {
        log.info("Creating product: {}", request.getName());
        Product product = productMapper.toDomain(request);
        Product created = productService.create(product);
        return productMapper.toResponse(created);
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable String id) {
        return productService.findById(id)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @GetMapping
    public PagedResponse<ProductResponse> searchProducts(
            @RequestParam(required = false) String categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return productService.search(categoryId, page, size)
                .map(productMapper::toResponse);
    }
}
```

**Global Exception Handler:**

```java
// controller/GlobalExceptionHandler.java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ProductNotFoundException ex) {
        return ErrorResponse.of("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Invalid",
                        (a, b) -> a
                ));
        return ErrorResponse.of("VALIDATION_ERROR", "Validation failed", errors);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred");
    }
}
```

### 4.3 `dto/` - Data Transfer Objects

```
dto/
├── request/
│   ├── CreateProductRequest.java
│   ├── UpdateProductRequest.java
│   └── SearchProductRequest.java
├── response/
│   ├── ProductResponse.java
│   ├── ProductListResponse.java
│   ├── PagedResponse.java        # Generic paged wrapper
│   └── ErrorResponse.java
├── mapper/
│   ├── ProductMapper.java       # MapStruct interface
│   └── ProductMapperImpl.java   # Generated
└── grpc/
    └── ProductProtoMapper.java
```

**Request DTO Pattern (with Bean Validation):**

```java
// dto/request/CreateProductRequest.java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateProductRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @NotBlank(message = "Category ID is required")
    private String categoryId;

    @Valid
    private List<CreateSkuRequest> skus;

    private Map<String, Object> attributes;
}
```

**Response DTO Pattern:**

```java
// dto/response/ProductResponse.java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {
    private String productId;
    private String name;
    private String description;
    private String categoryId;
    private ProductStatus status;
    private List<SkuResponse> skus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**Paged Response:**

```java
// dto/response/PagedResponse.java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedResponse<T> {
    private List<T> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;
}
```

### 4.4 `service/` - Application Layer

**Purpose:** Điều phối use cases, transaction boundaries, và orchestration. KHÔNG chứa business logic thuần túy (đó là domain layer).

```
service/
├── ProductService.java              # Interface (inbound port)
└── impl/
    └── ProductServiceImpl.java     # Implementation with @Transactional
```

**Service Interface Pattern (Inbound Port):**

```java
// service/ProductService.java
public interface ProductService {

    Product create(Product product);

    Optional<Product> findById(String productId);

    PagedResponse<Product> search(String categoryId, int page, int size);

    Product update(String productId, Product product);

    void delete(String productId);

    Product submitForReview(String productId);

    Product activate(String productId);

    Product hide(String productId);
}
```

**Service Implementation Pattern:**

```java
// service/impl/ProductServiceImpl.java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductCacheRepository cacheRepository;
    private final DomainEventPublisher eventPublisher;
    private final ProductStateMachineService stateMachineService;

    @Override
    public Product create(Product product) {
        log.info("Creating product: {}", product.getName());

        // Check duplicates
        if (productRepository.existsBySlug(product.getSlug())) {
            throw new DuplicateSlugException(product.getSlug());
        }

        // Persist
        Product saved = productRepository.save(product);

        // Publish event
        eventPublisher.publish(new ProductCreatedEvent(saved));

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(String productId) {
        // Try cache first
        return cacheRepository.findById(productId)
                .or(() -> {
                    Product product = productRepository.findById(productId)
                            .orElse(null);
                    if (product != null) {
                        cacheRepository.save(product);
                    }
                    return Optional.ofNullable(product);
                });
    }

    @Override
    public Product submitForReview(String productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        stateMachineService.sendEvent(product, ProductEvent.SUBMIT);
        return productRepository.save(product);
    }
}
```

### 4.5 `domain/` - Domain Layer

**Purpose:** Chứa pure business logic, KHÔNG phụ thuộc vào infrastructure. Đây là lõi nghiệp vụ của service.

#### 4.5.1 `entity/` - Domain Entities

**ONE file per aggregate root:**

```java
// domain/entity/Product.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    private ProductId id;
    private String name;
    private Slug slug;
    private CategoryId categoryId;
    private String description;
    private ProductStatus status;
    private boolean deleted;
    private Map<String, Object> attributes;
    private List<Sku> skus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Business methods
    public void submit() {
        if (this.status != ProductStatus.DRAFT) {
            throw new InvalidStateTransitionException(
                    "Can only submit DRAFT product, current status: " + this.status);
        }
        if (this.skus == null || this.skus.isEmpty()) {
            throw new DomainException("Product must have at least one SKU to submit");
        }
        this.status = ProductStatus.ACTIVE;
    }

    public void hide() {
        if (this.status == ProductStatus.HIDDEN) {
            return; // Idempotent
        }
        this.status = ProductStatus.HIDDEN;
    }

    public void addSku(Sku sku) {
        if (this.skus == null) {
            this.skus = new ArrayList<>();
        }
        this.skus.add(sku);
    }

    public void markDeleted() {
        this.deleted = true;
    }
}
```

#### 4.5.2 `vo/` - Value Objects

```java
// domain/vo/EmailAddress.java
@Data
@EqualsAndHashCode
public final class EmailAddress {

    private final String value;

    private EmailAddress(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException("Email cannot be empty");
        }
        String trimmed = value.trim().toLowerCase();
        if (!trimmed.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new DomainException("Invalid email format: " + value);
        }
        this.value = trimmed;
    }

    public static EmailAddress of(String value) {
        return new EmailAddress(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
```

#### 4.5.3 `enums/` - Domain Enums

```java
// domain/enums/ProductStatus.java
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    HIDDEN
}
```

#### 4.5.4 `event/` - Domain Events

```java
// domain/event/DomainEvent.java
@Data
public abstract class DomainEvent {
    private final String eventId;
    private final String eventType;
    private final String aggregateId;
    private final Instant timestamp;
    private final int schemaVersion;

    protected DomainEvent(String eventType, String aggregateId) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.timestamp = Instant.now();
        this.schemaVersion = 1;
    }
}

// domain/event/ProductCreatedEvent.java
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductCreatedEvent extends DomainEvent {
    private final String productId;
    private final String name;
    private final String categoryId;
    private final ProductStatus status;

    public ProductCreatedEvent(Product product) {
        super("PRODUCT_CREATED", product.getId().getValue());
        this.productId = product.getId().getValue();
        this.name = product.getName();
        this.categoryId = product.getCategoryId().getValue();
        this.status = product.getStatus();
    }
}
```

#### 4.5.5 `exception/` - Domain Exceptions

```java
// domain/exception/DomainException.java
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}

// domain/exception/ProductNotFoundException.java
public class ProductNotFoundException extends DomainException {
    public ProductNotFoundException(String productId) {
        super("Product not found: " + productId);
    }
}

// domain/exception/InvalidStateTransitionException.java
public class InvalidStateTransitionException extends DomainException {
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
```

#### 4.5.6 `statemachine/` - Spring State Machine

```java
// domain/statemachine/ProductStateMachineConfig.java
@Configuration
@EnableStateMachineFactory
public class ProductStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<ProductStatus, ProductEvent> {

    @Override
    public void configure(StateMachineConfigurationConfigurer<ProductStatus, ProductEvent> config)
            throws Exception {
        config
                .withConfiguration()
                .autoStartup(false)
                .stateMachineFactory(stateMachineFactory());
    }

    @Override
    public void configure(StateMachineStateConfigurer<ProductStatus, ProductEvent> states)
            throws Exception {
        states
                .withStates()
                .initial(ProductStatus.DRAFT)
                .states(EnumSet.allOf(ProductStatus.class))
                .end(ProductStatus.DELETED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<ProductStatus, ProductEvent> transitions)
            throws Exception {
        transitions
                // DRAFT -> ACTIVE
                .withExternal()
                    .source(ProductStatus.DRAFT)
                    .target(ProductStatus.ACTIVE)
                    .event(ProductEvent.SUBMIT)
                    .guard(hasRequiredSkusGuard())
                    .and()
                // ACTIVE -> HIDDEN
                .withExternal()
                    .source(ProductStatus.ACTIVE)
                    .target(ProductStatus.HIDDEN)
                    .event(ProductEvent.HIDE)
                    .and()
                // HIDDEN -> ACTIVE
                .withExternal()
                    .source(ProductStatus.HIDDEN)
                    .target(ProductStatus.ACTIVE)
                    .event(ProductEvent.SHOW)
                    .guard(hasAvailableStockGuard());
    }

    @Bean
    public Guard<ProductStatus, ProductEvent> hasRequiredSkusGuard() {
        return context -> {
            Product product = context.getExtendedState().get("product", Product.class);
            return product != null
                    && product.hasValidSkus()
                    ? StateMachineAdvice.EvaluateConditionResult.trueResult()
                    : StateMachineAdvice.EvaluateConditionResult.falseResult();
        };
    }
}
```

### 4.6 `port/` - Interfaces (Ports in Hexagonal)

**Purpose:** Định nghĩa contracts giữa các layer. **Domain defines interface, Infrastructure implements it.**

```
port/
├── inbound/
│   └── ProductService.java        # Mirrors service interface
└── outbound/
    ├── ProductRepository.java
    ├── ProductCacheRepository.java
    ├── CategoryRepository.java    # External/internal service
    ├── EventPublisher.java
    └── OutboxRepository.java
```

**Outbound Port Pattern:**

```java
// port/outbound/ProductRepository.java
public interface ProductRepository {

    Optional<Product> findById(String productId);

    Optional<Product> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Product save(Product product);

    void delete(Product product);

    Page<Product> findByCategoryId(String categoryId, Pageable pageable);
}

// port/outbound/ProductCacheRepository.java
public interface ProductCacheRepository {

    Optional<ProductCacheEntry> findById(String skuId);

    void save(String skuId, ProductCacheEntry entry, Duration ttl);

    void delete(String skuId);

    void deleteByPattern(String pattern);
}

// port/outbound/EventPublisher.java
public interface EventPublisher {

    void publish(DomainEvent event);

    void publishAsync(DomainEvent event);
}
```

### 4.7 `infrastructure/` - Infrastructure Adapters

**Purpose:** Implement các interfaces định nghĩa ở `port/`. Là **Secondary Adapters** trong Hexagonal Architecture.

#### 4.7.1 `persistence/` - JPA/Hibernate Implementation

```
infrastructure/
└── persistence/
    ├── entity/                    # JPA entity models (separate from domain!)
    │   ├── ProductJpaEntity.java
    │   ├── SkuJpaEntity.java
    │   └── ProductJpaEntityPK.java
    ├── repository/               # Spring Data JPA
    │   ├── ProductJpaRepository.java
    │   ├── SkuJpaRepository.java
    │   └── ProductJpaRepositoryImpl.java  # Custom impl for complex queries
    └── mapper/
        └── ProductJpaMapper.java  # Domain ↔ JPA entity mapper
```

**JPA Entity (separate from Domain):**

```java
// infrastructure/persistence/entity/ProductJpaEntity.java
@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductJpaEntity {

    @Id
    @Column(name = "product_id", length = 255)
    private String productId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 255, unique = true)
    private String slug;

    @Column(name = "category_id", length = 255)
    private String categoryId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "is_deleted")
    private boolean deleted;

    @Column(name = "attributes_json", columnDefinition = "jsonb")
    private String attributesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;  // Optimistic locking
}
```

**JPA Repository:**

```java
// infrastructure/persistence/repository/ProductJpaRepository.java
@Repository
public interface ProductJpaRepository
        extends JpaRepository<ProductJpaEntity, String>,
                JpaSpecificationExecutor<ProductJpaEntity>,
                ProductJpaRepositoryCustom {

    Optional<ProductJpaEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.categoryId = :categoryId AND p.deleted = false")
    Page<ProductJpaEntity> findActiveByCategoryId(@Param("categoryId") String categoryId, Pageable pageable);
}
```

**Entity to Domain Mapper:**

```java
// infrastructure/persistence/mapper/ProductJpaMapper.java
@Component
public class ProductJpaMapper {

    private final ObjectMapper objectMapper;

    public Product toDomain(ProductJpaEntity entity) {
        if (entity == null) return null;

        Map<String, Object> attributes = null;
        if (entity.getAttributesJson() != null) {
            try {
                attributes = objectMapper.readValue(entity.getAttributesJson(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse attributes", e);
            }
        }

        return Product.builder()
                .id(ProductId.of(entity.getProductId()))
                .name(entity.getName())
                .slug(Slug.of(entity.getSlug()))
                .categoryId(CategoryId.of(entity.getCategoryId()))
                .description(entity.getDescription())
                .status(ProductStatus.valueOf(entity.getStatus()))
                .deleted(entity.isDeleted())
                .attributes(attributes)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public ProductJpaEntity toEntity(Product product) {
        if (product == null) return null;

        String attributesJson = null;
        if (product.getAttributes() != null) {
            try {
                attributesJson = objectMapper.writeValueAsString(product.getAttributes());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize attributes", e);
            }
        }

        return ProductJpaEntity.builder()
                .productId(product.getId().getValue())
                .name(product.getName())
                .slug(product.getSlug().getValue())
                .categoryId(product.getCategoryId().getValue())
                .description(product.getDescription())
                .status(product.getStatus().name())
                .deleted(product.isDeleted())
                .attributesJson(attributesJson)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
```

#### 4.7.2 `redis/` - Redis Implementation

```
infrastructure/
└── redis/
    ├── RedisConfig.java
    ├── ProductRedisRepository.java     # Implements ProductCacheRepository
    ├── ProductCacheEntry.java          # Cache data model
    ├── RedisKeyBuilder.java            # Key naming convention
    └── LuaScriptLoader.java
```

**Redis Key Builder:**

```java
// infrastructure/redis/RedisKeyBuilder.java
@Component
public class RedisKeyBuilder {

    private static final String KEY_PREFIX = "product";

    public String sku(String skuId) {
        return String.format("%s:sku:%s", KEY_PREFIX, skuId);
    }

    public String markdownActive(String skuId) {
        return String.format("%s:markdown:active:%s", KEY_PREFIX, skuId);
    }

    public String inventoryPending() {
        return String.format("%s:inventory:pending", KEY_PREFIX);
    }

    public String idempotencyKey(String type, String id) {
        return String.format("%s:idemp:%s:%s", KEY_PREFIX, type, id);
    }

    public String distributedLock(String resource) {
        return String.format("%s:lock:%s", KEY_PREFIX, resource);
    }
}
```

**Redis Repository with JSON:**

```java
// infrastructure/redis/ProductRedisRepository.java
@Repository
@RequiredArgsConstructor
public class ProductRedisRepository implements ProductCacheRepository {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder keyBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<ProductCacheEntry> findById(String skuId) {
        String json = redisTemplate.opsForValue().get(keyBuilder.sku(skuId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, ProductCacheEntry.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize product cache: {}", skuId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(String skuId, ProductCacheEntry entry, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(keyBuilder.sku(skuId), json, ttl);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize product cache: {}", skuId, e);
        }
    }
}
```

#### 4.7.3 `kafka/` - Kafka Implementation

```
infrastructure/
└── kafka/
    ├── KafkaConfig.java
    ├── producer/
    │   └── ProductEventProducer.java
    ├── consumer/
    │   ├── MarkdownEventConsumer.java
    │   └── InventoryEventConsumer.java
    └── deserializer/
        └── CloudEventDeserializer.java
```

**Kafka Producer:**

```java
// infrastructure/kafka/producer/ProductEventProducer.java
@Service
@RequiredArgsConstructor
public class ProductEventProducer implements EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.topics.product-events}")
    private String productEventsTopic;

    @Override
    public void publish(DomainEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = event.getAggregateId(); // product_id or sku_id

            kafkaTemplate.send(productEventsTopic, key, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish event: {}", event.getEventId(), ex);
                        } else {
                            log.debug("Event published: {} -> partition {} offset {}",
                                    event.getEventId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }
}
```

**Kafka Consumer with DLQ:**

```java
// infrastructure/kafka/consumer/MarkdownEventConsumer.java
@Service
@Slf4j
public class MarkdownEventConsumer {

    private static final String MARKDOWN_SNAPSHOT_EVENT =
            "com.ecommerce.promotion.product-markdown-snapshot.v1";

    @KafkaListener(
            topics = "${spring.kafka.topics.promotion-markdown-events}",
            groupId = "${spring.kafka.consumer.group-ids.markdown}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String message, @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Received markdown event from partition {} offset {}", partition, offset);

        try {
            CloudEvent cloudEvent = objectMapper.readValue(message, CloudEvent.class);

            if (!MARKDOWN_SNAPSHOT_EVENT.equals(cloudEvent.getEventType())) {
                log.warn("Unknown event type: {}, skipping", cloudEvent.getEventType());
                return;
            }

            processEvent(cloudEvent);

        } catch (Exception e) {
            log.error("Failed to process markdown event", e);
            throw e; // Re-throw to trigger retry/DLQ
        }
    }

    private void processEvent(CloudEvent event) {
        // Payload là full product-scope snapshot. Handler chỉ apply khi
        // source_revision lớn hơn revision đã persist; state là ACTIVE/INACTIVE.
        handleProductMarkdownSnapshot(event);
    }
}
```

### 4.8 `worker/` - Background Workers

**ONE directory per worker type:**

```
worker/
├── consumer/                     # Kafka consumer workers
│   ├── MarkdownWorker.java
│   └── InventorySyncWorker.java
├── scheduler/                   # @Scheduled jobs
│   ├── OutboxRetentionJob.java
│   ├── AbandonedCartMarkerJob.java
│   └── DistributedLockRunner.java
└── processor/                  # Batch processors
    ├── MarkdownPriceCalculatorProcessor.java
    └── InventoryBatchProcessor.java
```

**Scheduled Job Pattern:**

```java
// worker/scheduler/OutboxRetentionJob.java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRetentionJob {

    private final OutboxRepository outboxRepository;
    private final DistributedLockRunner lockRunner;

    @Scheduled(cron = "${jobs.outbox-retention.cron:0 0 3 * * SUN}")
    public void run() {
        lockRunner.runWithLock("outbox-retention", Duration.ofMinutes(30), () -> {
            log.info("Starting outbox retention cleanup");
            int deleted = outboxRepository.deleteOlderThan(Duration.ofDays(30));
            log.info("Outbox retention cleanup completed, deleted {} records", deleted);
        });
    }
}
```

**Distributed Lock Runner:**

```java
// worker/scheduler/DistributedLockRunner.java
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLockRunner {

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyBuilder keyBuilder;

    public void runWithLock(String lockName, Duration ttl, Runnable task) {
        String lockKey = keyBuilder.distributedLock(lockName);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "locked", ttl);

        if (Boolean.TRUE.equals(acquired)) {
            try {
                task.run();
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            log.info("Lock {} already held by another instance, skipping", lockName);
        }
    }
}
```

### 4.9 `common/` - Shared Utilities

**Service-agnostic utilities có thể share giữa các services:**

```
common/
├── constants/
│   └── ServiceConstants.java
├── errors/
│   └── ErrorCode.java
├── keybuilder/
│   └── RedisKeyBuilder.java
├── circuitbreaker/
│   └── CircuitBreakerConfig.java
├── retry/
│   └── RetryConfig.java
├── utils/
│   ├── JsonUtils.java
│   └── DateTimeUtils.java
└── tracing/
    └── TracingUtils.java
```

---

## 5. Comparison: Current vs Proposed

### 5.1 Product Service

| Aspect | Current | Proposed |
|--------|---------|----------|
| Controllers | Mixed in root package | `controller/` (one file per entity) |
| Services | `service/` + `service/impl/` | Keep, add interface in `port/inbound/` |
| Domain | Mixed with JPA entities | Separate `domain/entity/` |
| JPA Entities | Mixed with domain | `infrastructure/persistence/entity/` |
| Workers | Flat `worker/` | `worker/consumer/`, `worker/scheduler/`, `worker/processor/` |
| Redis | `redis/` at root | `infrastructure/redis/` |
| Kafka | `kafka/` at root | `infrastructure/kafka/` |
| DTOs | Mixed | `dto/request/`, `dto/response/` |

### 5.2 Benefits of Proposed Structure

1. **Predictability:** Developer biết file ở đâu
2. **Scalability:** Mỗi team có thể làm việc trên layer riêng
3. **Testability:** Dễ mock dependencies
4. **Maintainability:** Thay đổi local trong layer, không affect toàn bộ
5. **Onboarding:** Cấu trúc nhất quán, chỉ cần học một lần
6. **Hexagonal-ready:** Ports/Adapters tách biệt rõ ràng
7. **Spring-native:** Tận dụng tối đa annotation, không fight against framework

---

## 6. Migration Guide

### Phase 1: Create Common Layout Template

1. Tạo một service mới với cấu trúc chuẩn làm template
2. Extract các common components vào `common/`
3. Document các patterns và conventions

### Phase 2: Migrate Existing Services (Incremental)

**Product Service:**

```
Current:                               Target:
├── src/main/java/.../         →      ├── src/main/java/.../
│   ├── controller/            →      │   ├── controller/
│   ├── service/              →      │   ├── service/ + port/inbound/
│   │   └── impl/             →      │   └── service/impl/
│   ├── repository/           →      │   └── infrastructure/persistence/repository/
│   ├── entity/               →      │   ├── domain/entity/ (domain)
│   │                                │   └── infrastructure/persistence/entity/ (JPA)
│   ├── dto/                  →      │   └── dto/request/ + dto/response/
│   ├── config/               →      │   └── config/
│   ├── kafka/               →      │   └── infrastructure/kafka/
│   ├── worker/               →      │   └── worker/consumer/ + worker/scheduler/
│   └── exception/           →      │   └── domain/exception/
└── src/main/resources/               └── src/main/resources/
```

### Phase 3: Enforce Standards

1. Thêm ArchUnit test để verify layer dependencies
2. Thêm checkstyle/spotless cho naming conventions
3. Code review checklist bao gồm structure verification

---

## 7. Code Examples

### 7.1 Complete Controller Example

```java
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductService productService;
    private final ProductMapper productMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("Creating product: {} [idempotency={}]", request.getName(), idempotencyKey);

        Product product = productMapper.toDomain(request);
        Product created = productService.create(product);

        return productMapper.toResponse(created);
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable String id) {
        return productService.findById(id)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @PatchMapping("/{id}/status")
    public ProductResponse changeStatus(
            @PathVariable String id,
            @RequestBody ChangeStatusRequest request) {

        Product updated = productService.changeStatus(id, request.getCommand());
        return productMapper.toResponse(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(@PathVariable String id) {
        productService.delete(id);
    }
}
```

### 7.2 Complete Service Implementation Example

```java
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductCacheRepository cacheRepository;
    private final DomainEventPublisher eventPublisher;
    private final ProductStateMachineService stateMachineService;

    @Override
    public Product create(Product product) {
        log.info("Creating product: {}", product.getName());

        if (productRepository.existsBySlug(product.getSlug().getValue())) {
            throw new DuplicateSlugException(product.getSlug().getValue());
        }

        product.initialize(); // Set default status, timestamps

        Product saved = productRepository.save(product);

        // Publish event (async-safe)
        eventPublisher.publish(new ProductCreatedEvent(saved));

        // Warm cache
        cacheRepository.save(
                saved.getPrimarySkuId(),
                ProductCacheEntry.from(saved),
                Duration.ofHours(1)
        );

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(String productId) {
        // Read-through cache pattern
        return cacheRepository.findById(productId)
                .map(ProductCacheEntry::toProduct)
                .or(() -> {
                    Product product = productRepository.findById(productId)
                            .orElse(null);
                    if (product != null) {
                        cacheRepository.save(
                                productId,
                                ProductCacheEntry.from(product),
                                Duration.ofHours(1)
                        );
                    }
                    return Optional.ofNullable(product);
                });
    }

    @Override
    public Product submit(String productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        stateMachineService.sendEvent(product, ProductEvent.SUBMIT);
        Product saved = productRepository.save(product);

        eventPublisher.publish(new ProductStatusChangedEvent(saved));

        return saved;
    }
}
```

### 7.3 Complete Domain Entity Example

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    private ProductId id;
    private String name;
    private Slug slug;
    private CategoryId categoryId;
    private String description;
    private ProductStatus status;
    private boolean deleted;
    private boolean adminHidden;
    private LocalDateTime deletedAt;
    private Map<String, Object> attributes;
    private List<Sku> skus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Lifecycle methods
    public void initialize() {
        this.id = ProductId.generate();
        this.status = ProductStatus.DRAFT;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void submit() {
        if (this.status != ProductStatus.DRAFT) {
            throw new InvalidStateTransitionException(
                    "Can only submit DRAFT product");
        }
        if (this.skus == null || this.skus.isEmpty()) {
            throw new DomainException("Product must have at least one SKU");
        }
        boolean hasValidSku = this.skus.stream()
                .anyMatch(sku -> sku.getOriginalPrice() != null
                        && sku.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0);
        if (!hasValidSku) {
            throw new DomainException("At least one SKU must have original_price > 0");
        }
        this.status = ProductStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void hide() {
        if (this.status == ProductStatus.HIDDEN) {
            return; // Idempotent
        }
        this.status = ProductStatus.HIDDEN;
        this.adminHidden = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void show() {
        if (this.status != ProductStatus.HIDDEN) {
            return;
        }
        boolean hasStock = this.skus != null
                && this.skus.stream().anyMatch(Sku::isInStock);
        if (!hasStock) {
            throw new DomainException("Cannot show product with zero stock");
        }
        this.status = ProductStatus.ACTIVE;
        this.adminHidden = false;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDeleted() {
        this.deleted = true;
        this.deletedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Query methods
    public boolean hasValidSkus() {
        return this.skus != null
                && !this.skus.isEmpty()
                && this.skus.stream()
                        .anyMatch(sku -> sku.getOriginalPrice() != null
                                && sku.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0);
    }

    public boolean isActive() {
        return !this.deleted && this.status == ProductStatus.ACTIVE;
    }

    public String getPrimarySkuId() {
        return this.skus != null && !this.skus.isEmpty()
                ? this.skus.get(0).getId().getValue()
                : null;
    }
}
```

### 7.4 Complete Repository Implementation Example

```java
// infrastructure/persistence/repository/ProductJpaRepositoryImpl.java
@Repository
@RequiredArgsConstructor
public class ProductJpaRepositoryImpl
        implements ProductJpaRepositoryCustom {

    private final EntityManager em;

    @Override
    public Page<ProductJpaEntity> findByFilters(
            String categoryId,
            String status,
            Boolean deleted,
            Pageable pageable) {

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ProductJpaEntity> cq = cb.createQuery(ProductJpaEntity.class);
        Root<ProductJpaEntity> root = cq.from(ProductJpaEntity.class);

        List<Predicate> predicates = new ArrayList<>();

        if (categoryId != null) {
            predicates.add(cb.equal(root.get("categoryId"), categoryId));
        }
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        if (deleted != null) {
            predicates.add(cb.equal(root.get("deleted"), deleted));
        }

        cq.where(predicates.toArray(new Predicate[0]));
        cq.orderBy(cb.desc(root.get("updatedAt")));

        TypedQuery<ProductJpaEntity> query = em.createQuery(cq);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());

        // Count query
        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<ProductJpaEntity> countRoot = countCq.from(ProductJpaEntity.class);
        countCq.select(cb.count(countRoot));
        countCq.where(predicates.toArray(new Predicate[0]));
        Long total = em.createQuery(countCq).getSingleResult();

        return new PageImpl<>(query.getResultList(), pageable, total);
    }
}
```

---

## 8. Best Practices Checklist

### 8.1 Project Structure

- [ ] Có `{ServiceName}Application.java` là entry point duy nhất
- [ ] Business logic trong `domain/`, không có trong `controller/` hoặc `infrastructure/`
- [ ] JPA entities tách biệt trong `infrastructure/persistence/entity/`
- [ ] Domain entities trong `domain/entity/`
- [ ] Interfaces định nghĩa trong `port/outbound/` hoặc `service/`
- [ ] Implementations trong `infrastructure/` hoặc `service/impl/`
- [ ] Controllers trong `controller/`, một file per entity

### 8.2 Dependency Rules

- [ ] `domain/` không import `infrastructure/` hoặc `persistence/`
- [ ] `domain/` không import Spring annotations (@Service, @Repository, etc.)
- [ ] `controller/` không import `infrastructure/` trực tiếp (qua service)
- [ ] `worker/` import `domain/` và `infrastructure/`
- [ ] JPA entities có mapper để convert to/from domain

### 8.3 Naming Conventions

- [ ] Package names ngắn gọn, lowercase dot-separated
- [ ] Class names PascalCase
- [ ] Interface names có suffix phù hợp (`Service`, `Repository`, `Publisher`)
- [ ] DTOs có suffix `Request` hoặc `Response`
- [ ] Exception types có suffix `Exception`
- [ ] Enum values UPPER_SNAKE_CASE

### 8.4 Spring Boot Conventions

- [ ] Dùng `@RequiredArgsConstructor` thay vì constructor manual
- [ ] Dùng `@Transactional(readOnly = true)` cho read methods
- [ ] Dùng `@Transactional` (default) cho write methods
- [ ] Dùng `@Validated` trên Controller class
- [ ] Dùng Bean Validation (`@Valid`, `@NotBlank`, `@NotNull`, etc.)
- [ ] Tách config theo component (`KafkaConfig`, `RedisConfig`, etc.)

### 8.5 Configuration

- [ ] Config từ `application.yml`
- [ ] Environment variables override file config
- [ ] Secrets từ environment variables hoặc Vault
- [ ] Flyway migrations trong `resources/db/migration/`

### 8.6 Testing

- [ ] Unit tests trong cùng package với class được test (`*Test.java`)
- [ ] Integration tests trong `test/` với annotation `@SpringBootTest`
- [ ] Mocks cho interfaces trong `test/` dùng Mockito
- [ ] Repository tests với `@DataJpaTest`

### 8.7 Documentation

- [ ] Package-level Javadoc cho public APIs
- [ ] README.md cho mỗi service
- [ ] API documentation (SpringDoc OpenAPI)
- [ ] Health check endpoints (`/actuator/health`)

---

## Appendix A: Quick Reference

### Layer Responsibilities

| Layer | Package | Responsibility | Examples |
|-------|---------|---------------|----------|
| API | `controller/` | HTTP request/response | Parse JSON, call service, format response |
| Application | `service/` | Use cases, orchestration | Transaction boundaries, input validation |
| Domain | `domain/` | Business logic | Entities, value objects, domain events, FSM |
| Port | `port/` | Contracts | Interfaces for data access, external services |
| Infrastructure | `infrastructure/` | External integrations | JPA queries, Redis ops, Kafka produce |

### Import Rules (Visual)

```
controller/ ────────────────┐
                            │
service/ ──────────────────┼──→ domain/ ───→ domain/vo
                            │       ├─→ domain/enums
                            │       └─→ domain/event
port/ ─────────────────────┘
        └─→ domain/ ◄┘

infrastructure/ ───────────┐
        ├─→ port/ ◄┘       │
        └─→ domain/ ◄┘     │
                            │
worker/ ────────────────────┘
        ├─→ domain/ ◄┘
        ├─→ infrastructure/ ◄┘
        └─→ port/ ◄┘
```

### Common Patterns

| Pattern | Where | Purpose |
|---------|-------|---------|
| Repository | `port/outbound/` + `infrastructure/persistence/` | Data access abstraction |
| Factory | `domain/entity/` | Object creation with validation |
| Mapper | `dto/mapper/` | DTO ↔ Entity ↔ Domain conversion |
| Builder | `domain/entity/` | Complex object construction |
| State Machine | `domain/statemachine/` | Lifecycle management |
| Event | `domain/event/` + `infrastructure/kafka/` | Event notification |
| Outbox | `infrastructure/persistence/` + Debezium | Reliable event publishing |
| Circuit Breaker | `common/circuitbreaker/` | Resilience |
| Read-through Cache | `service/` + `infrastructure/redis/` | Cache with fallback |

---

## Appendix B: Tech Stack Reference

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 3.x + JDK 21 |
| Build | Maven |
| Database | PostgreSQL + Spring Data JPA + Hibernate |
| Cache | Redis (Lettuce) |
| Kafka | Spring Kafka |
| gRPC | grpc-spring-boot-starter |
| State Machine | Spring State Machine |
| Resilience | Resilience4j |
| Validation | Bean Validation (Hibernate Validator) |
| API Docs | SpringDoc OpenAPI |
| Migration | Flyway |
| Testing | JUnit 5 + Mockito + TestContainers |
| Layer Testing | ArchUnit |

---

## Appendix C: Migration Checklist Template

```
[ ] 1. Create backup of current structure
[ ] 2. Create new directory structure
[ ] 3. Move entry point to root
[ ] 4. Extract domain/ entities from existing entities
[ ] 5. Extract domain/ enums from existing enums
[ ] 6. Extract domain/ exceptions
[ ] 7. Create port/outbound/ interfaces
[ ] 8. Move/create infrastructure/persistence/ JPA entities + mappers
[ ] 9. Move/create infrastructure/redis/ repositories
[ ] 10. Move/create infrastructure/kafka/ producers/consumers
[ ] 11. Update service/ to implement port interfaces
[ ] 12. Move/update controller/ with new DTOs
[ ] 13. Move/create worker/ components
[ ] 14. Create/verify application.yml configuration
[ ] 15. Update imports
[ ] 16. Run tests
[ ] 17. Run ArchUnit layer tests
[ ] 18. Update README
```

---

*Document maintained by: Platform Team*
*Last updated: June 10, 2026*
