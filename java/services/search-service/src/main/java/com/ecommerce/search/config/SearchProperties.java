package com.ecommerce.search.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    @NotNull
    private Jwt jwt = new Jwt();

    @NotNull
    private Es es = new Es();

    @NotNull
    private Cors cors = new Cors();

    @NotNull
    private Pagination pagination = new Pagination();

    @NotNull
    private Topics topics = new Topics();

    private Reindex reindex = new Reindex();
    private Suggestions suggestions = new Suggestions();
    private DraftCleanup draftCleanup = new DraftCleanup();

    @Data
    public static class Jwt {
        private boolean trustGateway = true;
        @NotBlank
        private String claimsHeader = "X-User-Claims";
    }

    @Data
    public static class Es {
        @Min(1000)
        private int queryTimeoutProductMs = 5000;
        @Min(500)
        private int queryTimeoutSuggestMs = 2000;
        @Min(1)
        private int updateByQueryThrottleRps = 300;
        @NotNull
        private IndexAliases indexAliases = new IndexAliases();
    }

    @Data
    public static class IndexAliases {
        @NotBlank private String products = "products";
        @NotBlank private String productsWrite = "products_write";
        @NotBlank private String categories = "categories";
        @NotBlank private String categoriesWrite = "categories_write";
        @NotBlank private String suggestions = "search_suggestions";
    }

    @Data
    public static class Cors {
        @NotBlank
        private String allowedOrigins = "*";
    }

    @Data
    public static class Pagination {
        @Min(1) private int defaultSize = 20;
        @Min(1) @Max(100) private int maxSize = 100;
    }

    @Data
    public static class Reindex {
        @Min(100) private int checkpointIntervalDocs = 5000;
        @Min(100) private int buildChunkSize = 2000;
    }

    @Data
    public static class Suggestions {
        @NotBlank
        private String rebuildCron = "0 0 3 * * *";
    }

    @Data
    public static class DraftCleanup {
        @NotBlank
        private String cron = "0 0 2 * * *";
        @NotBlank
        private String tombstonePurgerCron = "0 30 2 * * *";
        @Min(1) private int gracePeriodHours = 24;
        @Min(1) private int tombstoneRetentionDays = 30;
    }

    @Data
    public static class Topics {
        @NotBlank private String product = "t.product.events";
        @NotBlank private String category = "t.category.events";
        @NotBlank private String review = "t.review.events";
    }
}
