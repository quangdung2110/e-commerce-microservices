package com.ecommerce.search.config;

import com.ecommerce.search.domain.model.SearchCursor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SearchBeans {

    /**
     * The cursor codec uses the Elasticsearch mapper so a sort value round-trips through
     * the cursor with the same JSON representation it had in the search response.
     */
    @Bean
    public SearchCursor searchCursor(ObjectMapper esObjectMapper) {
        return new SearchCursor(esObjectMapper);
    }
}
