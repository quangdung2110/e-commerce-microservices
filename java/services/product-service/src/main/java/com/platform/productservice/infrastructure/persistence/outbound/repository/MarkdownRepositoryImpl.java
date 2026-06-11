package com.platform.productservice.infrastructure.persistence.outbound.repository;

import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.infrastructure.persistence.jpa.spring.MarkdownAppliedJpaRepository;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.MarkdownAppliedJpaMapper;
import com.platform.productservice.port.outbound.MarkdownRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@Transactional(readOnly = true)
public class MarkdownRepositoryImpl implements MarkdownRepository {

    private final MarkdownAppliedJpaRepository jpaRepository;
    private final MarkdownAppliedJpaMapper mapper;

    public MarkdownRepositoryImpl(MarkdownAppliedJpaRepository jpaRepository, MarkdownAppliedJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public MarkdownApplied save(MarkdownApplied markdown) {
        var entity = mapper.toEntity(markdown);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<MarkdownApplied> findByRuleId(String ruleId) {
        return jpaRepository.findByRuleId(ruleId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<MarkdownApplied> findByRuleIdAndSkuId(String ruleId, String skuId) {
        return jpaRepository.findByRuleIdAndSkuId(ruleId, skuId).map(mapper::toDomain);
    }

    @Override
    public List<MarkdownApplied> findBySkuId(String skuId) {
        return jpaRepository.findBySkuId(skuId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<MarkdownApplied> findByProductId(ProductId productId) {
        return jpaRepository.findByProductId(productId.getValue()).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<MarkdownApplied> findExpiredMarkdowns(Instant now) {
        return jpaRepository.findExpiredMarkdowns(now).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteByRuleId(String ruleId) {
        jpaRepository.deleteByRuleId(ruleId);
    }

    @Override
    @Transactional
    public void delete(MarkdownApplied markdown) {
        jpaRepository.delete(mapper.toEntity(markdown));
    }
}
