package com.platform.productservice.domain.service;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marker annotation for domain services.
 * Domain services contain pure business logic with no infrastructure dependencies.
 * This is a marker annotation for ArchUnit layer validation.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
public @interface DomainServiceMarker {
}
