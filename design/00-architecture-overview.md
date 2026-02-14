# Architecture Overview - E-Commerce Microservices Platform

**Last Updated**: February 13, 2026  
**Status**: Design Phase - Pattern Selection Complete

## Executive Summary

Hệ thống E-Commerce Platform gồm **17 microservices** được thiết kế theo kiến trúc microservices với các patterns khác nhau dựa trên business requirements thực tế. Sau khi phân tích kỹ, chúng tôi quyết định **KHÔNG áp dụng Event Sourcing/CQRS/Saga một cách đồng nhất** cho tất cả services để tránh over-engineering.

## Architecture Principles

### 1. Pattern Diversity Strategy
```
✅ Apply the RIGHT pattern for the RIGHT problem
❌ Avoid "one-size-fits-all" architecture
```

**Key Decision**: Chỉ 35% services (6/17) thực sự cần Event Sourcing

### 2. Technology Stack Alignment

| Language | Use Cases | Services Count |
|----------|-----------|----------------|
| **Go** | High-performance, low-latency, simple CRUD | 9 services |
| **Java/Spring Boot** | Complex business logic, Event Sourcing, financial transactions | 6 services |
| **Python** | Analytics, AI/ML, data processing | 2 services |

### 3. Infrastructure Components

```
┌─────────────────────────────────────────────────────────────┐
│                    INFRASTRUCTURE LAYER                      │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ PostgreSQL   │  │    Kafka     │  │   Temporal   │       │
│  │              │  │              │  │              │       │
│  │ - Main DB    │  │ - Events     │  │ - Workflows  │       │
│  │ - Event Store│  │ - Integration│  │ - Sagas      │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │    Redis     │  │ Elasticsearch│  │  ScyllaDB    │       │
│  │              │  │              │  │              │       │
│  │ - Cache      │  │ - Search     │  │ - Chat       │       │
│  │ - Sessions   │  │ - Analytics  │  │ - Time-series│       │
│  │ - Rate Limit │  │              │  │              │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                               │
│  ❌ RabbitMQ - REMOVED (Redundant với Temporal + Kafka)      │
└─────────────────────────────────────────────────────────────┘
```

**Key Decision**: Loại bỏ RabbitMQ vì Temporal + Kafka đủ cover use cases

## Service Classification Summary

### 🟢 Tier 1: Simple CRUD (9 Services)
Traditional RDBMS patterns với event publishing cho integration

- Auth Service ✅ (Go - Implemented)
- User Service (Go)
- Cart Service (Go)
- Notification Service (Go)
- Chat Service (Go)
- Analytics Service (Python)
- Search Service (Go)

**Characteristics**:
- PostgreSQL/Redis cho storage
- Simple CRUD operations
- Event publishing (không phải event sourcing)
- Không có complex state machines
- Không có distributed transactions

### 🟡 Tier 2: CQRS Only (4 Services)
Separate read models cho performance, không cần full event sourcing

- Seller Service (Java)
- Product Service (Java) ⬅️ **DOWNGRADED từ ES**
- Review Service (Java)
- AI Agent Service (Python)

**Characteristics**:
- Write model normalized
- Read model denormalized (materialized views)
- Async synchronization via events
- Không cần replay full history

### 🟠 Tier 3: Event Sourcing (4 Services)
Complete audit trail required, no cross-service sagas

- Promotion Service (Java)
- Inventory Service (Java)
- Refund Service (Java)
- Shipping Service (Go/Java) ⬅️ **UPGRADED từ CRUD**

**Characteristics**:
- Event store cho all state changes
- Rebuild state từ events
- Compliance/audit requirements
- CQRS cho read optimization
- Không có multi-service transactions

### 🔴 Tier 4: Full Stack - ES + CQRS + Saga (2 Services)
Complex workflows với compensation logic

- Order Service ✅ (Java - Reference Implementation)
- Payment Service (Java)

**Characteristics**:
- Event sourcing cho immutability
- CQRS cho multiple read models
- **Temporal sagas** cho distributed transactions
- Compensation logic (rollback on failure)
- Strong consistency guarantees

## Critical Decisions

### Decision 1: Product Service Downgrade (ES → CQRS)
**Reason**: 
- Product data ít thay đổi
- Price history đủ với audit table
- Cart/Order đã snapshot giá
- **Impact**: -40% complexity, tiết kiệm 1 tuần development

### Decision 2: Shipping Service Upgrade (CRUD → ES)
**Reason**:
- Long-running process (5-7 ngày)
- Async webhooks từ carriers (GHN, Viettel Post)
- Cần idempotency cho duplicate webhooks
- Out-of-order event handling
- **Impact**: +80% complexity, +2 tuần development NHƯNG critical cho reliability

