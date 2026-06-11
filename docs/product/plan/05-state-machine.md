# Module 05: State Machine

> **Status:** Module 5 of 15
> **Prerequisites:** Module 01 (foundation), Module 02 (domain model), Module 03 (repository layer)
> **Dependents:** Module 06 (business services), Module 12 (inventory sync)

---

## 1. Overview

This module implements the Product State Machine using Spring State Machine. It manages the lifecycle transitions: `DRAFT → ACTIVE → HIDDEN`, with guards for business rule validation and actions for side effects (event publishing, cache updates).

---

## 2. State and Event Enums

### 2.1 File: `domain/statemachine/ProductState.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/ProductState.java`

- [ ] Enum implementing `StateMachineState<String, String>`: `DRAFT`, `ACTIVE`, `HIDDEN`
- [ ] Method `String getStateId()`: returns the state name
- [ ] Static method `ProductState fromString(String value)`: parse from DB string

### 2.2 File: `domain/statemachine/ProductEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/ProductEvent.java`

- [ ] Enum implementing `StateMachineEvent<String, String>`: `SUBMIT`, `HIDE`, `SHOW`, `AUTO_HIDE_OUT_OF_STOCK`, `AUTO_REACTIVATE_IN_STOCK`
- [ ] Method `String getEventId()`: returns the event name
- [ ] Method `boolean isAdminEvent()`: returns true for SUBMIT, HIDE, SHOW
- [ ] Method `boolean isSystemEvent()`: returns true for AUTO_* events

---

## 3. State Machine Configuration

### 3.1 File: `domain/statemachine/ProductStateMachineConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/ProductStateMachineConfig.java`

- [ ] Create `@Configuration` extending `EnumStateMachineConfigurerAdapter<ProductState, ProductEvent>`
- [ ] Configure state machine factory with `StateMachineConfigurationConfigurer`:
  - `autoStartup(false)` (manual start per entity)
  - `stateMachineExecutor` with `StateMachineExecuteRegistry`
- [ ] Configure states with `StateMachineStateConfigurer`:
  - Initial state: `DRAFT`
  - All states: `DRAFT`, `ACTIVE`, `HIDDEN`
  - No end states (soft delete handled separately)
- [ ] Configure transitions with `StateMachineTransitionConfigurer`:
  - `DRAFT → ACTIVE`: event `SUBMIT`, guard `hasRequiredSkusGuard`, action `submitAction`
  - `ACTIVE → HIDDEN`: event `HIDE`, guard `none`, action `hideAction`
  - `ACTIVE → HIDDEN`: event `AUTO_HIDE_OUT_OF_STOCK`, guard `none`, action `autoHideAction`
  - `HIDDEN → ACTIVE`: event `SHOW`, guard `hasAvailableStockGuard`, action `showAction`
  - `HIDDEN → ACTIVE`: event `AUTO_REACTIVATE_IN_STOCK`, guard `isNotAdminHiddenGuard`, action `autoReactivateAction`
  - `HIDDEN → HIDDEN`: event `HIDE` (idempotent guard)
- [ ] Register all guards as `@Bean` methods
- [ ] Register all actions as `@Bean` methods
- [ ] Configure state machine listeners for logging and debugging

### 3.2 File: `domain/statemachine/guards/HasRequiredSkusGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/HasRequiredSkusGuard.java`

