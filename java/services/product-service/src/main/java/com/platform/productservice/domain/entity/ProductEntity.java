package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregate root của catalog.
 *
 * <p>{@code catalogRevision} tăng đúng một lần cho mỗi thay đổi observable của
 * aggregate (Product, SKU, media, lifecycle, markdown) và được mang trong mọi
 * catalog event của Product/SKU (docs/product/data.md §2).
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class ProductEntity {

    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 180)
    private String slug;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes_json", nullable = false)
    private Map<String, Object> attributes = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProductStatus status;

    @Column(name = "catalog_revision", nullable = false)
    private long catalogRevision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Tăng revision đúng một lần cho một command nghiệp vụ.
     */
    public long bumpRevision(Instant now) {
        this.catalogRevision += 1;
        this.updatedAt = now;
        return this.catalogRevision;
    }

    /** ETag mạnh theo contracts/http/product/v1/openapi.yaml. */
    public String etag() {
        return etagOf(catalogRevision);
    }

    public static String etagOf(long revision) {
        return "\"catalog-revision-" + revision + "\"";
    }
}
