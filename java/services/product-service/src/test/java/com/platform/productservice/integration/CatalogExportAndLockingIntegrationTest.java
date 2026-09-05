package com.platform.productservice.integration;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.ReplayOffset;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.service.CatalogExportService;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Internal export cho Search full reindex, và lock order Product → SKU trong
 * database.
 */
class CatalogExportAndLockingIntegrationTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private CatalogExportService exportService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("Export Product phân trang theo cursor và dừng ở upper bound đã chụp")
    void productExportIsBoundedAndPaged() {
        UUID categoryId = givenLeafCategory("phones");
        givenProduct(categoryId, "one", 1000L);
        givenProduct(categoryId, "two", 2000L);
        givenProduct(categoryId, "three", 3000L);

        ProductDtos.ProductExportPageResponse first = exportService.exportProducts(null, 2);
        assertThat(first.products()).hasSize(2);
        assertThat(first.nextCursor()).isNotBlank();

        // Product tạo sau khi export bắt đầu không được kéo dài export vô hạn.
        givenProduct(categoryId, "four", 4000L);

        ProductDtos.ProductExportPageResponse second = exportService.exportProducts(first.nextCursor(), 2);
        assertThat(second.products()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("Export Product mang đầy đủ SKU và media, không có field tồn kho")
    void productExportCarriesFullAggregate() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);

        ProductDtos.ProductExportPageResponse page = exportService.exportProducts(null, 10);

        assertThat(page.products()).hasSize(1);
        ProductDtos.ProductResponse exported = page.products().get(0);
        assertThat(exported.productId()).isEqualTo(product.productId());
        assertThat(exported.skus()).hasSize(1);
        assertThat(exported.skus().get(0).sellingPriceVnd()).isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("Export category mang path và is_leaf dẫn xuất")
    void categoryExportCarriesDerivedFields() {
        UUID rootId = givenLeafCategory("electronics");
        UUID childId = givenChildCategory(rootId, "phones");

        CategoryDtos.CategoryExportPageResponse page = exportService.exportCategories(null, 10);

        assertThat(page.categories()).hasSize(2);
        CategoryDtos.CategoryExportResponse child = page.categories().stream()
                .filter(item -> item.categoryId().equals(childId))
                .findFirst()
                .orElseThrow();
        assertThat(child.isLeaf()).isTrue();
        assertThat(child.path()).hasSize(2);
        CategoryDtos.CategoryExportResponse root = page.categories().stream()
                .filter(item -> item.categoryId().equals(rootId))
                .findFirst()
                .orElseThrow();
        assertThat(root.isLeaf()).isFalse();
    }

    @Test
    @DisplayName("Export session chụp offsets đủ mọi partition trước khi scan")
    void exportOpensSessionWithReplayOffsets() {
        UUID categoryId = givenLeafCategory("phones");
        givenProduct(categoryId, "one", 1000L);

        ProductDtos.ProductExportPageResponse page = exportService.exportProducts(null, 10);

        assertThat(page.replayFromOffsets()).containsExactly(
                new ReplayOffset(PRODUCT_TOPIC, 0, 100L),
                new ReplayOffset(PRODUCT_TOPIC, 1, 200L),
                new ReplayOffset(PRODUCT_TOPIC, 2, 300L));
        assertThat(offsetProvider.captureCount(PRODUCT_TOPIC)).isEqualTo(1);
    }

    @Test
    @DisplayName("Category export chụp offsets của t.category.events, không phải t.product.events")
    void categoryExportUsesItsOwnTopic() {
        givenLeafCategory("phones");

        CategoryDtos.CategoryExportPageResponse page = exportService.exportCategories(null, 10);

        assertThat(page.replayFromOffsets())
                .containsExactly(new ReplayOffset(CATEGORY_TOPIC, 0, 42L));
        assertThat(offsetProvider.captureCount(PRODUCT_TOPIC)).isZero();
    }

    @Test
    @DisplayName("Mọi trang của cùng session trả cùng offsets dù catalog đổi giữa chừng")
    void everyPageOfASessionCarriesTheSameOffsets() {
        UUID categoryId = givenLeafCategory("phones");
        givenProduct(categoryId, "one", 1000L);
        givenProduct(categoryId, "two", 2000L);
        givenProduct(categoryId, "three", 3000L);

        ProductDtos.ProductExportPageResponse first = exportService.exportProducts(null, 2);

        // Broker tiến lên trong lúc export: trang sau vẫn phải giữ boundary cũ,
        // nếu không những event nằm giữa hai lần chụp sẽ không bao giờ được replay.
        offsetProvider.withOffsets(PRODUCT_TOPIC, 999L, 999L, 999L);
        givenProduct(categoryId, "four", 4000L);

        ProductDtos.ProductExportPageResponse second = exportService.exportProducts(first.nextCursor(), 2);

        assertThat(second.replayFromOffsets()).isEqualTo(first.replayFromOffsets());
        assertThat(offsetProvider.captureCount(PRODUCT_TOPIC)).isEqualTo(1);
    }

    @Test
    @DisplayName("Retry đúng một trang trả lại cùng offsets, không mở session mới")
    void retryingOnePageIsIdempotent() {
        UUID categoryId = givenLeafCategory("phones");
        givenProduct(categoryId, "one", 1000L);
        givenProduct(categoryId, "two", 2000L);
        givenProduct(categoryId, "three", 3000L);

        ProductDtos.ProductExportPageResponse first = exportService.exportProducts(null, 2);
        ProductDtos.ProductExportPageResponse retried = exportService.exportProducts(first.nextCursor(), 2);
        ProductDtos.ProductExportPageResponse retriedAgain =
                exportService.exportProducts(first.nextCursor(), 2);

        assertThat(retriedAgain.replayFromOffsets()).isEqualTo(first.replayFromOffsets());
        assertThat(retriedAgain.products()).usingRecursiveComparison().isEqualTo(retried.products());
        assertThat(offsetProvider.captureCount(PRODUCT_TOPIC)).isEqualTo(1);
    }

    @Test
    @DisplayName("Trang rỗng vẫn mang offsets vì contract bắt buộc minItems 1")
    void emptyPageStillCarriesOffsets() {
        ProductDtos.ProductExportPageResponse page = exportService.exportProducts(null, 10);

        assertThat(page.products()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.replayFromOffsets()).hasSize(3);
    }

    @Test
    @DisplayName("Kafka lỗi làm export thất bại 503 thay vì trả snapshot không có boundary")
    void exportFailsWhenOffsetsCannotBeCaptured() {
        UUID categoryId = givenLeafCategory("phones");
        givenProduct(categoryId, "one", 1000L);
        offsetProvider.failNextCaptures();

        assertThatThrownBy(() -> exportService.exportProducts(null, 10))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).errorCode())
                .isEqualTo(ErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("Cursor của Category export không dùng được cho Product export")
    void exportCursorIsBoundToItsOwnStream() {
        UUID parentId = givenLeafCategory("root");
        givenChildCategory(parentId, "phones");
        givenChildCategory(parentId, "laptops");

        String categoryCursor = exportService.exportCategories(null, 1).nextCursor();
        assertThat(categoryCursor).isNotBlank();

        assertThatThrownBy(() -> exportService.exportProducts(categoryCursor, 1))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("Cursor export bị sửa không dùng được")
    void tamperedExportCursorIsRejected() {
        UUID categoryId = givenLeafCategory("phones");
        givenProduct(categoryId, "one", 1000L);
        givenProduct(categoryId, "two", 2000L);

        String cursor = exportService.exportProducts(null, 1).nextCursor();
        String tampered = cursor.substring(0, cursor.length() - 2) + "xy";

        assertThatThrownBy(() -> exportService.exportProducts(tampered, 1))
                .isInstanceOf(com.platform.productservice.common.DomainException.class);
    }

    @Test
    @DisplayName("Product đang bị lock chặn command thứ hai cho tới khi transaction đầu kết thúc")
    void productRowLockSerializesConcurrentCommands() throws Exception {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
                productRepository.lockById(product.productId()).orElseThrow();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> contender = executor.submit(() -> {
                try {
                    // lock_timeout ngắn: chờ lock quá hạn là lỗi tạm thời, không phải lỗi nghiệp vụ.
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = 500");
                        jdbcTemplate.queryForObject(
                                "SELECT catalog_revision FROM products WHERE product_id = ? FOR UPDATE",
                                Long.class, product.productId());
                        return null;
                    });
                    return false;
                } catch (org.springframework.dao.DataAccessException e) {
                    return true;
                }
            });

            assertThat(contender.get(15, TimeUnit.SECONDS))
                    .as("transaction thứ hai phải bị lock timeout")
                    .isTrue();
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        // Sau khi lock được nhả, command bình thường vẫn chạy được.
        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(1L),
                com.platform.productservice.domain.enums.ProductStatus.ACTIVE);
        assertThat(currentRevision(product.productId())).isEqualTo(2L);
    }
}
