# Design Documentation

**E-Commerce Platform - Microservices Architecture**

This directory contains comprehensive architectural design documentation for the E-Commerce Platform consisting of 17 microservices. All decisions, patterns, and implementation strategies are documented here.

## 📚 Documentation Structure

### Core Documents

1. **[00-architecture-overview.md](00-architecture-overview.md)** ⭐ START HERE
   - Executive summary
   - Service classification (4 tiers)
   - Technology stack
   - Infrastructure components
   - Key decisions & anti-patterns

2. **[01-pattern-decision-framework.md](01-pattern-decision-framework.md)**
   - When to use Event Sourcing
   - When to use CQRS
   - When to use Saga Pattern
   - Decision trees & matrices
   - Anti-patterns to avoid
   - Cost-benefit analysis

3. **[02-service-classification.md](02-service-classification.md)**
   - Detailed breakdown of all 17 services
   - Pattern justification per service
   - Database schemas
   - Event types
   - CQRS read models

4. **[07-decision-log.md](07-decision-log.md)**
   - ADR (Architecture Decision Records)
   - Critical decisions (Product downgrade, Shipping upgrade, Remove RabbitMQ)
   - Rationale & consequences
   - Lessons learned

5. **[08-implementation-roadmap.md](08-implementation-roadmap.md)**
   - 8-month phased development plan
   - 4 phases with dependencies
   - Parallel development strategy
   - Risk mitigation
   - Success metrics

---

## 🎯 Quick Reference

### Service Tiers

```
🟢 Tier 1: Simple CRUD (9 services)
   - Auth, User, Cart, Notification, Chat, Analytics, Search
   - Pattern: PostgreSQL/Redis + Kafka events
   - Time: 2 weeks per service

🟡 Tier 2: CQRS Only (4 services)
   - Product, Seller, Review, AI Agent
   - Pattern: Write model + Read model (materialized views)
   - Time: 3 weeks per service

🟠 Tier 3: Event Sourcing (4 services)
   - Promotion, Inventory, Shipping, Refund
   - Pattern: Event Store + CQRS read models
   - Time: 4 weeks per service

🔴 Tier 4: Full Stack (2 services)
   - Order, Payment
   - Pattern: Event Sourcing + CQRS + Temporal Sagas
   - Time: 6 weeks per service
```

### Technology Stack

| Component | Technology | Use Cases |
|-----------|------------|-----------|
| **Languages** | Go, Java, Python | Go: High-perf CRUD<br>Java: Event Sourcing, business logic<br>Python: Analytics, AI |
| **Databases** | PostgreSQL, Redis, ScyllaDB, TimescaleDB | PostgreSQL: Main DB + Event Store<br>Redis: Cache, sessions<br>ScyllaDB: Chat<br>TimescaleDB: Analytics |
| **Event Bus** | Kafka | Event sourcing, integration, streaming |
| **Orchestration** | Temporal | Sagas, long-running workflows |
| **Search** | Elasticsearch | Product catalog full-text search |
| **Monitoring** | Prometheus, Grafana | Metrics, dashboards, alerts |

**Removed**: ❌ RabbitMQ (redundant with Temporal + Kafka)

---

## 🔑 Key Decisions

### Decision 1: Pattern Diversity (Not Uniform)
**Status**: ✅ Accepted

**Rationale**: Avoid over-engineering. Only 35% of services (6/17) need Event Sourcing.

**Impact**: 
- ✅ 40% reduction in development time
- ✅ Simpler operations
- ⚠️ Inconsistent patterns (requires documentation)

---

### Decision 2: Remove RabbitMQ
**Status**: ✅ Accepted

**Rationale**: 95% overlap with Temporal (workflows) + Kafka (events).

**Impact**:
- ✅ -1 infrastructure component
- ✅ -30% operational overhead
- ⚠️ Temporal workflows ~30ms vs RabbitMQ ~5ms (acceptable)

---

### Decision 3: Product Service Downgrade (ES → CQRS)
**Status**: ✅ Accepted

**Rationale**: 
- Product data ít thay đổi (1-2 times/month)
- Cart/Order snapshot prices → No disputes
- Audit table sufficient for compliance

**Impact**:
- ✅ -40% complexity
- ✅ -1 week development time
- ✅ Still have audit trail (price_history table)

---

### Decision 4: Shipping Service Upgrade (CRUD → ES)
**Status**: ✅ Accepted

**Rationale**:
- Long-running process (5-7 days)
- Async webhooks (duplicates, out-of-order, delays)
- Need idempotency + state machine
- Timeout handling critical

**Impact**:
- ✅ Idempotency guaranteed
- ✅ Timeout handling (Temporal)
- ✅ Complete audit trail
- ⚠️ +80% complexity (justified)

---

## 📊 Statistics

### Development Effort

| Tier | Services | Days/Service | Total Days | % Effort |
|------|----------|--------------|------------|----------|
| Tier 1 | 9 | 10 | 90 | 38% |
| Tier 2 | 4 | 15 | 60 | 25% |
| Tier 3 | 4 | 20 | 80 | 34% |
| Tier 4 | 2 | 30 | 60 | 25% |
| **Total** | **19** | - | **290 days** | **100%** |

*Note: 19 services vì Order Service has reference implementation*

### Pattern Distribution

```
Event Sourcing: 6 services (35%)
CQRS: 10 services (59%)
Saga: 2 services (12%)
```

