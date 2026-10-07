package com.platform.productservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Category tree. {@code path} và {@code is_leaf} là giá trị dẫn xuất, không lưu
 * (docs/product/data.md §3.1).
 *
 * <p>{@code categoryRevision} tăng đúng một lần cho mỗi mutation observable của
 * Category hoặc mapping category–attribute, và là ordering guard của Category event.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
public class CategoryEntity {

    @Id
    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "slug", nullable = false, length = 160)
    private String slug;

    @Column(name = "category_revision", nullable = false)
    private long categoryRevision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public long bumpRevision(Instant now) {
        this.categoryRevision += 1;
        this.updatedAt = now;
        return this.categoryRevision;
    }
}
