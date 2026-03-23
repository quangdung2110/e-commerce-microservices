package com.ecommerce.shipping.infrastructure.persistence.postgres.repository;

import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Theo thiết kế shipping_workers.md mục 1: polling outbox events.
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query(value = """
	    select *
	      from outbox_events
	     where status = 'PENDING'
	       and (next_retry_at is null or next_retry_at <= now())
	     order by created_at asc
	     limit :limit
	     for update skip locked
	    """, nativeQuery = true)
    List<OutboxEventEntity> pollPendingForPublish(@Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
	    update OutboxEventEntity o
	       set o.status = 'PUBLISHED',
		   o.publishedAt = :publishedAt
	     where o.id = :eventId
	    """)
    int markPublished(@Param("eventId") UUID eventId,
		      @Param("publishedAt") OffsetDateTime publishedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
	    update OutboxEventEntity o
	       set o.status = 'FAILED',
		   o.retryCount = :retryCount,
		   o.nextRetryAt = :nextRetryAt
	     where o.id = :eventId
	    """)
    int markFailed(@Param("eventId") UUID eventId,
		   @Param("retryCount") int retryCount,
		   @Param("nextRetryAt") OffsetDateTime nextRetryAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
	    update OutboxEventEntity o
	       set o.status = 'DEAD',
		   o.retryCount = :retryCount
	     where o.id = :eventId
	    """)
    int markDead(@Param("eventId") UUID eventId,
		 @Param("retryCount") int retryCount);
}
