# Module 13: HTTP API Controllers

> **Depends on:** M10 (Routing), M11 (Temporal), M12 (Admin Services)
> **Depended by:** (none — leaves the service)

---

## 1. Goal

Triển khai 5 HTTP REST endpoints của Inventory Service:

| Method | Endpoint | Auth |
|:---|:---|:---|
| POST | `/v1/inventory/routing-options` | JWT (User) |
| POST | `/v1/inventory/restock` | JWT (Admin) |
| POST | `/v1/inventory/ship-to-customer` | JWT (Admin) |
| POST | `/v1/inventory/returns/refund-expectations` | Internal OIDC m2m |
| POST | `/v1/inventory/admin/unlock-sku` | JWT (Admin) |

Plus:
- Security filter chain (JWT validation, scope/role guard)
- Error response mapping (DomainException → HTTP)
- Request/response DTOs + Bean Validation
- Idempotency interceptor

> Tham chiếu: `inventory_endpoints.md` §1-5.

---

## 2. Package Layout

```
com.platform.inventoryservice.controller/
├── RoutingController.java
├── RestockController.java
├── ShipToCustomerController.java
├── RefundExpectationsController.java
├── UnlockSkuController.java
└── GlobalExceptionHandler.java

com.platform.inventoryservice.dto/
├── request/
│   ├── RoutingOptionsRequest.java
│   ├── RestockRequest.java
│   ├── ShipToCustomerRequest.java
│   ├── RefundExpectationsRequest.java
│   └── UnlockSkuRequest.java
└── response/
    ├── RoutingOptionsResponse.java
    ├── RestockResponse.java
    ├── ShipToCustomerResponse.java
    ├── RefundExpectationsResponse.java
    ├── UnlockSkuResponse.java
    └── ErrorResponse.java

com.platform.inventoryservice.security/
├── Scope.java                  # Enum: inventory:read, inventory:routing, inventory:admin
├── ScopeAuthority.java         # SimpleGrantedAuthority wrapper
├── JwtScopeConverter.java      # Extract scope từ JWT
├── JwtRoleConverter.java       # Extract role từ JWT
└── InternalServiceTokenFilter.java   # OIDC m2m validation cho refund-expectations
```

---

## 3. Security Configuration

### 3.1 JwtScopeConverter

```java
@Component
public class JwtScopeConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        // Extract scope (space-separated string)
        Object scopeClaim = jwt.getClaim("scope");
        if (scopeClaim instanceof String scopeStr) {
            for (String scope : scopeStr.split(" ")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        } else if (scopeClaim instanceof List<?> scopeList) {
            for (Object scope : scopeList) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        }

        // Extract role
        Object roleClaim = jwt.getClaim("role");
        if (roleClaim instanceof String roleStr) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleStr));
        }

        return authorities;
    }
}
```

