package com.picopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Self-issued token login (local admin account + Google Sign-In) used when no
 * external OIDC provider such as Keycloak is available. Disable in production.
 */
@ConfigurationProperties(prefix = "picopilot.security.local-auth")
public record LocalAuthProperties(
    boolean enabled,
    String adminUsername,
    String adminPassword,
    String jwtSecret,
    long tokenTtlMinutes,
    String googleClientId,
    List<String> adminEmails
) {
    public LocalAuthProperties {
        adminEmails = adminEmails == null ? List.of() : adminEmails;
    }

    public boolean googleEnabled() {
        return enabled && googleClientId != null && !googleClientId.isBlank();
    }
}
