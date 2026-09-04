package com.platform.productservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Cấu hình riêng của Product Service (prefix {@code product}).
 *
 * <p>Không có mục cache, inventory hay scheduler: thiết kế hiện hành không có
 * các workload đó (docs/product/architecture.md).
 */
@ConfigurationProperties(prefix = "product")
@Getter
@Setter
public class ProductProperties {

    private Admin admin = new Admin();
    private Internal internal = new Internal();
    private Export export = new Export();
    private Markdown markdown = new Markdown();

    @Getter
    @Setter
    public static class Admin {

        /** Role bắt buộc trên header trusted do Gateway inject. */
        private String requiredRole = "ADMIN";

        private String userIdHeader = "X-User-Id";

        private String rolesHeader = "X-User-Roles";
    }

    @Getter
    @Setter
    public static class Internal {

        /**
         * Endpoint {@code /internal/v1/**} dùng mTLS service identity trên mạng
         * nội bộ. Application chỉ tin identity đã được ingress/mesh xác minh và
         * đặt vào header này; TLS termination là trách nhiệm hạ tầng.
         */
        private String identityHeader = "X-Service-Identity";

        private List<String> allowedClients = List.of("search-service");
    }

    @Getter
    @Setter
    public static class Export {

        /** Khóa ký cursor export; lấy từ secret manager ở môi trường thật. */
        private String cursorSecret = "change-me-export-cursor-secret";

        private Duration cursorTtl = Duration.ofMinutes(30);

        private int defaultLimit = 200;

        private int maxLimit = 500;
    }

    @Getter
    @Setter
    public static class Markdown {

        private String topic = "t.promotion.markdown.events";

        private String dlqTopic = "t.promotion.markdown.events.dlq";

        private String groupId = "product-markdown-consumer";

        /** Lock timeout ngắn cho transaction của consumer; timeout là lỗi tạm thời. */
        private Duration lockTimeout = Duration.ofSeconds(3);

        private int retryAttempts = 4;

        private Duration retryBackoff = Duration.ofMillis(500);
    }
}