### 3.2 SecurityConfig (M01 đã scaffold — M13 nâng cấp)

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtScopeConverter jwtScopeConverter;
    private final InternalServiceTokenValidator internalValidator;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/inventory/routing-options")
                    .hasAnyAuthority("SCOPE_inventory:routing", "SCOPE_inventory:read")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/restock")
                    .hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/ship-to-customer")
                    .hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/returns/refund-expectations")
                    .hasAuthority("SCOPE_inventory:internal")
                .requestMatchers(HttpMethod.POST, "/v1/inventory/admin/unlock-sku")
                    .hasAuthority("ROLE_ADMIN")
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtScopeConverter)))
            .addFilterBefore(internalValidator, OAuth2AuthenticationFilter.class);
        return http.build();
    }
}
```

### 3.3 InternalServiceTokenValidator

```java
@Component
@RequiredArgsConstructor
public class InternalServiceTokenValidator extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Service";
    private static final String ORDER_SERVICE = "order-service";

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) {
        if (!req.getRequestURI().contains("/refund-expectations")) {
            chain.doFilter(req, resp);
            return;
        }

        String serviceId = req.getHeader(HEADER);
        if (!ORDER_SERVICE.equals(serviceId)) {
            writeError(resp, 401, "MISSING_INTERNAL_SERVICE",
                       "Missing or invalid X-Internal-Service header");
            return;
        }

        // Verify JWT contains scope inventory:internal (already handled by SecurityFilterChain above)
        // Additional check: caller must be authenticated
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            writeError(resp, 401, "UNAUTHENTICATED", "Missing JWT");
            return;
        }

        chain.doFilter(req, resp);
    }

    private void writeError(HttpServletResponse resp, int status, String code, String message)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.getWriter().write(objectMapper.writeValueAsString(
            Map.of("error", code, "message", message)));
    }
}
```

---

## 4. Routing Controller

### 4.1 Request DTO

```java
public record RoutingOptionsRequest(
    @NotEmpty(message = "items required")
    @Size(max = 100, message = "items max 100")
    @Valid List<@NotNull RoutingItemDto> items,

    @NotNull(message = "customer_location required")
    @Valid CustomerLocationDto customerLocation
) {
    public record RoutingItemDto(
        @NotBlank @Size(max = 64) String skuId,
        @Min(1) @Max(10000) int quantity
    ) {}

    public record CustomerLocationDto(
        @NotBlank @Size(max = 20) String cityCode,
        @Size(max = 20) String districtCode    // optional Phase 14
    ) {}
}
```

### 4.2 Response DTO

```java
public record RoutingOptionsResponse(
    UUID routePlanId,
    Instant expiresAt,
    List<RoutePlanItemDto> items,
    List<WarehouseOptionDto> warehouseOptions
) {
    public record RoutePlanItemDto(
        String skuId,
        String productId,
        int quantity,
        long warehouseId
    ) {}

    public record WarehouseOptionDto(
        long warehouseId,
        long availableQuantity,
        int estimatedDeliveryDays
    ) {}

    public static RoutingOptionsResponse from(RoutePlan plan) {
        return new RoutingOptionsResponse(
            plan.routePlanId(),
            plan.expiresAt(),
            plan.items().stream()
                .map(i -> new RoutePlanItemDto(
                    i.skuId(), i.productId(),
                    (int) i.quantity(), i.warehouseId()))
                .toList(),
            plan.warehouseOptions().stream()
                .map(w -> new WarehouseOptionDto(
                    w.warehouseId(), w.availableQuantity(), w.estimatedDeliveryDays()))
                .toList()
        );
    }
}
```

### 4.3 Controller

```java
@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class RoutingController {

    private final RoutingService routingService;
    private final InventoryMetrics metrics;

    @PostMapping("/routing-options")
    public ResponseEntity<RoutingOptionsResponse> route(
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody @Valid RoutingOptionsRequest request
    ) {
        // Extract customer_location (already in body); JWT có thể có thêm fields nhưng body authoritative
        CustomerLocation location = new CustomerLocation(
            request.customerLocation().cityCode(),
            request.customerLocation().districtCode()
        );

        List<RoutingItem> items = request.items().stream()
            .map(i -> new RoutingItem(i.skuId(), i.quantity()))
            .toList();

        RoutePlan plan = routingService.route(location, items);

        metrics.recordRoutingRequest(items.size());

        return ResponseEntity.ok(RoutingOptionsResponse.from(plan));
    }
}
```

---

## 5. Restock Controller

### 5.1 Request DTO

```java
public record RestockRequest(
    @Min(1) long warehouseId,
    @NotBlank @Size(max = 64) String skuId,
    @NotBlank @Size(max = 64) String productId,
    @Min(1) @Max(1_000_000) int quantity,
    ReferenceType referenceType,    // null = default ADMIN_RESTOCK

    // Required if referenceType == ORDER_RETURNED_RESTOCK
    Long orderId,
    Long orderItemId,
    String refundRequestId
) {}
```

### 5.2 Response DTO

```java
public record RestockResponse(
    boolean success,
    long warehouseId,
    String skuId,
    String productId,
    ReferenceType referenceType,
    int quantityAdded,
    PhysicalSnapshot physical,
    RefundSnapshot refund,            // null for ADMIN_RESTOCK
    UUID eventId,
    Instant timestamp
) {
    public record PhysicalSnapshot(int actualQuantity, int reservedQuantity, int availableQuantity) {}
    public record RefundSnapshot(
        long orderId, long orderItemId, String refundRequestId,
        Instant refundReturnedAt, boolean allItemsReceived
    ) {}

    public static RestockResponse from(RestockResult r) {
        return new RestockResponse(
            true,
            r.warehouseId(), r.skuId(), r.productId(),
            r.referenceType(), r.quantityAdded(),
            new PhysicalSnapshot(
                r.physical().actualQuantity(),
                r.physical().reservedQuantity(),
                r.physical().availableQuantity()),
            r.refund() == null ? null : new RefundSnapshot(
                r.refund().orderId(), r.refund().orderItemId(),
                r.refund().refundRequestId(), r.refund().refundReturnedAt(),
                r.refund().allItemsReceived()),
            r.eventId(), r.timestamp()
        );
    }
}
```

### 5.3 Controller

```java
@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class RestockController {

    private final RestockService restockService;

    @PostMapping("/restock")
    public ResponseEntity<RestockResponse> restock(
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationIdHeader,
        @RequestBody @Valid RestockRequest request
    ) {
        ReferenceType refType = request.referenceType() == null
            ? ReferenceType.ADMIN_RESTOCK : request.referenceType();

        String correlationId = correlationIdHeader != null && !correlationIdHeader.isBlank()
            ? correlationIdHeader
            : CorrelationIdContext.get();

        RestockCommand cmd = new RestockCommand(
            request.warehouseId(),
            request.skuId(),
            request.productId(),
            request.quantity(),
            refType,
            request.orderId(),
            request.orderItemId(),
            request.refundRequestId(),
            correlationId
        );

        RestockResult result = restockService.restock(cmd);
        return ResponseEntity.ok(RestockResponse.from(result));
    }
}
```

---

## 6. Ship-to-Customer Controller

```java
@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
public class ShipToCustomerController {

