# Module 06: Cursor Pagination (CursorCodec)

> **Status:** Module 6 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 04 (Search Query Builders — uses `search_after` sort values)
> **Dependents:** Module 05 (Public API Controllers — uses CursorCodec)
> **Estimated Effort:** 0.5 person-days

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.

---

## 1. Overview

Cursor pagination thay thế `page`/`size` offset-based pagination. Cursor là **base64 encoded JSON array `[sort_value, sku_id]`** — opaque cho client, stateless ở server.

**Lợi ích:**
- Không bị giới hạn bởi ES `index.max_result_window = 10000` (offset pagination).
- Pagination ổn định khi có documents mới được insert/update (offset pagination dễ duplicate/miss nếu index shift).
- Stateless: server không cần lưu cursor state.

**Tie-break `sku_id asc` (xem [Architecture Recommendations §3](search_architecture_recommendations.md)) đảm bảo pagination ổn định khi nhiều documents có cùng sort value.**

---

## 2. Cursor Format

### 2.1 Format Specification

```
cursor = base64url( JSON([sort_value_1, sort_value_2, ..., sku_id]) )
```

**Example:**
- Sort `price_asc`: `[80000000, "sku-abc-123"]` → base64 → `WzgwMDAwMDAwLCJza3UtYWJjLTEyMyJd`
- Sort `relevance`: `[12.34, "sku-xyz-456"]` → base64 → `WzEyLjM0LCJza3UteHl6LTQ1NiJd`
- Sort `rating`: `[4.9, 1200, "sku-rating-789"]` → base64 → `WzQuOSwxMjAwLCJza3UtcmF0aW5nLTc4OSJd`

### 2.2 Edge Cases

| Case | Behavior |
|---|---|
| Cursor = null (first request) | No `search_after` clause, return first page |
| Cursor = "W10=" (empty array base64) | Invalid → 400 `INVALID_CURSOR` |
| Cursor corrupted / not base64 | Throw `InvalidCursorException` → 400 `INVALID_CURSOR` |
| Cursor from older API version | Assume array length mismatches → 400 `INVALID_CURSOR` |
| Last page (no more results) | Return `next_cursor: null` |

---

## 3. CursorCodec Implementation

### 3.1 File: `util/CursorCodec.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/util/CursorCodec.java`

- [ ] Create `@Component` class
- [ ] Inject `ObjectMapper`
- [ ] Method: `String encode(List<FieldValue> sortValues)` — encode sort values → base64 string
- [ ] Method: `List<FieldValue> decode(String cursor)` — decode base64 string → sort values
- [ ] Use Jackson `ObjectMapper.writeValueAsString` + `readValue` for JSON
- [ ] Use Java 8 `Base64.getUrlEncoder().withoutPadding()` + `getUrlDecoder()` (URL-safe base64)
- [ ] Throw `InvalidCursorException` on parse errors

```java
package com.ecommerce.search.util;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.json.JsonData;
import com.ecommerce.search.domain.exception.InvalidCursorException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CursorCodec {

    private final ObjectMapper objectMapper;

    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    /**
     * Encode ES sort values to opaque cursor string.
     * Format: base64url(JSON([sort_value_1, sort_value_2, ..., sku_id]))
     */
    public String encode(List<FieldValue> sortValues) {
        if (sortValues == null || sortValues.isEmpty()) {
            return null;
        }
        try {
            // Convert FieldValue to plain Java objects (for JSON serialization)
            List<Object> plainValues = new ArrayList<>(sortValues.size());
            for (FieldValue fv : sortValues) {
                plainValues.add(toPlainObject(fv));
            }
            String json = objectMapper.writeValueAsString(plainValues);
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to encode cursor", e);
            throw new InvalidCursorException("Failed to encode cursor: " + e.getMessage(), e);
        }
    }

    /**
     * Decode opaque cursor string to ES sort values.
     * Returns null if cursor is null/blank.
     * Throws InvalidCursorException on parse errors.
     */
    public List<FieldValue> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String json = new String(decoded, StandardCharsets.UTF_8);
            List<Object> plainValues = objectMapper.readValue(json, LIST_TYPE);

            List<FieldValue> fieldValues = new ArrayList<>(plainValues.size());
            for (Object obj : plainValues) {
                fieldValues.add(toFieldValue(obj));
            }
            return fieldValues;
        } catch (Exception e) {
            log.warn("Invalid cursor: {}", cursor, e);
            throw new InvalidCursorException("Invalid cursor format: " + e.getMessage(), e);
        }
    }

    /**
     * Convert FieldValue to plain Java object for JSON serialization.
     * FieldValue can be: String, Long, Double, Boolean
     */
    private Object toPlainObject(FieldValue fv) {
        return switch (fv._kind()) {
            case String -> fv.stringValue();
            case Long -> fv.longValue();
            case Double -> fv.doubleValue();
            case Boolean -> fv.booleanValue();
            case Null -> null;
            default -> fv.toString();
        };
    }

    /**
     * Convert plain Java object to FieldValue.
     */
    private FieldValue toFieldValue(Object obj) {
        if (obj == null) {
            return FieldValue.NULL;
        }
        if (obj instanceof String s) {
            return FieldValue.of(s);
        }
        if (obj instanceof Long l) {
            return FieldValue.of(l);
        }
        if (obj instanceof Integer i) {
            return FieldValue.of(i.longValue());
        }
        if (obj instanceof Double d) {
            return FieldValue.of(d);
        }
        if (obj instanceof Float f) {
            return FieldValue.of(f.doubleValue());
        }
        if (obj instanceof Boolean b) {
            return FieldValue.of(b);
        }
        if (obj instanceof Number n) {
            return FieldValue.of(n.doubleValue());
        }
        // Fallback: convert to string
        return FieldValue.of(obj.toString());
    }
}
```

