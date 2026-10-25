package com.picopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "picopilot.scan.worker")
public record ScanWorkerProperties(
    int threadPoolSize,
    int maxQueueSize,
    int batchSize,
    int timeoutMinutes
) {}