package com.picopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "picopilot.github")
public record GitHubProperties(
    String appId,
    String appInstallationId,
    String privateKeyRef,
    String apiBaseUrl
) {}