    private final ShipToCustomerService shipService;

    @PostMapping("/ship-to-customer")
    public ResponseEntity<ShipToCustomerResponse> ship(
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestBody @Valid ShipToCustomerRequest request
    ) {
        ShipToCustomerResult result = shipService.ship(new ShipToCustomerCommand(
            request.orderId(),
            request.warehouseId(),
            request.shippedAt(),
            request.courierNote(),
            correlationId != null ? correlationId : CorrelationIdContext.get()
        ));

        return ResponseEntity.ok(ShipToCustomerResponse.from(result));
    }
}
```

---

## 7. Refund Expectations Controller

```java
@RestController
@RequestMapping("/v1/inventory/returns")
@RequiredArgsConstructor
public class RefundExpectationsController {

    private final RefundExpectationsService refundService;

    @PostMapping("/refund-expectations")
    public ResponseEntity<RefundExpectationsResponse> registerExpectations(
        @RequestHeader(value = "X-Internal-Service", required = false) String internalService,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestBody @Valid RefundExpectationsRequest request
    ) {
        if (!"order-service".equals(internalService)) {
            throw new AccessDeniedException("Missing X-Internal-Service header");
        }

        RefundExpectationsResult result = refundService.registerExpectations(
            new RefundExpectationsCommand(
                request.orderId(),
                request.refundRequestId(),
                request.orderItemIds(),
                correlationId != null ? correlationId : CorrelationIdContext.get()
            ));

        return ResponseEntity.ok(new RefundExpectationsResponse(
            result.orderId(),
            result.refundRequestId(),
            result.markedCount()
        ));
    }
}
```

---

## 8. Unlock SKU Controller

```java
@RestController
@RequestMapping("/v1/inventory/admin")
@RequiredArgsConstructor
public class UnlockSkuController {

    private final UnlockSkuService unlockService;