### Decision 3: Remove RabbitMQ
**Reason**:
- Temporal handles workflows, activities, retry
- Kafka handles events, streaming
- RabbitMQ redundant (95% overlap)
- **Impact**: -1 infrastructure component, giảm operational complexity

## Data Flow Patterns

### Pattern 1: Event Sourcing Flow (Tier 3 & 4)
```
Command → Domain Logic → Event Generated
                              ↓
                    ┌─────────┴─────────┐
                    ↓                   ↓
            Event Store (PG)     Kafka Topic
            (Audit Trail)        (Integration)
                    ↓                   ↓
            Read Model Update   Event Consumers
            (CQRS Projection)   (Other Services)
```

### Pattern 2: Saga Orchestration (Tier 4 Only)
```
Order Service
    ↓ Start Temporal Workflow
Saga Orchestrator (Temporal)
    ↓ Activity 1: Reserve Inventory
Inventory Service (returns success)
    ↓ Activity 2: Process Payment
Payment Service (returns success)
    ↓ Activity 3: Create Shipment
Shipping Service (returns success)
    ↓ All activities complete
Order Status → PAID → SHIPPED
```

**Compensation Flow** (nếu Payment fails):
```
Payment Service FAILED
    ↓ Temporal triggers compensation
Inventory Service.releaseReservation()
    ↓ Rollback complete
Order Status → FAILED
```

### Pattern 3: CQRS Sync Pattern (Tier 2)
```
Write Command → Update Write Model (PostgreSQL)
                        ↓
                Publish Event (Kafka)
                        ↓
                Event Listener
                        ↓
                Update Read Model
                ├─ Materialized View (PostgreSQL)
                ├─ Cache (Redis)
                └─ Search Index (Elasticsearch)
```

## Implementation Roadmap

### Phase 1: Foundation (Month 1-2) 
**Focus**: Core infrastructure + reference implementations

- ✅ Auth Service (Go) - COMPLETE
- 🔨 User Service (Go) 
- ✅ Order Service (Java) - Reference exists
- 🔨 Cart Service (Go)

### Phase 2: Commerce Core (Month 3-4)
**Focus**: End-to-end purchase flow

- Product Service (Java - CQRS)
- Inventory Service (Java - ES)
- Payment Service (Java - Full Stack)
- Shipping Service (Go/Java - ES)

### Phase 3: Seller & Engagement (Month 5-6)
**Focus**: Marketplace features

- Seller Service (Java - CQRS)
- Promotion Service (Java - ES)
- Review Service (Java - CQRS)
- Refund Service (Java - ES + Saga)

### Phase 4: Intelligence & Scale (Month 7-8)
**Focus**: Analytics + AI

- Search Service (Go)
- Notification Service (Go)
- Chat Service (Go)
- Analytics Service (Python)
- AI Agent Service (Python - CQRS)

## Anti-Patterns Avoided

### ❌ Anti-Pattern 1: Event Sourcing Everywhere
```
❌ BAD: Apply ES to all 17 services
✅ GOOD: Only 6 services need ES (compliance, audit, financial)
```

### ❌ Anti-Pattern 2: CRUD for Financial Transactions
```
❌ BAD: Payment Service with direct DB updates
✅ GOOD: Event Sourcing + Double-Entry Bookkeeping
```

### ❌ Anti-Pattern 3: Simple CRUD for Long-Running Processes
```
❌ BAD: Shipping Service with status field updates
✅ GOOD: Event Sourcing + Temporal workflows
```

### ❌ Anti-Pattern 4: Over-Normalized Read Models
```
❌ BAD: Complex JOINs on every product query
✅ GOOD: Denormalized materialized views (CQRS)
```

## Success Metrics

### Development Velocity
- Tier 1 services: **2 weeks** per service
- Tier 2 services: **3 weeks** per service
- Tier 3 services: **4 weeks** per service
- Tier 4 services: **6 weeks** per service

### Technical Debt Reduction
- Right-sized patterns → 40% less unnecessary code
- Clear boundaries → 60% less cross-service coupling
- Event-driven integration → 50% less tight dependencies

### Operational Excellence
- Fewer infrastructure components (no RabbitMQ) → 30% less operational overhead
- Temporal visibility → 80% faster debugging for sagas
- Event Store audit trails → 100% compliance coverage

## References

- [Pattern Decision Framework](01-pattern-decision-framework.md)
- [Service Classification Details](02-service-classification.md)
- [Decision Log](07-decision-log.md)
- Database Schemas: `/database/*.md`
- Implementation Plans: `/plan/*.md`
