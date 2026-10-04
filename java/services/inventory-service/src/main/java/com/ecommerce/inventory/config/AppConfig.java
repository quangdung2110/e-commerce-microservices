package com.ecommerce.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Transactions use the auto-configured {@code DataSourceTransactionManager}, which allows
 * savepoints: a durable domain rejection rolls back the attempted mutation but keeps the
 * command-result row in the same transaction.
 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
