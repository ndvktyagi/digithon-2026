package com.picopilot.encryption.repository;

import com.picopilot.encryption.model.KeyMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KeyMetadataRepository extends JpaRepository<KeyMetadata, UUID> {
    List<KeyMetadata> findByProviderConfigId(UUID providerConfigId);
    Optional<KeyMetadata> findByProviderConfigIdAndKeyAliasAndKeyVersion(
        UUID providerConfigId, String keyAlias, int keyVersion);
    Optional<KeyMetadata> findByProviderConfigIdAndKeyAliasAndKeyState(
        UUID providerConfigId, String keyAlias, String keyState);
}