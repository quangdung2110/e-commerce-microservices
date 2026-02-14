package com.example.common.repository;

import com.example.common.event.BaseEvent;

import java.util.List;

/**
 * Interface cho Event Store
 * Lưu trữ tất cả các events theo pattern Event Sourcing
 */
public interface EventStore<T extends BaseEvent> {
    
    /**
     * Lưu một event vào event store
     * @param event Event cần lưu
     */
    void save(T event);
    
    /**
     * Lấy tất cả events của một aggregate
     * @param aggregateId ID của aggregate
     * @return Danh sách events theo thứ tự thời gian
     */
    List<T> findByAggregateId(String aggregateId);
    
    /**
     * Lấy tất cả events của một aggregate type
     * @param aggregateType Loại aggregate
     * @return Danh sách events
     */
    List<T> findByAggregateType(String aggregateType);
    
    /**
     * Lấy events trong một khoảng version
     * @param aggregateId ID của aggregate
     * @param fromVersion Version bắt đầu
     * @param toVersion Version kết thúc
     * @return Danh sách events
     */
    List<T> findByAggregateIdAndVersionBetween(String aggregateId, Integer fromVersion, Integer toVersion);
}
