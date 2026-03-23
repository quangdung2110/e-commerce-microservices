package com.ecommerce.shipping.infrastructure.persistence.postgres.repository;

import com.ecommerce.shipping.infrastructure.persistence.postgres.entity.WebhookIdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Theo thiết kế shipping_state_machine.md mục 4.1: INSERT ... ON CONFLICT DO NOTHING chống webhook duplicate.
public interface WebhookIdempotencyJpaRepository extends JpaRepository<WebhookIdempotencyEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO webhook_idempotency(provider, provider_event_id)
            VALUES (:provider, :providerEventId)
            ON CONFLICT (provider, provider_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(@Param("provider") String provider,
                     @Param("providerEventId") String providerEventId);
}
