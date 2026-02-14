package com.example.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "temporal")
public class TemporalProperties {
    private String host = "localhost";
    private int port = 7233;
    private String namespace = "default";
    
    // Worker configurations
    private Worker worker = new Worker();
    
    // Workflow configurations
    private Workflow workflow = new Workflow();
    
    @Data
    public static class Worker {
        private String taskQueue = "order-saga-task-queue";
        private int maxConcurrentWorkflowTasks = 100;
        private int maxConcurrentActivityTasks = 100;
    }
    
    @Data
    public static class Workflow {
        private long executionTimeout = 1800; // 30 minutes
        private long taskTimeout = 300; // 5 minutes
        private int maxRetryAttempts = 3;
    }
}
