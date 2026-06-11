package com.platform.productservice.infrastructure.persistence.jpa.spring;

import com.platform.productservice.infrastructure.persistence.entity.OutboxEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    void deleteByCreatedAtBefore(LocalDateTime threshold);

    @Modifying
    @Query(value = "DELETE FROM product_outbox_events WHERE created_at < :threshold LIMIT :limit", nativeQuery = true)
    int deleteOlderThan(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.aggregateId = :aggregateId ORDER BY e.createdAt ASC")
    List<OutboxEventJpaEntity> findByAggregateIdOrderByCreatedAt(@Param("aggregateId") String aggregateId);
}
