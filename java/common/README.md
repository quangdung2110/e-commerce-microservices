# Common Module - Shared Library for Event Sourcing

Module này chứa các class và interface dùng chung cho tất cả các microservices trong hệ thống Event Sourcing.

## Cấu trúc

```
com.example.common/
├── annotation/
│   └── DomainEvent.java              # Annotation để đánh dấu Domain Events
├── config/
│   └── KafkaConfigAndProperties.java # Kafka configuration và properties binding
├── event/
│   └── BaseEvent.java                # Base class cho tất cả events
├── publisher/
│   ├── EventPublisher.java           # Interface cho event publishing
│   └── KafkaEventPublisher.java      # Base implementation với Kafka
└── repository/
    └── EventStore.java               # Interface cho Event Store
```

## Sử dụng trong các service

### 1. Thêm dependency vào pom.xml

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>common</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 2. Import các class cần thiết

```java
import com.example.common.annotation.DomainEvent;
import com.example.common.event.BaseEvent;
import com.example.common.publisher.EventPublisher;
import com.example.common.publisher.KafkaEventPublisher;
import com.example.common.repository.EventStore;
```

### 3. Tạo Domain Events

```java
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@DomainEvent(name = "OrderCreated")
public class OrderCreatedEvent extends BaseEvent {
    private String orderId;
    private String productId;
    // ... các fields khác
}
```

### 4. Implement Event Publisher

```java
@Component
public class OrderEventPublisher extends KafkaEventPublisher<BaseEvent> {
    
    public OrderEventPublisher(
        KafkaTemplate<String, BaseEvent> kafkaTemplate,
        @Value("${order.topicName}") String topic
    ) {
        super(kafkaTemplate, topic);
    }
}
```

### 5. Implement Event Store

```java
@Component
public class JpaEventStore<T extends BaseEvent> implements EventStore<T> {
    // Implementation cho JPA
}
```

## Tính năng

### BaseEvent
- Tự động generate `eventId`, `occurredOn`, `version`, `eventType`
- Serialize được để lưu vào database hoặc gửi qua message broker
- Support cho Event Sourcing pattern

### DomainEvent Annotation
- Đánh dấu các Domain Events
- Hỗ trợ metadata (name)
- Có thể dùng cho reflection và auto-configuration

### EventPublisher
- Interface chung cho việc publish events
- Hỗ trợ nhiều message brokers (Kafka, RabbitMQ, etc.)
- KafkaEventPublisher là base implementation cho Kafka

### EventStore
- Interface chung cho Event Store
- Hỗ trợ query events theo aggregateId, aggregateType, version
- Có thể implement với JPA, MongoDB, EventStoreDB, etc.

### KafkaConfigAndProperties
- Shared Kafka configuration (ObjectMapper, ProducerFactory, KafkaTemplate)
- Properties binding cho multiple topics configuration
- Mỗi service có thể cấu hình nhiều topics với partitions và replication khác nhau
- Xem chi tiết tại [KAFKA_MULTIPLE_TOPICS_GUIDE.md](../KAFKA_MULTIPLE_TOPICS_GUIDE.md)

## Kafka Configuration - Multiple Topics

### 1. Component Scan (QUAN TRỌNG)

Service của bạn **phải scan** package `com.example.common`:

```java
@SpringBootApplication(scanBasePackages = {
    "com.example.order_service",
    "com.example.common"  // ← Bắt buộc
})
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

### 2. Tạo KafkaConfig trong service

```java
@Configuration
public class KafkaConfig {
    private final KafkaConfigAndProperties kafkaConfigAndProperties;
    
    public KafkaConfig(KafkaConfigAndProperties kafkaConfigAndProperties) {
        this.kafkaConfigAndProperties = kafkaConfigAndProperties;
    }
    
    @Bean
    public List<NewTopic> createTopics() {
        return kafkaConfigAndProperties.getTopics().stream()
            .map(config -> new NewTopic(
                config.getName(),
                config.getPartitions(),
                config.getReplicationFactor()
            ))
            .toList();
    }
}
```

### 3. Cấu hình topics trong application.properties

```properties
# Multiple topics với cấu hình khác nhau
kafka.topic.topics[0].name=order-events
kafka.topic.topics[0].partitions=5
kafka.topic.topics[0].replicationFactor=2

kafka.topic.topics[1].name=order-notifications
kafka.topic.topics[1].partitions=3
kafka.topic.topics[1].replicationFactor=1
```

**Hoặc dùng YAML (dễ đọc hơn):**

```yaml
kafka:
  topic:
    topics:
      - name: order-events
        partitions: 5
        replicationFactor: 2
      - name: order-notifications
        partitions: 3
        replicationFactor: 1
```

## Ví dụ sử dụng trong các services khác

### Shipping Service

```java
// 1. Thêm dependency vào pom.xml của shipping-service
<dependency>
    <groupId>com.example</groupId>
    <artifactId>common</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

// 2. Tạo ShippingEvent
@DomainEvent(name = "ShippingCreated")
public class ShippingCreatedEvent extends BaseEvent {
    private String shippingId;
    private String orderId;
    // ...
}

// 3. Tạo ShippingEventPublisher
@Component
public class ShippingEventPublisher extends KafkaEventPublisher<BaseEvent> {
    public ShippingEventPublisher(
        KafkaTemplate<String, BaseEvent> kafkaTemplate,
        @Value("${shipping.topicName}") String topic
    ) {
        super(kafkaTemplate, topic);
    }
}
```

## Build

```bash
cd common
./mvnw clean install
```

## Lưu ý

- Module này là **library**, không phải Spring Boot application
- Không có main class
- Được sử dụng làm dependency cho các services khác
- Khi thay đổi, cần rebuild và các services phụ thuộc cần update dependency
