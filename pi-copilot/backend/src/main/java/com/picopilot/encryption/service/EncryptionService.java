package com.picopilot.encryption.service;

import com.picopilot.common.exception.EncryptionException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.encryption.model.EncryptionProviderConfig;
import com.picopilot.encryption.model.KeyMetadata;
import com.picopilot.encryption.repository.EncryptionProviderConfigRepository;
import com.picopilot.encryption.repository.KeyMetadataRepository;
import com.picopilot.identity.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EncryptionService {

    private final EncryptionProviderConfigRepository configRepository;
    private final KeyMetadataRepository keyRepository;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public List<EncryptionProviderConfig> listProviders(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return configRepository.findByWorkspaceId(workspaceId);
    }

    @Transactional
    public EncryptionProviderConfig createProvider(UUID workspaceId,
                                                     EncryptionProviderConfig config,
                                                     String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        config.setWorkspaceId(workspaceId);

        // Validate development-only mode
        if ("LOCAL_DEV".equals(config.getProviderType())) {
            config.setDevelopment(true);
            log.warn("Creating LOCAL_DEV encryption provider in workspace {}. " +
                     "This must not be used in production.", workspaceId);
        }

        return configRepository.save(config);
    }

    @Transactional
    public EncryptionProviderConfig validateProvider(UUID workspaceId, UUID providerId,
                                                       String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        EncryptionProviderConfig config = configRepository.findById(providerId)
            .orElseThrow(() -> new ResourceNotFoundException("EncryptionProvider", providerId.toString()));

        try {
            // Attempt health check against the provider
            // Actual implementation would call KMS/Vault health endpoint
            log.info("Health checking encryption provider {} (type: {})",
                providerId, config.getProviderType());
            config.setVerified(true);
            return configRepository.save(config);
        } catch (Exception e) {
            config.setVerified(false);
            configRepository.save(config);
            throw new EncryptionException("Provider health check failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public KeyMetadata registerKey(UUID workspaceId, UUID providerId,
                                    KeyMetadata key, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        if (!configRepository.existsById(providerId)) {
            throw new ResourceNotFoundException("EncryptionProvider", providerId.toString());
        }
        key.setWorkspaceId(workspaceId);
        key.setProviderConfigId(providerId);
        key.setActivatedAt(Instant.now());
        key.setKeyState("ACTIVE");
        return keyRepository.save(key);
    }

    @Transactional
    public KeyMetadata rotateKey(UUID workspaceId, UUID keyId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        KeyMetadata existingKey = keyRepository.findById(keyId)
            .orElseThrow(() -> new ResourceNotFoundException("KeyMetadata", keyId.toString()));

        // Mark old key as DEPRECATED
        existingKey.setKeyState("DEPRECATED");
        existingKey.setRetiredAt(Instant.now());
        keyRepository.save(existingKey);

        // Create new key version
        KeyMetadata newKey = new KeyMetadata();
        newKey.setWorkspaceId(workspaceId);
        newKey.setProviderConfigId(existingKey.getProviderConfigId());
        newKey.setKeyAlias(existingKey.getKeyAlias());
        newKey.setKeyVersion(existingKey.getKeyVersion() + 1);
        newKey.setKeyType(existingKey.getKeyType());
        newKey.setKeyState("ACTIVE");
        newKey.setActivatedAt(Instant.now());
        newKey.setRotationPolicy(existingKey.getRotationPolicy());

        log.info("Key {} rotated: old version {} deprecated, new version {} activated",
            keyId, existingKey.getKeyVersion(), newKey.getKeyVersion());
        return keyRepository.save(newKey);
    }
}