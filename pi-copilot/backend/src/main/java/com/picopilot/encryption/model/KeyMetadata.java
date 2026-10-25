package com.picopilot.encryption.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "key_metadata")
@Getter
@Setter
public class KeyMetadata extends TenantAwareEntity {

    @Column(name = "provider_config_id", nullable = false)
    private UUID providerConfigId;

    @Column(nullable = false, length = 100)
    private String keyAlias;

    @Column(length = 200)
    private String keyIdentifier; // KMS key ID or Vault key path

    @Column(nullable = false)
    private int keyVersion;

    @Column(nullable = false, length = 30)
    private String keyType; // KEK, DEK, BLIND_INDEX

    @Column(name = "key_state", length = 20)
    private String keyState; // ACTIVE, DEPRECATED, RETIRED

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "rotation_policy", columnDefinition = "jsonb")
    private String rotationPolicy;

    @Column(name = "wrapped_key_reference", length = 200)
    private String wrappedKeyReference; // reference to wrapped key in KMS/Vault
}