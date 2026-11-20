package com.ecommerce.pdp.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Cache in-process phần catalog Product của PDP (ADR-011, operations.md §Cấu hình). Chỉ
 * giữ buyer-read Product đã validate; Inventory và Review luôn được gọi theo từng request.
 *
 * @param enabled   tắt thì mỗi request gọi Product như trước ADR-011
 * @param ttl       độ trễ tối đa của catalog trên PDP; tính từ lúc Product trả, không gia hạn
 *                  khi được đọc
 * @param maxWeight trần bộ nhớ ước lượng của các entry; vượt thì entry ít dùng bị loại
 */
@Validated
@ConfigurationProperties(prefix = "pdp.catalog-cache")
public record CatalogCacheProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("10s") Duration ttl,
        @NotNull @DefaultValue("64MB") DataSize maxWeight) {

    /** Một phút là trần: lâu hơn thì giá và markdown trên PDP trễ quá xa so với Checkout. */
    @AssertTrue(message = "pdp.catalog-cache.ttl must be between 1s and 60s when the cache is enabled")
    public boolean isTtlBounded() {
        return !enabled || ttl != null
                && ttl.compareTo(Duration.ofSeconds(1)) >= 0 && ttl.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    @AssertTrue(message = "pdp.catalog-cache.max-weight must be between 1MB and 1GB when the cache is enabled")
    public boolean isMaxWeightBounded() {
        return !enabled || maxWeight != null
                && maxWeight.toMegabytes() >= 1 && maxWeight.toBytes() <= DataSize.ofGigabytes(1).toBytes();
    }
}