- [ ] Create `@Component` implementing `StateMachineGuard<ProductState, ProductEvent>`
- [ ] Guard logic: `Product has at least 1 SKU with `originalPrice > 0` and `deleted = false`
- [ ] Inject `SkuRepository` for data access
- [ ] Return `ConditionResult`: `true` if valid, `false` with error message if invalid
- [ ] Handle `ProductNotFoundException` (should not happen in FSM context)

### 3.3 File: `domain/statemachine/guards/HasAvailableStockGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/HasAvailableStockGuard.java`

- [ ] Create `@Component` implementing `StateMachineGuard<ProductState, ProductEvent>`
- [ ] Guard logic: `SUM(displayQuantity) > 0` across all non-deleted SKUs
- [ ] Inject `SkuRepository` to query sum of display quantities
- [ ] Return `ConditionResult`

### 3.4 File: `domain/statemachine/guards/IsNotAdminHiddenGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/IsNotAdminHiddenGuard.java`

- [ ] Create `@Component` implementing `StateMachineGuard<ProductState, ProductEvent>`
- [ ] Guard logic: `product.adminHidden == false`
- [ ] Read from extended state: `product` object passed via context

### 3.5 File: `domain/statemachine/guards/CanTransitionFromActiveGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/CanTransitionFromActiveGuard.java`

- [ ] Create `@Component` implementing `StateMachineGuard<ProductState, ProductEvent>`
- [ ] Guard logic: product must be `ACTIVE` before HIDE or AUTO_HIDE
- [ ] Read current state from context

### 3.6 File: `domain/statemachine/guards/CanTransitionFromHiddenGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/guards/CanTransitionFromHiddenGuard.java`

- [ ] Create `@Component` implementing `StateMachineGuard<ProductState, ProductEvent>`
- [ ] Guard logic: product must be `HIDDEN` before SHOW or AUTO_REACTIVATE

---

## 4. State Machine Actions

### 4.1 File: `domain/statemachine/actions/StateMachineAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/StateMachineAction.java`

- [ ] Create `@Component` for `SUBMIT` action
- [ ] Action logic: set `product.status = ACTIVE`, `updatedAt = now`
- [ ] Save extended state: `product`
- [ ] Log transition: `DRAFT → ACTIVE`

### 4.2 File: `domain/statemachine/actions/HideAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/HideAction.java`

- [ ] Create `@Component` for `HIDE` action (admin-initiated)
- [ ] Action logic: set `product.status = HIDDEN`, `product.adminHidden = true`, `updatedAt = now`
- [ ] Save extended state: `product`

### 4.3 File: `domain/statemachine/actions/AutoHideAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/AutoHideAction.java`

- [ ] Create `@Component` for `AUTO_HIDE_OUT_OF_STOCK` action
- [ ] Action logic: set `product.status = HIDDEN` (do NOT set `adminHidden = true`), `updatedAt = now`
- [ ] Save extended state: `product`
- [ ] Mark `changedByType = SYSTEM` in extended state

### 4.4 File: `domain/statemachine/actions/ShowAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/ShowAction.java`

- [ ] Create `@Component` for `SHOW` action (admin-initiated)
- [ ] Action logic: set `product.status = ACTIVE`, `product.adminHidden = false`, `updatedAt = now`
- [ ] Save extended state: `product`

### 4.5 File: `domain/statemachine/actions/AutoReactivateAction.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/actions/AutoReactivateAction.java`

- [ ] Create `@Component` for `AUTO_REACTIVATE_IN_STOCK` action
- [ ] Action logic: set `product.status = ACTIVE`, `updatedAt = now`
- [ ] Do NOT modify `adminHidden` (preserve admin's manual hide)
- [ ] Save extended state: `product`

---

## 5. State Machine Service

### 5.1 File: `service/ProductStateMachineService.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/ProductStateMachineService.java`

- [ ] Create `@Service` class
- [ ] Inject `StateMachineFactory<ProductState, ProductEvent>`
- [ ] Method `StateMachine<ProductState, ProductEvent> build(ProductId productId)`: creates new state machine instance
- [ ] Method `Transition<ProductState, ProductEvent> sendEvent(Product product, ProductEvent event)`: acquire lock, build SM, set state, load product into extended state, send event, persist state, return transition
- [ ] Method `ProductState getCurrentState(String productId)`: query from database
- [ ] Method `boolean canTransition(Product product, ProductEvent event)`: check if transition is valid
- [ ] Use `StateMachine.start()` before sending events
- [ ] Use `StateMachine.stop()` after completion
- [ ] Handle `InvalidStateTransitionException` from guards
- [ ] Configure state machine persistence: use `StateMachineRuntimeService` to persist state
- [ ] Configure state machine ID strategy: `productId` as state machine ID

### 5.2 File: `service/ProductFsmFacade.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/ProductFsmFacade.java`

- [ ] Create `@Service` class as high-level FSM facade
- [ ] Method `Product submit(Product product)`: calls `stateMachineService.sendEvent(product, SUBMIT)`
- [ ] Method `Product hide(Product product)`: calls `stateMachineService.sendEvent(product, HIDE)`
- [ ] Method `Product show(Product product)`: calls `stateMachineService.sendEvent(product, SHOW)`
- [ ] Method `Product autoHideOutOfStock(Product product)`: calls `stateMachineService.sendEvent(product, AUTO_HIDE_OUT_OF_STOCK)`
- [ ] Method `Product autoReactivateInStock(Product product)`: calls `stateMachineService.sendEvent(product, AUTO_REACTIVATE_IN_STOCK)`
- [ ] Each method persists the product to DB after FSM transition
- [ ] Publish domain event after state change

---

## 6. FSM Integration with Inventory Sync

### 6.1 File: `domain/statemachine/FsmTriggerCalculator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/FsmTriggerCalculator.java`

- [ ] Create `@DomainService` (pure domain logic)
- [ ] Method `Optional<ProductEvent> calculateFsmTrigger(Product product, List<Sku> skus)`: determines if FSM should be triggered based on inventory changes
- [ ] Logic: if all SKUs have `displayQuantity == 0` and product is `ACTIVE` and `adminHidden == false` → return `AUTO_HIDE_OUT_OF_STOCK`
- [ ] Logic: if any SKU has `displayQuantity > 0` and product is `HIDDEN` and `adminHidden == false` → return `AUTO_REACTIVATE_IN_STOCK`
- [ ] Return `Optional.empty()` if no trigger needed

### 6.2 File: `domain/statemachine/FsmTriggerContext.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/statemachine/FsmTriggerContext.java`

- [ ] Create record/class: `fsmTrigger (ProductEvent)`, `reason (String)`, `changedBy (String)`
- [ ] Factory methods: `forAutoHide()`, `forAutoReactivate()`

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
