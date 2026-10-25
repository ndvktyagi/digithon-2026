package com.picopilot.encryption.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "encryption_provider_config")
@Getter
@Setter
public class EncryptionProviderConfig extends TenantAwareEntity {

    @Column(nullable = false, length = 50)
    private String providerType; // AWS_KMS, AZURE_KEY_VAULT, GCP_KMS, VAULT_TRANSIT, LOCAL_DEV

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String endpoint;

    @Column(length = 200)
    private String keyName;

    @Column(length = 100)
    private String keyId;

    @Column(length = 50)
    private String keyVersion;

    @Column(name = "auth_method", length = 100)
    private String authMethod; // WORKLOAD_IDENTITY, MANAGED_IDENTITY, SERVICE_ACCOUNT

    @Column(name = "auth_reference", length = 150)
    private String authReference; // reference to secret manager

    @Column(name = "rotation_policy_days")
    private Integer rotationPolicyDays;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 10;

    @Column(name = "retry_policy", length = 200)
    private String retryPolicy;

    @Column(name = "health_check_enabled")
    private boolean healthCheckEnabled = true;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "is_development")
    private boolean isDevelopment = false;

    @Column(length = 30)
    private String status = "ACTIVE";
}