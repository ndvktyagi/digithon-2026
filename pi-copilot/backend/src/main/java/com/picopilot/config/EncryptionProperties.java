package com.picopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "picopilot.encryption")
public record EncryptionProperties(
    String defaultMode,
    String algorithm,
    int keySize,
    boolean envelopeEnabled
) {}