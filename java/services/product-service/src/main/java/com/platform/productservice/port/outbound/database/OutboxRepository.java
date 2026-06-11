package com.platform.productservice.port.outbound.database;

import com.platform.productservice.domain.entity.OutboxEvent;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository {

    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> saveAll(List<OutboxEvent> events);

    int deleteOlderThan(LocalDateTime threshold, int limit);
}
