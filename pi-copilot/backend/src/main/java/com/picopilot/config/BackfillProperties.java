package com.picopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "picopilot.backfill")
public record BackfillProperties(
    int batchSize,
    int maxRetries,
    long retryDelayMs,
    int workerThreads
) {}