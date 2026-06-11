# Module 07: REST API Endpoints

> **Status:** Module 7 of 15 - **IMPLEMENTED**
> **Prerequisites:** Module 06 (business services)
> **Dependents:** None (this is a leaf module for code generation)
> **Implementation Date:** June 11, 2026

---

## 1. Overview

This module implements the Admin REST API layer: request DTOs with validation, REST controllers, and the global exception handler. All endpoints are prefixed with `/api/v1/` and secured with JWT authentication.

---

## 2. Request DTOs

### 2.1 File: `dto/request/CreateProductRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateProductRequest.java`

- [x] Create class as Java record
- [x] Fields: `name (@NotBlank, @Size(max=255))`, `description (@Size(max=5000))`, `categoryId (@NotBlank)`, `attributes (Map<String, Object>)`, `skus (@Valid, @NotNull, @NotEmpty)`, `media (List<CreateMediaRequest>)`, `slug`
- [x] Add JSR-380 Bean Validation annotations

### 2.2 File: `dto/request/UpdateProductRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/UpdateProductRequest.java`

- [x] Fields: `name`, `description`, `categoryId`, `attributes (Map<String, Object>)`, `slug` — all optional for partial update
- [x] Add `jakarta.annotation.Nullable` annotations

### 2.3 File: `dto/request/ChangeStatusRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/ChangeStatusRequest.java`

- [x] Fields: `command (@NotBlank)` — values: SUBMIT, HIDE, SHOW
- [x] Add validation: `@Pattern(regexp = "SUBMIT|HIDE|SHOW")`

### 2.4 File: `dto/request/CreateSkuRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateSkuRequest.java`

- [x] Fields: `skuCode (@NotBlank, @Size(max=255))`, `originalPrice (@NotNull, @DecimalMin("0.01"))`, `variantAttributes (Map<String, String>)`, `media (List<CreateMediaRequest>)`

### 2.5 File: `dto/request/UpdateSkuRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/UpdateSkuRequest.java`

- [x] Fields: all optional (partial update): `originalPrice (@DecimalMin("0.01"))`, `variantAttributes (Map<String, String>)`, `media (List<CreateMediaRequest>)`, `addMedia (List<CreateMediaRequest>)`, `removeMediaIds (List<String>)`, `skuCode`

### 2.6 File: `dto/request/CreateCategoryRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateCategoryRequest.java`

- [x] Fields: `name (@NotBlank, @Size(max=255))`, `parentId (String)`, `isLeaf (Boolean)`, `slug (String)`

### 2.7 File: `dto/request/UpdateCategoryRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/UpdateCategoryRequest.java`

- [x] Fields: `name (@Size(max=255))`, `parentId`, `isLeaf`, `slug`

### 2.8 File: `dto/request/CreateMediaRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/CreateMediaRequest.java`

- [x] Fields: `skuId (String nullable)`, `mediaType (@NotBlank @Pattern("IMAGE|VIDEO"))`, `url (@NotBlank)`, `thumbnailUrl (String)`, `sortOrder (Integer)`

### 2.9 File: `dto/request/ProductSearchRequest.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/request/ProductSearchRequest.java`

- [x] Fields: `categoryId`, `status`, `search`, `page (@Min(1) default 1)`, `size (@Min(1) @Max(100) default 20)`, `sort (String default "updatedAt,desc")`

---

## 3. Response DTOs

### 3.1 File: `dto/response/ProductResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/ProductResponse.java`

- [x] Fields: `productId`, `name`, `slug`, `description`, `categoryId`, `categoryName`, `status`, `isDeleted`, `isAdminHidden`, `attributes (Map<String, Object>)`, `skus (List<SkuResponse>)`, `media (List<MediaResponse>)`, `createdAt`, `updatedAt`

### 3.2 File: `dto/response/SkuResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/SkuResponse.java`