**Key Insight**: Chỉ 12% services cần Sagas - phần lớn là simple operations.

---

## 🚀 Getting Started

### For Architects

1. Read [00-architecture-overview.md](00-architecture-overview.md)
2. Review [07-decision-log.md](07-decision-log.md)
3. Study [01-pattern-decision-framework.md](01-pattern-decision-framework.md)

### For Developers

1. Find your service in [02-service-classification.md](02-service-classification.md)
2. Check tier & pattern requirements
3. Review database schema in `/database/*.md`
4. Follow [08-implementation-roadmap.md](08-implementation-roadmap.md)

### For New Team Members

1. Read **Quick Reference** above
2. Understand the 4 tiers
3. Review [Decision Log ADRs](07-decision-log.md)
4. Ask questions in team channel

---

## 📖 Related Documentation

### Database Schemas
Located in [`/database/`](../database/)
- `01-identity-services.md` - Auth, User, Seller
- `02-catalog-services.md` - Product, Search
- `03-shopping-services.md` - Cart, Order, Promotion
- `04-fulfillment-services.md` - Inventory, Payment, Shipping, Refund
- `05-engagement-services.md` - Review, Notification, Chat
- `06-intelligence-services.md` - Analytics, AI Agent

### Implementation Plans
Located in [`/plan/`](../plan/)
- Detailed service-specific implementation plans
- API specifications
- Integration requirements

### Source Code
- Go services: [`/go/services/`](../go/services/)
- Java services: [`/java/`](../java/)
- Python services: [`/python/`](../python/)

---

## 🎓 Learning Resources

### Event Sourcing
- [Martin Fowler - Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
- [Microservices.io - Event Sourcing Pattern](https://microservices.io/patterns/data/event-sourcing.html)

### CQRS
- [Martin Fowler - CQRS](https://martinfowler.com/bliki/CQRS.html)
- [Microsoft CQRS Journey](https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj554200(v=pandp.10))

### Saga Pattern
- [Microservices.io - Saga Pattern](https://microservices.io/patterns/data/saga.html)
- [Temporal Documentation](https://docs.temporal.io/)

### Architecture Patterns
- [Chris Richardson - Microservices Patterns](https://microservices.io/patterns/)
- [Sam Newman - Building Microservices](https://www.oreilly.com/library/view/building-microservices-2nd/9781492034018/)

---

## ✅ Design Principles

### 1. Right-Sized Patterns
**Principle**: Apply the RIGHT pattern for the RIGHT problem.

❌ Don't: Event Sourcing everywhere "because best practice"  
✅ Do: CRUD for simple services, ES for financial transactions

### 2. Start Simple, Evolve
**Principle**: Begin with simplest pattern, upgrade when needed.

❌ Don't: Full stack (ES+CQRS+Saga) from day 1  
✅ Do: CRUD → CQRS → ES → Saga (when requirements emerge)

### 3. Explicit Trade-offs
**Principle**: Document costs & benefits of each decision.

❌ Don't: Choose pattern based on "cool factor"  
✅ Do: Analyze development time, complexity, operational cost

### 4. Learn from Feedback
**Principle**: Adapt based on real-world experience.

❌ Don't: Stick to initial plan regardless of feedback  
✅ Do: Downgrade Product Service, Upgrade Shipping Service based on analysis

### 5. Measure Impact
**Principle**: Track metrics before/after pattern changes.

✅ Track: Development time, query latency, storage costs  
✅ Compare: CRUD vs ES overhead (actual numbers)

---

## 🔄 Document Updates

| Date | Document | Change | Author |
|------|----------|--------|--------|
| 2026-02-13 | Initial creation | All design docs created | Architecture Team |
| 2026-02-13 | ADR-003 | Product Service downgrade (ES → CQRS) | Product Team |
| 2026-02-13 | ADR-004 | Shipping Service upgrade (CRUD → ES) | Fulfillment Team |

---

## 📞 Contact

**Architecture Team**:
- Lead Architect: [Contact info]
- Go Services Lead: [Contact info]
- Java Services Lead: [Contact info]
- Infrastructure Lead: [Contact info]

**Feedback**: Architecture decisions are living documents. If you find issues or have suggestions, please:
1. Create issue in project tracker
2. Tag `architecture` label
3. Provide rationale & alternative proposal

---

## 📝 Contributing to Documentation

### When to Update

- ✅ New service added/removed
- ✅ Pattern changed (e.g., CRUD → CQRS)
- ✅ Major technology decision
- ✅ Lessons learned from production

### How to Update

1. Create branch: `docs/architecture-update-{topic}`
2. Update relevant .md file(s)
3. Add entry to Decision Log if ADR
4. Update this README if structural change
5. Submit PR with `architecture` label

### Documentation Standards

- Use Markdown format
- Include code examples (Java/Go/Python)
- Add diagrams (Mermaid or ASCII art)
- Reference related documents
- Update modification date

---

## 🎯 Success Criteria

This design is successful if:

- [x] All 17 services classified with clear pattern rationale
- [x] Development time estimated (290 days total)
- [x] Trade-offs explicitly documented
- [x] Anti-patterns identified and avoided
- [ ] Implementation following design (TBD after Phase 1)
- [ ] Performance metrics matching predictions (TBD after launch)

---

**Last Updated**: February 13, 2026  
**Status**: ✅ Design Phase Complete, Ready for Implementation  
**Next Phase**: Phase 1 (Foundation) - Weeks 1-8
