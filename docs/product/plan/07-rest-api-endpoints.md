# Module 07: REST API Endpoints

> **Status:** Module 7 of 15
> **Prerequisites:** Module 06 (business services)
> **Dependents:** None (this is a leaf module for code generation)

---

## 1. Overview

This module implements the Admin REST API layer: request DTOs with validation, REST controllers, and the global exception handler. All endpoints are prefixed with `/api/v1/` and secured with JWT authentication.

---

## 2. Request DTOs

### 2.1 File: `dto/request/CreateProductRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateProductRequest.java`

- [ ] Create class with `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- [ ] Fields: `name (@NotBlank, @Size(max=255))`, `description (@Size(max=5000))`, `categoryId (@NotBlank)`, `attributes (Map<String, Object>)`, `skus (@Valid, @NotNull, @NotEmpty)`, `media (List<CreateMediaRequest>)`
- [ ] Add JSR-380 Bean Validation annotations

### 2.2 File: `dto/request/UpdateProductRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/UpdateProductRequest.java`

- [ ] Fields: `name (@Size(max=255))`, `description (@Size(max=5000))`, `categoryId`, `attributes (Map<String, Object>)` — all optional for partial update
- [ ] Add `jakarta.annotation.Nullable` annotations

### 2.3 File: `dto/request/ChangeStatusRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/ChangeStatusRequest.java`

- [ ] Fields: `command (@NotBlank)` — values: SUBMIT, HIDE, SHOW
- [ ] Add validation: `@Pattern(regexp = "SUBMIT|HIDE|SHOW")`

### 2.4 File: `dto/request/CreateSkuRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateSkuRequest.java`

- [ ] Fields: `skuCode (@NotBlank, @Size(max=255))`, `originalPrice (@NotNull, @DecimalMin("0.01"))`, `variantAttributes (Map<String, String>)`, `media (List<CreateMediaRequest>)`

### 2.5 File: `dto/request/UpdateSkuRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/UpdateSkuRequest.java`

- [ ] Fields: all optional (partial update): `originalPrice (@DecimalMin("0.01"))`, `variantAttributes (Map<String, String>)`, `media (List<CreateMediaRequest>)`, `addMedia (List<CreateMediaRequest>)`, `removeMediaIds (List<String>)`

### 2.6 File: `dto/request/CreateCategoryRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateCategoryRequest.java`

- [ ] Fields: `name (@NotBlank, @Size(max=255))`, `parentId (String)`, `isLeaf (Boolean)`

### 2.7 File: `dto/request/UpdateCategoryRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/UpdateCategoryRequest.java`

- [ ] Fields: `name (@Size(max=255))`, `parentId`, `isLeaf`

### 2.8 File: `dto/request/CreateMediaRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateMediaRequest.java`

- [ ] Fields: `skuId (String nullable)`, `mediaType (@NotBlank @Pattern("IMAGE|VIDEO"))`, `url (@NotBlank)`, `thumbnailUrl (String)`, `sortOrder (Integer)`

### 2.9 File: `dto/request/ProductSearchRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/ProductSearchRequest.java`

- [ ] Fields: `categoryId`, `status`, `search`, `page (@Min(1) default 1)`, `size (@Min(1) @Max(100) default 20)`, `sort (String default "updatedAt,desc")`

---

## 3. Response DTOs

### 3.1 File: `dto/response/ProductResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/ProductResponse.java`

- [ ] Fields: `productId`, `name`, `description`, `categoryId`, `categoryName`, `status`, `attributes (Map<String, Object>)`, `skus (List<SkuResponse>)`, `media (List<MediaResponse>)`, `createdAt`, `updatedAt`

### 3.2 File: `dto/response/SkuResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/SkuResponse.java`

- [ ] Fields: `skuId`, `skuCode`, `originalPrice`, `promotionalPrice`, `status`, `displayQuantity`, `variantAttributes (Map<String, String>)`, `media (List<MediaResponse>)`, `createdAt`, `updatedAt`

### 3.3 File: `dto/response/MediaResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/MediaResponse.java`

- [ ] Fields: `mediaId`, `skuId`, `mediaType`, `url`, `thumbnailUrl`, `sortOrder`

### 3.4 File: `dto/response/CategoryResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/CategoryResponse.java`

- [ ] Fields: `categoryId`, `parentId`, `name`, `slug`, `level`, `isLeaf`, `children (List<CategoryResponse>)`, `createdAt`, `updatedAt`

### 3.5 File: `dto/response/CategoryTreeResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/CategoryTreeResponse.java`

- [ ] Fields: `roots (List<CategoryResponse>)` — flat tree structure

### 3.6 File: `dto/response/PagedResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/PagedResponse.java`

- [ ] Generic class `PagedResponse<T>` with fields: `items (List<T>)`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`

### 3.7 File: `dto/response/ErrorResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/ErrorResponse.java`

- [ ] Fields: `code (String)`, `message (String)`, `errors (Map<String, String>)`, `timestamp`, `path`
- [ ] Static factory: `ErrorResponse of(String code, String message)`, `ErrorResponse of(String code, String message, Map<String, String> errors)`

---

## 4. REST Controllers

### 4.1 File: `controller/ProductController.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/ProductController.java`

