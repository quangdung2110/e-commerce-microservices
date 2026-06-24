package com.ecommerce.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async execution configuration for background jobs.
 *
 * <p>Provides a dedicated thread pool for the Full Reindex Job (Module 10)
 * so that long-running index operations do not block other async tasks
 * or occupy the main request-processing threads.
 *
 * <h3>Thread pool sizing</h3>
 * <ul>
 *   <li>Core pool: 2 threads — sufficient since only one reindex runs at a time</li>
 *   <li>Max pool: 5 threads — headroom for concurrent catch-up consumer setup</li>
 *   <li>Queue capacity: 10 — small queue to reject excess tasks early</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "reindexExecutor")
    public Executor reindexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("reindex-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
