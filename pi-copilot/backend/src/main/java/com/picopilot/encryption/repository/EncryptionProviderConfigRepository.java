package com.picopilot.encryption.repository;

import com.picopilot.encryption.model.EncryptionProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EncryptionProviderConfigRepository extends JpaRepository<EncryptionProviderConfig, UUID> {
    List<EncryptionProviderConfig> findByWorkspaceId(UUID workspaceId);
    List<EncryptionProviderConfig> findByWorkspaceIdAndStatus(UUID workspaceId, String status);

    long countByWorkspaceIdAndVerifiedTrue(UUID workspaceId);
}
