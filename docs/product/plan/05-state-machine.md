# Module 05: State Machine

> **Status:** ✅ Module 5 of 15 - **IMPLEMENTED**
> **Prerequisites:** Module 01 (foundation), Module 02 (domain model), Module 03 (repository layer)
> **Dependents:** Module 06 (business services), Module 12 (inventory sync)

---

## 1. Overview

This module implements the Product State Machine using Spring State Machine. It manages the lifecycle transitions: `DRAFT → ACTIVE → HIDDEN`, with guards for business rule validation and actions for side effects (event publishing, cache updates).

---

## 2. State and Event Enums

### 2.1 File: `domain/statemachine/ProductState.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/ProductState.java`

- [x] Enum implementing `StateMachineState<String, String>`: `DRAFT`, `ACTIVE`, `HIDDEN`
- [x] Method `String getStateId()`: returns the state name
- [x] Static method `ProductState fromString(String value)`: parse from DB string

### 2.2 File: `domain/statemachine/ProductEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/ProductEvent.java`

- [x] Enum implementing `StateMachineEvent<String, String>`: `SUBMIT`, `HIDE`, `SHOW`, `AUTO_HIDE_OUT_OF_STOCK`, `AUTO_REACTIVATE_IN_STOCK`
- [x] Method `String getEventId()`: returns the event name
- [x] Method `boolean isAdminEvent()`: returns true for SUBMIT, HIDE, SHOW
- [x] Method `boolean isSystemEvent()`: returns true for AUTO_* events

---

## 3. State Machine Configuration

### 3.1 File: `domain/statemachine/ProductStateMachineConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/ProductStateMachineConfig.java`

- [x] Create `@Configuration` extending `EnumStateMachineConfigurerAdapter<ProductState, ProductEvent>`
- [x] Configure state machine factory with `StateMachineConfigurationConfigurer`:
  - `autoStartup(false)` (manual start per entity)
  - `stateMachineExecutor` with `StateMachineExecuteRegistry`
- [x] Configure states with `StateMachineStateConfigurer`:
  - Initial state: `DRAFT`
  - All states: `DRAFT`, `ACTIVE`, `HIDDEN`
  - No end states (soft delete handled separately)
- [x] Configure transitions with `StateMachineTransitionConfigurer`:
  - `DRAFT → ACTIVE`: event `SUBMIT`, guard `hasRequiredSkusGuard`, action `submitAction`
  - `ACTIVE → HIDDEN`: event `HIDE`, guard `canTransitionFromActiveGuard`, action `hideAction`
  - `ACTIVE → HIDDEN`: event `AUTO_HIDE_OUT_OF_STOCK`, guard `canTransitionFromActiveGuard`, action `autoHideAction`
  - `HIDDEN → ACTIVE`: event `SHOW`, guard `canTransitionFromHiddenGuard`, `hasAvailableStockGuard`, action `showAction`
  - `HIDDEN → ACTIVE`: event `AUTO_REACTIVATE_IN_STOCK`, guard `canTransitionFromHiddenGuard`, `isNotAdminHiddenGuard`, action `autoReactivateAction`
- [x] Register all guards as `@Component` (injected via constructor)
- [x] Register all actions as `@Component` (injected via constructor)
- [x] Configure state machine listeners for logging and debugging

### 3.2 File: `domain/statemachine/guards/HasRequiredSkusGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/HasRequiredSkusGuard.java`

