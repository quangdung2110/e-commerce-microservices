package com.platform.productservice.infrastructure.persistence.outbound.repository;

import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.infrastructure.persistence.jpa.spring.OutboxEventJpaRepository;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.OutboxEventJpaMapper;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@Transactional(readOnly = true)
public class OutboxRepositoryImpl implements OutboxRepository {

    private final OutboxEventJpaRepository jpaRepository;
    private final OutboxEventJpaMapper mapper;

    public OutboxRepositoryImpl(OutboxEventJpaRepository jpaRepository, OutboxEventJpaMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public OutboxEvent save(OutboxEvent event) {
        var entity = mapper.toEntity(event, event.getPayload());
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional
    public List<OutboxEvent> saveAll(List<OutboxEvent> events) {
        var entities = events.stream()
                .map(e -> mapper.toEntity(e, e.getPayload()))
                .toList();
        return jpaRepository.saveAll(entities).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional
    public int deleteOlderThan(LocalDateTime threshold, int limit) {
        return jpaRepository.deleteOlderThan(threshold, limit);
    }
}
