package com.ecommerce.search.config;

import com.ecommerce.search.domain.model.SearchCursor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class SearchBeans {

    /** Cursor payload uses the ES mapper and a shared deployment signing key. */
    @Bean
    public SearchCursor searchCursor(ObjectMapper esObjectMapper,
            @Value("${search.pagination.cursor-secret}") String secret) {
        return new SearchCursor(esObjectMapper, secret);
    }
}