- [x] Create `@Component` implementing `Guard<ProductState, ProductEvent>`
- [x] Guard logic: `Product has at least 1 SKU with `originalPrice > 0` and `deleted = false`
- [x] Inject `SkuRepository` for data access
- [x] Return `false` with error message if invalid
- [x] Handle missing product in extended state

### 3.3 File: `domain/statemachine/guards/HasAvailableStockGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/HasAvailableStockGuard.java`

- [x] Create `@Component` implementing `Guard<ProductState, ProductEvent>`
- [x] Guard logic: `SUM(displayQuantity) > 0` across all non-deleted SKUs
- [x] Inject `SkuRepository` to query sum of display quantities
- [x] Return `false` with error message if no stock

### 3.4 File: `domain/statemachine/guards/IsNotAdminHiddenGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/IsNotAdminHiddenGuard.java`

- [x] Create `@Component` implementing `Guard<ProductState, ProductEvent>`
- [x] Guard logic: `product.adminHidden == false`
- [x] Read from extended state: `product` object passed via context

### 3.5 File: `domain/statemachine/guards/CanTransitionFromActiveGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/CanTransitionFromActiveGuard.java`

- [x] Create `@Component` implementing `Guard<ProductState, ProductEvent>`
- [x] Guard logic: product must be `ACTIVE` before HIDE or AUTO_HIDE
- [x] Read current state from context

### 3.6 File: `domain/statemachine/guards/CanTransitionFromHiddenGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/CanTransitionFromHiddenGuard.java`

- [x] Create `@Component` implementing `Guard<ProductState, ProductEvent>`
- [x] Guard logic: product must be `HIDDEN` before SHOW or AUTO_REACTIVATE

---

## 4. State Machine Actions

### 4.1 File: `domain/statemachine/actions/SubmitAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/SubmitAction.java`

- [x] Create `@Component` implementing `Action<ProductState, ProductEvent>` for `SUBMIT` action
- [x] Action logic: set `product.status = ACTIVE`, `updatedAt = now`
- [x] Save extended state: `product`
- [x] Log transition: `DRAFT → ACTIVE`

### 4.2 File: `domain/statemachine/actions/HideAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/HideAction.java`

- [x] Create `@Component` implementing `Action<ProductState, ProductEvent>` for `HIDE` action (admin-initiated)
- [x] Action logic: set `product.status = HIDDEN`, `product.adminHidden = true`, `updatedAt = now`
- [x] Save extended state: `product`

### 4.3 File: `domain/statemachine/actions/AutoHideAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/AutoHideAction.java`

- [x] Create `@Component` implementing `Action<ProductState, ProductEvent>` for `AUTO_HIDE_OUT_OF_STOCK` action
- [x] Action logic: set `product.status = HIDDEN` (do NOT set `adminHidden = true`), `updatedAt = now`
- [x] Save extended state: `product`
- [x] Mark `changedByType = AUTO_HIDE_OUT_OF_STOCK` in extended state

### 4.4 File: `domain/statemachine/actions/ShowAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/ShowAction.java`

- [x] Create `@Component` implementing `Action<ProductState, ProductEvent>` for `SHOW` action (admin-initiated)
- [x] Action logic: set `product.status = ACTIVE`, `product.adminHidden = false`, `updatedAt = now`
- [x] Save extended state: `product`

### 4.5 File: `domain/statemachine/actions/AutoReactivateAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/AutoReactivateAction.java`

- [x] Create `@Component` implementing `Action<ProductState, ProductEvent>` for `AUTO_REACTIVATE_IN_STOCK` action
- [x] Action logic: set `product.status = ACTIVE`, `updatedAt = now`
- [x] Do NOT modify `adminHidden` (preserve admin's manual hide)
- [x] Save extended state: `product`

---

## 5. State Machine Service

### 5.1 File: `service/ProductStateMachineService.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/ProductStateMachineService.java`

- [x] Create `@Service` class
- [x] Inject `StateMachineFactory<ProductState, ProductEvent>`
- [x] Method `StateMachine<ProductState, ProductEvent> build(ProductId productId)`: creates new state machine instance, initializes with current product state
- [x] Method `TransitionResult sendEvent(Product product, ProductEvent event)`: build SM, load product into extended state, send event, return result
- [x] Method `ProductState getCurrentState(String productId)`: query from database
- [x] Method `boolean canTransition(Product product, ProductEvent event)`: check if transition is valid
- [x] Use `StateMachine.startReactively()` before sending events
- [x] Use `StateMachine.stopReactively()` after completion
- [x] Handle exceptions and wrap in `InvalidStateTransitionException`
- [x] Use `productId` as state machine ID (for factory lookup)
- [x] Return `TransitionResult` inner class with accepted/rejected states

### 5.2 File: `service/ProductFsmFacade.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/ProductFsmFacade.java`

- [x] Create `@Service` class as high-level FSM facade
- [x] Method `Product submit(Product product)`: calls `stateMachineService.sendEvent(product, SUBMIT)`
- [x] Method `Product hide(Product product)`: calls `stateMachineService.sendEvent(product, HIDE)`
- [x] Method `Product show(Product product)`: calls `stateMachineService.sendEvent(product, SHOW)`
- [x] Method `Product autoHideOutOfStock(Product product)`: calls `stateMachineService.sendEvent(product, AUTO_HIDE_OUT_OF_STOCK)`
- [x] Method `Product autoReactivateInStock(Product product)`: calls `stateMachineService.sendEvent(product, AUTO_REACTIVATE_IN_STOCK)`
- [x] Each method persists the product to DB after FSM transition
- [x] Publish `ProductStatusChangedEvent` to outbox after state change
- [x] Method `boolean canTransition(Product, ProductEvent)`: delegate to stateMachineService
- [x] Method `ProductState getCurrentState(ProductId)`: delegate to stateMachineService

---

## 6. FSM Integration with Inventory Sync

### 6.1 File: `domain/statemachine/FsmTriggerCalculator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/FsmTriggerCalculator.java`

- [x] Create `@DomainService` (pure domain logic)
- [x] Method `Optional<ProductEvent> calculateFsmTrigger(Product product, List<Sku> skus)`: determines if FSM should be triggered based on inventory changes
- [x] Logic: if all SKUs have `displayQuantity == 0` and product is `ACTIVE` and `adminHidden == false` → return `AUTO_HIDE_OUT_OF_STOCK`
- [x] Logic: if any SKU has `displayQuantity > 0` and product is `HIDDEN` and `adminHidden == false` → return `AUTO_REACTIVATE_IN_STOCK`
- [x] Return `Optional.empty()` if no trigger needed

### 6.2 File: `domain/statemachine/FsmTriggerContext.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/FsmTriggerContext.java`

- [x] Create class: `fsmTrigger (ProductEvent)`, `reason (String)`, `changedBy (String)`
- [x] Factory methods: `forAutoHide()`, `forAutoReactivate()`

---

## 7. Acceptance Criteria

- [ ] State machine transitions correctly from DRAFT to ACTIVE when SUBMIT event is sent with valid SKU
- [ ] State machine rejects SUBMIT when no SKU has `originalPrice > 0`
- [ ] State machine correctly handles AUTO_HIDE_OUT_OF_STOCK without setting `adminHidden = true`
- [ ] State machine correctly handles AUTO_REACTIVATE_IN_STOCK only when `adminHidden = false`
- [ ] Admin HIDE sets `adminHidden = true`, Admin SHOW resets it to `false`
- [ ] State machine is idempotent: sending HIDE on already HIDDEN product does not fail
- [ ] `FsmTriggerCalculator` correctly determines FSM triggers based on inventory state
- [ ] State machine persists state across restarts (using Spring State Machine persistence)

---

## 8. Files Created

| # | File | Status |
|---|------|--------|
| 1 | `domain/statemachine/ProductState.java` | ✅ |
| 2 | `domain/statemachine/ProductEvent.java` | ✅ |
| 3 | `domain/statemachine/ProductStateMachineConfig.java` | ✅ |
| 4 | `domain/statemachine/guards/HasRequiredSkusGuard.java` | ✅ |
| 5 | `domain/statemachine/guards/HasAvailableStockGuard.java` | ✅ |
| 6 | `domain/statemachine/guards/IsNotAdminHiddenGuard.java` | ✅ |
| 7 | `domain/statemachine/guards/CanTransitionFromActiveGuard.java` | ✅ |
| 8 | `domain/statemachine/guards/CanTransitionFromHiddenGuard.java` | ✅ |
| 9 | `domain/statemachine/actions/SubmitAction.java` | ✅ |
| 10 | `domain/statemachine/actions/HideAction.java` | ✅ |
| 11 | `domain/statemachine/actions/AutoHideAction.java` | ✅ |
| 12 | `domain/statemachine/actions/ShowAction.java` | ✅ |
| 13 | `domain/statemachine/actions/AutoReactivateAction.java` | ✅ |
| 14 | `service/ProductStateMachineService.java` | ✅ |
| 15 | `service/ProductFsmFacade.java` | ✅ |
| 16 | `domain/statemachine/FsmTriggerCalculator.java` | ✅ |
| 17 | `domain/statemachine/FsmTriggerContext.java` | ✅ |

**Total: 17 files created**
