package com.platform.productservice.integration;

import com.platform.productservice.domain.enums.ProductStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogWarmupTargetIntegrationTest extends AbstractCatalogIntegrationTest {
    @Test
    void selectsPublishedSkuWithoutChangingCatalogOrOutbox() {
        var category = givenLeafCategory("warmup");
        var draft = givenProduct(category, "draft", 1000L);
        var active = givenProduct(category, "published", 1000L);
        productCommandService.changeStatus(active.productId(), etagOf(active.productId()), ProductStatus.ACTIVE);
        int eventsBefore = outboxRows().size();

        var target = skuRepository.findWarmupTarget().orElseThrow();

        assertThat(target.getSkuId()).isEqualTo(active.skus().get(0).skuId());
        assertThat(target.getSlug()).isEqualTo("published");
        assertThat(outboxRows()).hasSize(eventsBefore);
        assertThat(productRepository.findById(draft.productId()).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void emptyOrUnpublishedCatalogNeedsNoSyntheticProduct() {
        assertThat(skuRepository.findWarmupTarget()).isEmpty();
        givenProduct(givenLeafCategory("warmup"), "draft", 1000L);
        assertThat(skuRepository.findWarmupTarget()).isEmpty();
    }
}