> **Quan trọng:** Sử dụng `Base64.getUrlEncoder().withoutPadding()` (URL-safe, no padding) để cursor có thể dùng trực tiếp trong URL query string không cần encode thêm.

---

## 4. CursorCodec Unit Test

### 4.1 File: `util/CursorCodecTest.java`

**Path:** `java/services/search-service/src/test/java/com/ecommerce/search/util/CursorCodecTest.java`

- [ ] Test: `encode` với empty list → return null
- [ ] Test: `encode` với `[Long 100, String "sku-1"]` → base64 string
- [ ] Test: `decode` với valid cursor → return correct list
- [ ] Test: `decode` với null → return null
- [ ] Test: `decode` với invalid base64 → throw `InvalidCursorException`
- [ ] Test: `decode` với valid base64 nhưng invalid JSON → throw `InvalidCursorException`
- [ ] Test: round-trip encode → decode → same values
- [ ] Test: special characters in sku_id (e.g., UUID) handled correctly

```java
package com.ecommerce.search.util;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.ecommerce.search.domain.exception.InvalidCursorException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CursorCodecTest {

    private CursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new CursorCodec(new ObjectMapper());
    }

    @Test
    @DisplayName("Encode and decode round-trip preserves values")
    void roundTrip() {
        List<FieldValue> original = List.of(
            FieldValue.of(100L),
            FieldValue.of("sku-abc-123")
        );

        String encoded = codec.encode(original);
        assertThat(encoded).isNotNull().isNotBlank();

        List<FieldValue> decoded = codec.decode(encoded);
        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).longValue()).isEqualTo(100L);
        assertThat(decoded.get(1).stringValue()).isEqualTo("sku-abc-123");
    }

    @Test
    @DisplayName("Decode null returns null")
    void decodeNull() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("")).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    @DisplayName("Encode empty list returns null")
    void encodeEmpty() {
        assertThat(codec.encode(null)).isNull();
        assertThat(codec.encode(List.of())).isNull();
    }

    @Test
    @DisplayName("Invalid base64 throws InvalidCursorException")
    void invalidBase64() {
        assertThatThrownBy(() -> codec.decode("not-valid-base64!!!"))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("Valid base64 but invalid JSON throws InvalidCursorException")
    void validBase64InvalidJson() {
        // Encode "not json" as base64
        String notJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not json".getBytes());
        assertThatThrownBy(() -> codec.decode(notJson))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("URL-safe base64 with no padding works in query string")
    void urlSafe() {
        List<FieldValue> original = List.of(
            FieldValue.of(80000000L),
            FieldValue.of("sku-with-special+chars/and=more")
        );
        String encoded = codec.encode(original);
        // Verify no characters that need URL encoding
        assertThat(encoded).doesNotContain("+", "/", "=");
    }
}
```

---

## 5. Acceptance Criteria

- [ ] `encode` returns base64 URL-safe string (no `+`, `/`, `=`)
- [ ] `decode` với null/empty/blank → return null (không throw)
- [ ] `decode` với invalid base64 → throw `InvalidCursorException`
- [ ] `decode` với valid base64 nhưng invalid JSON → throw `InvalidCursorException`
- [ ] Round-trip encode → decode preserves all values
- [ ] Special characters (UUID, slashes) trong sku_id handled correctly
- [ ] Long values (price), Double values (score), String values (sku_id) all serialized correctly
- [ ] Global exception handler catches `InvalidCursorException` → 400 `INVALID_CURSOR`

---

## 6. Integration with ProductSearchService

CursorCodec is used in `ProductSearchService.search()`:

```java
// 1. Decode cursor
List<FieldValue> searchAfter = null;
if (params.cursor() != null && !params.cursor().isBlank()) {
    try {
        searchAfter = cursorCodec.decode(params.cursor());
    } catch (InvalidCursorException e) {
        throw e;  // Global handler returns 400
    }
}

// 2. Build query (with searchAfter)
SearchRequest request = queryBuilder.build(params, searchAfter);

// 3. Execute search
SearchResponse<ProductDocument> response = repository.search(request);

// 4. Build next cursor from last hit
String nextCursor = null;
if (!items.isEmpty() && items.size() == params.size()) {
    Hit<ProductDocument> lastHit = response.hits().hits().get(response.hits().hits().size() - 1);
    if (lastHit.sort() != null && !lastHit.sort().isEmpty()) {
        nextCursor = cursorCodec.encode(lastHit.sort());
    }
}
```

> **Lưu ý:** `lastHit.sort()` là List<FieldValue> từ ES (bao gồm tất cả sort values: sort field + tie-break `sku_id`). CursorCodec encode thành opaque string.

---

## 7. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `util/CursorCodec.java` | ⏳ Not yet |
| 2 | `util/CursorCodecTest.java` | ⏳ Not yet |