    @PostMapping("/unlock-sku")
    public ResponseEntity<UnlockSkuResponse> unlock(
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestBody @Valid UnlockSkuRequest request
    ) {
        UnlockSkuResult result = unlockService.unlock(new UnlockSkuCommand(
            request.skuId(),
            request.warehouseId(),
            request.reason(),
            correlationId != null ? correlationId : CorrelationIdContext.get()
        ));

        return ResponseEntity.ok(new UnlockSkuResponse(
            result.skuId(), result.warehouseId(),
            result.unlocked(), result.unlockedAt()
        ));
    }
}
```

---

## 9. Global Exception Handler

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex) {
        ErrorCode code = ex.errorCode();
        log.warn("Domain exception: code={} message={}", code, ex.getMessage());

        ErrorResponse body = new ErrorResponse(
            code.name(),
            ex.getMessage(),
            null,
            Instant.now()
        );

        // Special handling for RefundScopeMismatchException
        if (ex instanceof RefundScopeMismatchException rsm) {
            body = new ErrorResponse(
                code.name(),
                rsm.getMessage(),
                Map.of(
                    "existing_order_item_ids", rsm.existing(),
                    "requested_order_item_ids", rsm.requested()
                ),
                Instant.now()
            );
        }

        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(
            "INVALID_REQUEST",
            "Validation failed: " + details,
            null, Instant.now()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(new ErrorResponse(
            "ACCESS_DENIED", ex.getMessage(), null, Instant.now()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(500).body(new ErrorResponse(
            "INTERNAL_ERROR", "Internal server error", null, Instant.now()
        ));
    }
}
```

```java
public record ErrorResponse(
    String error,
    String message,
    Map<String, Object> details,    // optional context
    Instant timestamp
) {}
```

---

## 10. Error Mapping Table

| Exception | HTTP Status | Code |
|:---|:---:|:---|
| `MethodArgumentNotValidException` | 400 | `INVALID_REQUEST` |
| `IllegalArgumentException` (validation) | 400 | `INVALID_REQUEST` |
| `InsufficientStockException` | 409 | `INSUFFICIENT_STOCK` |
| `OutOfStockException` | 409 | `OUT_OF_STOCK` |
| `InsufficientReservedException` | 409 | `INSUFFICIENT_RESERVED` |
| `ReleaseUnderflowException` | 409 | `RELEASE_UNDERFLOW` |
| `SkuSuspendedException` | 409 | `SKU_SUSPENDED` |
| `RefundScopeMismatchException` | 409 | `REFUND_SCOPE_MISMATCH` |
| `RefundNotExpectedException` | 409 | `REFUND_NOT_EXPECTED` |
| `WarehouseNotFoundException` | 404 | `WAREHOUSE_NOT_FOUND` |
| `SkuNotFoundException` | 404 | `SKU_NOT_FOUND` |
| `ReservationNotFoundException` | 404 | `RESERVATION_NOT_FOUND` |
| `SkuNotSuspendedException` | 404 | `SKU_NOT_SUSPENDED` |
| `InvariantDriftException` | 500 | `INVARIANT_DRIFT` |
| `InventoryUnavailableException` | 503 | `INVENTORY_UNAVAILABLE` |
| Any unhandled | 500 | `INTERNAL_ERROR` |

---

## 11. Idempotency Interceptor

```java
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final IdempotencyKeyBuilder idempKeyBuilder;
    private final RedisTemplate<String, String> redis;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if (!"POST".equals(req.getMethod())) return true;
        String idempotencyKey = req.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;   // No key → no idempotency at HTTP layer
        }
        MDC.put("idempotencyKey", idempotencyKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
                                Object handler, Exception ex) {
        MDC.remove("idempotencyKey");
    }
}
```

> Idempotency thực sự vẫn nằm ở Redis Lua (hot path) và DB UNIQUE constraint (cold path). Interceptor chỉ propagate header vào MDC cho log/metric.

---

## 12. Request Validation Rules