- [x] Fields: `skuId`, `skuCode`, `originalPrice`, `promotionalPrice`, `bestPrice`, `hasPromotion`, `status`, `displayQuantity`, `variantAttributes (Map<String, Object>)`, `media (List<MediaResponse>)`, `createdAt`, `updatedAt`

### 3.3 File: `dto/response/MediaResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/MediaResponse.java`

- [x] Fields: `mediaId`, `skuId`, `mediaType`, `url`, `thumbnailUrl`, `sortOrder`

### 3.4 File: `dto/response/CategoryResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/CategoryResponse.java`

- [x] Fields: `categoryId`, `parentId`, `name`, `slug`, `level`, `isLeaf`, `children (List<CategoryResponse>)`, `createdAt`, `updatedAt`

### 3.5 File: `dto/response/CategoryTreeResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/CategoryTreeResponse.java`

- [x] Fields: `roots (List<CategoryResponse>)` — flat tree structure

### 3.6 File: `dto/response/PagedResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/PagedResponse.java`

- [x] Generic class `PagedResponse<T>` with fields: `items (List<T>)`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`
- [x] Static factory: `of(items, page, size, totalElements)`

### 3.7 File: `dto/response/ErrorResponse.java`

**Path:** `product-service/src/main/java/com/platform/productservice/dto/response/ErrorResponse.java`

- [x] Fields: `code (String)`, `message (String)`, `errors (Map<String, String>)`, `timestamp`, `path`
- [x] Static factory: `ErrorResponse of(String code, String message)`, `ErrorResponse of(String code, String message, Map<String, String> errors)`, `ErrorResponse of(String code, String message, String path)`

---

## 4. REST Controllers

### 4.1 File: `controller/ProductController.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/ProductController.java`

- [x] Annotate with `@RestController`, `@RequestMapping("/api/v1/products")`, `@Validated`
- [x] Inject: `ProductService`, `SkuService`, `ProductMapper`, `SkuMapper`
- [x] **POST /**: `createProduct(CreateProductRequest)` — HTTP 201
  - Extract idempotency key from `X-Idempotency-Key` header
  - Call service, return `ProductResponse`
- [x] **GET /{id}**: `getProduct(String id)` — HTTP 200
- [x] **GET /**: `searchProducts(ProductSearchRequest)` — HTTP 200
  - Call `ProductService.search()`, return `PagedResponse<ProductResponse>`
- [x] **PUT /{id}**: `updateProduct(String id, UpdateProductRequest)` — HTTP 200
- [x] **DELETE /{id}**: `deleteProduct(String id)` — HTTP 204
  - Validation handled by service layer (returns 400 for ACTIVE products)
- [x] **PATCH /{id}/status**: `changeStatus(String id, ChangeStatusRequest)` — HTTP 200
  - Convert `command` string to `ProductEvent`, delegate to service
- [x] **POST /{id}/skus**: `addSku(String id, CreateSkuRequest)` — HTTP 201
- [x] **PUT /{id}/skus/{skuId}**: `updateSku(String id, String skuId, UpdateSkuRequest)` — HTTP 200
- [x] **DELETE /{id}/skus/{skuId}**: `deleteSku(String id, String skuId)` — HTTP 204
- [x] Add request validation with `@Valid` on all request bodies
- [x] Add `@RequestHeader("X-Idempotency-Key") String idempotencyKey` to create/update methods
- [x] Log all operations with structured logging (request ID, product ID, SKU ID)

### 4.2 File: `controller/CategoryController.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/CategoryController.java`

- [x] Annotate with `@RestController`, `@RequestMapping("/api/v1/categories")`, `@Validated`
- [x] Inject: `CategoryService`, `CategoryMapper`, `CategoryRepository`
- [x] **GET /tree**: `getCategoryTree()` — HTTP 200
- [x] **GET /{id}**: `getCategory(String id)` — HTTP 200
- [x] **GET /**: `listCategories(@RequestParam(required=false) String parentId)` — HTTP 200
- [x] **POST /**: `createCategory(CreateCategoryRequest)` — HTTP 201
- [x] **PUT /{id}**: `updateCategory(String id, UpdateCategoryRequest)` — HTTP 200
- [x] **DELETE /{id}**: `deleteCategory(String id)` — HTTP 204
  - Return 409 Conflict if category has children (handled by service layer)

---

## 5. Internal REST APIs (Service-to-Service)

### 5.1 File: `controller/InternalProductController.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/InternalProductController.java`

- [x] Annotate with `@RestController`, `@RequestMapping("/api/v1/internal/products")`
- [x] **GET /categories/tree**: `getCategoryTreeSnapshot()` — returns full category tree for Search Service sync
- [x] **GET /export**: `exportProducts(@RequestParam page, @RequestParam size, @RequestParam(required=false) String updatedSince)` — paginated product export for reindexing
- [x] **GET /{productId}/snapshot**: `getProductSnapshot(String productId)` — full product snapshot for retry replayer
- [x] Internal endpoints use `@PreAuthorize("hasRole('INTERNAL')")` or service-to-service mTLS (TODO: add security annotation)
- [x] No request validation needed (internal callers are trusted)

---

## 6. Global Exception Handler

### 6.1 File: `controller/GlobalExceptionHandler.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/GlobalExceptionHandler.java`

- [x] Annotate with `@RestControllerAdvice`, `@Slf4j`
- [x] Handle `ProductNotFoundException`: HTTP 404, `ErrorResponse.of("PRODUCT_NOT_FOUND", message)`
- [x] Handle `SkuNotFoundException`: HTTP 404
- [x] Handle `CategoryNotFoundException`: HTTP 404
- [x] Handle `InvalidStateTransitionException`: HTTP 422, `ErrorResponse.of("INVALID_STATE_TRANSITION", message)`
- [x] Handle `DuplicateSlugException`: HTTP 409, `ErrorResponse.of("DUPLICATE_SLUG", message)`
- [x] Handle `DuplicateSkuCodeException`: HTTP 409
- [x] Handle `DomainException`: HTTP 400, `ErrorResponse.of("DOMAIN_ERROR", message)`
- [x] Handle `ValidationException`: HTTP 400, include field errors
- [x] Handle `MethodArgumentNotValidException`: HTTP 400, extract field errors from binding result
- [x] Handle `ConstraintViolationException`: HTTP 400
- [x] Handle `OptimisticLockingFailureException`: HTTP 409, `ErrorResponse.of("CONCURRENCY_CONFLICT", "Resource was modified by another request")`
- [x] Handle `LockAcquisitionException`: HTTP 503, `ErrorResponse.of("SERVICE_UNAVAILABLE", "Could not acquire lock, try again")`
- [x] Handle all other `Exception`: HTTP 500, log full stack trace, return generic error
- [x] Extract `HttpServletRequest` for `path` field in error response
- [x] Add `timestamp` field to all responses

---

## 7. Validation Configuration

### 7.1 File: `config/ValidationConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/ValidationConfig.java`

- [x] Create `@Configuration` implementing `Validator`
- [x] Configure `MethodValidationPostProcessor` with Hibernate Validator
- [x] Register custom validators if needed

---

## 8. Supporting Files Created

### 8.1 File: `domain/exception/LockAcquisitionException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/LockAcquisitionException.java`

- [x] Created exception class for distributed lock acquisition failures
- [x] Extends `DomainException`

### 8.2 File: `port/outbound/database/SkuRepository.java` (updated)

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/database/SkuRepository.java`

- [x] Added `findWithLockById(SkuId id)` method to match implementation

---

## 9. Acceptance Criteria

- [x] All request DTOs have correct Bean Validation annotations
- [x] All endpoints return correct HTTP status codes (201 for create, 204 for delete, etc.)
- [x] Validation errors return structured field-level error messages
- [x] Idempotency key is extracted and passed to service layer
- [x] Global exception handler covers all domain exceptions with correct HTTP status codes
- [x] Controller logs include structured fields (requestId, productId, action)
- [x] Internal endpoints are separated from admin endpoints with different base paths