- [ ] Annotate with `@RestController`, `@RequestMapping("/api/v1/products")`, `@Validated`
- [ ] Inject: `ProductService`, `SkuService`, `ProductMapper`, `SkuMapper`
- [ ] **POST /**: `createProduct(CreateProductRequest)` — HTTP 201
  - Extract idempotency key from `X-Idempotency-Key` header
  - Call service, return `ProductResponse`
- [ ] **GET /{id}**: `getProduct(String id)` — HTTP 200
- [ ] **GET /**: `searchProducts(ProductSearchRequest)` — HTTP 200
  - Call `ProductService.search()`, return `PagedResponse<ProductResponse>`
- [ ] **PUT /{id}**: `updateProduct(String id, UpdateProductRequest)` — HTTP 200
- [ ] **DELETE /{id}**: `deleteProduct(String id)` — HTTP 204
  - Return 400 Bad Request if product is ACTIVE
- [ ] **PATCH /{id}/status**: `changeStatus(String id, ChangeStatusRequest)` — HTTP 200
  - Convert `command` string to `ProductEvent`, delegate to service
- [ ] **POST /{id}/skus**: `addSku(String id, CreateSkuRequest)` — HTTP 201
- [ ] **PUT /{id}/skus/{skuId}**: `updateSku(String id, String skuId, UpdateSkuRequest)` — HTTP 200
- [ ] **DELETE /{id}/skus/{skuId}**: `deleteSku(String id, String skuId)` — HTTP 204
- [ ] Add request validation with `@Valid` on all request bodies
- [ ] Add `@RequestHeader("X-Idempotency-Key") String idempotencyKey` to create/update methods
- [ ] Log all operations with structured logging (request ID, product ID, SKU ID)

### 4.2 File: `controller/CategoryController.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/CategoryController.java`

- [ ] Annotate with `@RestController`, `@RequestMapping("/api/v1/categories")`, `@Validated`
- [ ] Inject: `CategoryService`, `CategoryMapper`
- [ ] **GET /tree**: `getCategoryTree()` — HTTP 200
- [ ] **GET /{id}**: `getCategory(String id)` — HTTP 200
- [ ] **GET /**: `listCategories(@RequestParam(required=false) String parentId)` — HTTP 200
- [ ] **POST /**: `createCategory(CreateCategoryRequest)` — HTTP 201
- [ ] **PUT /{id}**: `updateCategory(String id, UpdateCategoryRequest)` — HTTP 200
- [ ] **DELETE /{id}**: `deleteCategory(String id)` — HTTP 204
  - Return 409 Conflict if category has children

---

## 5. Internal REST APIs (Service-to-Service)

### 5.1 File: `controller/InternalProductController.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/InternalProductController.java`

- [ ] Annotate with `@RestController`, `@RequestMapping("/api/v1/internal/products")`
- [ ] **GET /categories/tree**: `getCategoryTreeSnapshot()` — returns full category tree for Search Service sync
- [ ] **GET /export**: `exportProducts(@RequestParam page, @RequestParam size, @RequestParam(required=false) String updatedSince)` — paginated product export for reindexing
- [ ] **GET /{productId}/snapshot**: `getProductSnapshot(String productId)` — full product snapshot for retry replayer
- [ ] Internal endpoints use `@PreAuthorize("hasRole('INTERNAL')")` or service-to-service mTLS
- [ ] No request validation needed (internal callers are trusted)

---

## 6. Global Exception Handler

### 6.1 File: `controller/GlobalExceptionHandler.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/GlobalExceptionHandler.java`

- [ ] Annotate with `@RestControllerAdvice`, `@Slf4j`
- [ ] Handle `ProductNotFoundException`: HTTP 404, `ErrorResponse.of("PRODUCT_NOT_FOUND", message)`
- [ ] Handle `SkuNotFoundException`: HTTP 404
- [ ] Handle `CategoryNotFoundException`: HTTP 404
- [ ] Handle `InvalidStateTransitionException`: HTTP 422, `ErrorResponse.of("INVALID_STATE_TRANSITION", message)`
- [ ] Handle `DuplicateSlugException`: HTTP 409, `ErrorResponse.of("DUPLICATE_SLUG", message)`
- [ ] Handle `DuplicateSkuCodeException`: HTTP 409
- [ ] Handle `DomainException`: HTTP 400, `ErrorResponse.of("DOMAIN_ERROR", message)`
- [ ] Handle `ValidationException`: HTTP 400, include field errors
- [ ] Handle `MethodArgumentNotValidException`: HTTP 400, extract field errors from binding result
- [ ] Handle `ConstraintViolationException`: HTTP 400
- [ ] Handle `OptimisticLockingFailureException`: HTTP 409, `ErrorResponse.of("CONCURRENCY_CONFLICT", "Resource was modified by another request")`
- [ ] Handle `LockAcquisitionException`: HTTP 503, `ErrorResponse.of("SERVICE_UNAVAILABLE", "Could not acquire lock, try again")`
- [ ] Handle all other `Exception`: HTTP 500, log full stack trace, return generic error
- [ ] Extract `HttpServletRequest` for `path` field in error response
- [ ] Add `timestamp` field to all responses

---

## 7. Validation Configuration

### 7.1 File: `config/ValidationConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/ValidationConfig.java`

- [ ] Create `@Configuration` implementing `Validator`
- [ ] Configure `MethodValidationPostProcessor` with Hibernate Validator
- [ ] Register custom validators if needed

---

## 8. Acceptance Criteria

- [ ] All request DTOs have correct Bean Validation annotations
- [ ] All endpoints return correct HTTP status codes (201 for create, 204 for delete, etc.)
- [ ] Validation errors return structured field-level error messages
- [ ] Idempotency key is extracted and passed to service layer
- [ ] Global exception handler covers all domain exceptions with correct HTTP status codes
- [ ] Controller logs include structured fields (requestId, productId, action)
- [ ] Internal endpoints are separated from admin endpoints with different base paths