| Field | Constraint |
|:---|:---|
| `routing-options.items` | min 1, max 100 |
| `routing-options.items[].skuId` | not blank, max 64 chars |
| `routing-options.items[].quantity` | 1-10000 |
| `routing-options.customerLocation.cityCode` | not blank, max 20 |
| `restock.warehouseId` | >= 1 |
| `restock.skuId` | not blank, max 64 |
| `restock.productId` | not blank, max 64 |
| `restock.quantity` | 1-1,000,000 |
| `ship-to-customer.orderId` | >= 1 |
| `ship-to-customer.warehouseId` | >= 1 |
| `ship-to-customer.shippedAt` | ISO 8601, required |
| `refund-expectations.orderId` | >= 1 |
| `refund-expectations.refundRequestId` | not blank, max 64 |
| `refund-expectations.orderItemIds` | min 1 |
| `unlock-sku.skuId` | not blank, max 64 |
| `unlock-sku.reason` | not blank, max 512 |
| `unlock-sku.correlationId` | not blank, max 128 |

---

## 13. CORS

> Tạm thời không enable CORS (service-to-service only). Nếu sau này frontend trực tiếp gọi, thêm `CorsConfigurationSource`.

---

## 14. Rate Limiting

> Tích hợp Resilience4j RateLimiter cho admin endpoints:

```yaml
resilience4j:
  ratelimiter:
    instances:
      restock:
        limit-refresh-period: 60s
        limit-for-period: 100
      ship-to-customer:
        limit-refresh-period: 60s
        limit-for-period: 200
```

```java
@RateLimiter(name = "restock")
@PostMapping("/restock")
public ResponseEntity<RestockResponse> restock(...) { ... }
```

---

## 15. Metrics

| Metric | Type | Labels |
|:---|:---|:---|
| `inventory_http_requests_total` | Counter | `endpoint`, `method`, `status` |
| `inventory_http_request_duration_seconds` | Timer | `endpoint`, `method` |
| `inventory_http_validation_errors_total` | Counter | `endpoint` |
| `inventory_http_auth_errors_total` | Counter | `endpoint` |
| `inventory_http_business_errors_total` | Counter | `endpoint`, `error_code` |

> Auto-instrumented bởi Spring Boot Actuator + Micrometer.

---

## 16. Testing

| Test | Mục đích |
|:---|:---|
| Routing options happy path | 200, route plan returned |
| Routing options insufficient stock | 409 |
| Routing options invalid SKU | 404 |
| Restock ADMIN_RESTOCK | 200, ledger + outbox |
| Restock ORDER_RETURNED_RESTOCK partial | 200, no RETURN_RECEIVED_ALL |
| Restock missing field | 400 |
| Ship to customer | 200, outbox |
| Ship to customer no reservation | 404 |
| Refund expectations idempotent | 200 |
| Refund expectations conflict | 409 |
| Unlock SKU | 200 |
| Unlock SKU not suspended | 404 |
| Auth missing | 401 |
| Auth wrong role | 403 |

---

## 17. Verification

| Check | Cách verify |
|:---|:---|
| Endpoint registered | `curl /actuator/mappings \| grep routing-options` |
| Validation | Missing field → 400 INVALID_REQUEST |
| Auth | No JWT → 401 |
| Scope/role | Wrong role → 403 |
| Error mapping | Trigger exception → correct HTTP status + error code |
| Idempotency MDC | Log có `idempotencyKey` field khi header có |

---

## 18. Files Created

```
src/main/java/com/platform/inventoryservice/
├── controller/
│   ├── RoutingController.java
│   ├── RestockController.java
│   ├── ShipToCustomerController.java
│   ├── RefundExpectationsController.java
│   ├── UnlockSkuController.java
│   └── GlobalExceptionHandler.java
├── dto/
│   ├── request/  (5 files)
│   └── response/ (6 files including ErrorResponse)
└── security/
    ├── JwtScopeConverter.java
    ├── InternalServiceTokenValidator.java
    └── Scope.java
```