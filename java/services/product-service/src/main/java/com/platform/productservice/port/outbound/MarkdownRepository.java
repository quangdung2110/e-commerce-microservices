package com.platform.productservice.port.outbound;

import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.domain.vo.ProductId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MarkdownRepository {

    MarkdownApplied save(MarkdownApplied markdown);

    List<MarkdownApplied> findByRuleId(String ruleId);

    Optional<MarkdownApplied> findByRuleIdAndSkuId(String ruleId, String skuId);

    List<MarkdownApplied> findBySkuId(String skuId);

    List<MarkdownApplied> findByProductId(ProductId productId);

    List<MarkdownApplied> findExpiredMarkdowns(Instant now);

    void deleteByRuleId(String ruleId);

    void delete(MarkdownApplied markdown);
}
