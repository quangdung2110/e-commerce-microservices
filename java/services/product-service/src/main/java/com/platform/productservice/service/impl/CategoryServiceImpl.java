package com.platform.productservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.event.CategoryCreatedEvent;
import com.platform.productservice.domain.event.CategoryDeletedEvent;
import com.platform.productservice.domain.event.CategoryUpdatedEvent;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.exception.CategoryNotFoundException;
import com.platform.productservice.domain.exception.DomainException;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.Slug;
import com.platform.productservice.port.inbound.CategoryServicePort;
import com.platform.productservice.port.outbound.database.CategoryRepository;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Category service implementation managing hierarchical category operations.
 * Coordinates between domain, repositories, and outbox event publishing.
 */
@Service
@Transactional
public class CategoryServiceImpl implements CategoryServicePort {

    private static final Logger log = LoggerFactory.getLogger(CategoryServiceImpl.class);

    private final CategoryRepository categoryRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public CategoryServiceImpl(
            CategoryRepository categoryRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.categoryRepository = categoryRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Category create(Category category) {
        log.info("CategoryServiceImpl.create: name={}", category.getName());

        if (category.getParentId() != null) {
            categoryRepository.findById(category.getParentId())
                    .orElseThrow(() -> new DomainException(
                            "Parent category not found: " + category.getParentId().getValue()));
            category.setLeaf(false);
        }

        if (category.getSlug() != null && categoryRepository.existsBySlug(category.getSlug())) {
            throw new DomainException("Duplicate slug: " + category.getSlug().getValue());
        }

        category.initialize();

        Category saved = categoryRepository.save(category);

        List<DomainEvent> events = new ArrayList<>();
        events.add(new CategoryCreatedEvent(saved));
        saveOutboxEvents(events);

        log.info("CategoryServiceImpl.create: created category id={}", saved.getId().getValue());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Category findById(String categoryId) {
        CategoryId id = CategoryId.of(categoryId);
        return categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> findTree() {
        log.debug("CategoryServiceImpl.findTree: building category tree");

        List<Category> rootCategories = categoryRepository.findRootCategories();
        Map<String, List<Category>> childrenMap = new HashMap<>();

        for (Category root : rootCategories) {
            buildChildrenMap(root.getId().getValue(), childrenMap);
        }

        return rootCategories;
    }

    private void buildChildrenMap(String parentId, Map<String, List<Category>> childrenMap) {
        List<Category> children = categoryRepository.findChildren(CategoryId.of(parentId));
        if (!children.isEmpty()) {
            childrenMap.put(parentId, children);
            for (Category child : children) {
                buildChildrenMap(child.getId().getValue(), childrenMap);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> findAll() {
        return categoryRepository.findRootCategories();
    }

    @Override
    public Category update(String categoryId, Category updatedData) {
        log.info("CategoryServiceImpl.update: categoryId={}", categoryId);

        CategoryId id = CategoryId.of(categoryId);
        Category existing = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));

        List<String> changedFields = new ArrayList<>();

        if (updatedData.getName() != null && !updatedData.getName().equals(existing.getName())) {
            existing.rename(updatedData.getName());
            changedFields.add("name");
        }

        if (updatedData.getSlug() != null && !updatedData.getSlug().equals(existing.getSlug())) {
            if (categoryRepository.existsBySlug(updatedData.getSlug())) {
                throw new DomainException("Duplicate slug: " + updatedData.getSlug().getValue());
            }
            existing.setSlug(updatedData.getSlug());
            changedFields.add("slug");
        }

        if (updatedData.getParentId() != null && !updatedData.getParentId().equals(existing.getParentId())) {
            categoryRepository.findById(updatedData.getParentId())
                    .orElseThrow(() -> new DomainException(
                            "Parent category not found: " + updatedData.getParentId().getValue()));
            existing.setParentId(updatedData.getParentId());
            changedFields.add("parentId");
        }

        Category saved = categoryRepository.save(existing);

        List<DomainEvent> events = new ArrayList<>();
        events.add(new CategoryUpdatedEvent(saved, changedFields));
        saveOutboxEvents(events);

        log.info("CategoryServiceImpl.update: updated category id={}", categoryId);
        return saved;
    }

    @Override
    public void delete(String categoryId) {
        log.info("CategoryServiceImpl.delete: categoryId={}", categoryId);

        CategoryId id = CategoryId.of(categoryId);
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));

        if (categoryRepository.hasChildren(id)) {
            throw new DomainException("Cannot delete category with children. Remove children first.");
        }

        List<String> affectedProductIds = List.of();

        categoryRepository.delete(category);

        List<DomainEvent> events = new ArrayList<>();
        events.add(new CategoryDeletedEvent(categoryId, affectedProductIds));
        saveOutboxEvents(events);

        log.info("CategoryServiceImpl.delete: deleted category id={}", categoryId);
    }

    private void saveOutboxEvents(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) return;

        try {
            List<OutboxEvent> outboxEvents = new ArrayList<>();
            for (DomainEvent event : events) {
                String payload = objectMapper.writeValueAsString(event.toCloudEventPayload());
                OutboxEvent outboxEvent = OutboxEvent.of(
                        event,
                        event.getAggregateId(),
                        event.getPartitionKey(),
                        payload
                );
                outboxEvents.add(outboxEvent);
            }
            outboxRepository.saveAll(outboxEvents);
        } catch (Exception e) {
            log.error("CategoryServiceImpl: failed to save outbox events: {}", e.getMessage(), e);
        }
    }
}
